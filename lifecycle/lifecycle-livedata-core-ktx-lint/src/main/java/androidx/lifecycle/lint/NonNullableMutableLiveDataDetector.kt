/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.lifecycle.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Detector.UastScanner
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.UastLintUtils.Companion.tryResolveUDeclaration
import com.android.tools.lint.detector.api.isKotlin
import com.intellij.openapi.components.ServiceManager
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.TypeNullability
import org.jetbrains.kotlin.types.typeUtil.getImmediateSuperclassNotAny
import org.jetbrains.kotlin.types.typeUtil.nullability
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UResolvable
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.isNullLiteral
import org.jetbrains.uast.kotlin.KotlinAbstractUExpression
import org.jetbrains.uast.kotlin.KotlinUFunctionCallExpression
import org.jetbrains.uast.kotlin.KotlinUSimpleReferenceExpression
import org.jetbrains.uast.kotlin.KotlinUastResolveProviderService
import org.jetbrains.uast.resolveToUElement
import org.jetbrains.uast.toUElementOfType

/**
 * Lint check for ensuring that [androidx.lifecycle.MutableLiveData] values are never null when
 * the type is defined as non-nullable in Kotlin.
 */
class NonNullableMutableLiveDataDetector : Detector(), UastScanner {

    companion object {
        val ISSUE = Issue.Companion.create(
            id = "NullSafeMutableLiveData",
            briefDescription = "LiveData value assignment nullability mismatch",
            explanation = """This check ensures that LiveData values are not null when explicitly \
                declared as non-nullable.

                Kotlin interoperability does not support enforcing explicit null-safety when using \
                generic Java type parameters. Since LiveData is a Java class its value can always \
                be null even when its type is explicitly declared as non-nullable. This can lead \
                to runtime exceptions from reading a null LiveData value that is assumed to be \
                non-nullable.""",
            category = Category.INTEROPERABILITY_KOTLIN,
            severity = Severity.FATAL,
            implementation = Implementation(
                NonNullableMutableLiveDataDetector::class.java,
                Scope.JAVA_FILE_SCOPE
            ),
            androidSpecific = true
        )
    }

    val methods = listOf("setValue", "postValue")

    override fun getApplicableUastTypes(): List<Class<out UElement>>? {
        return listOf(UCallExpression::class.java)
    }

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        return object : UElementHandler() {

            override fun visitCallExpression(node: UCallExpression) {
                val isSupportedMethod = methods.contains(node.methodName) ||
                    node.kind == UastCallKind.CONSTRUCTOR_CALL
                if (!isKotlin(node.sourcePsi) || !isSupportedMethod) return

                val calledMethod = node.resolve() ?: return

                if (!context.evaluator.isMemberInSubClassOf(
                        calledMethod,
                        "androidx.lifecycle.LiveData", false
                    )
                ) return

                checkMethod(context, node)
            }
        }
    }

    internal fun checkMethod(context: JavaContext, node: UCallExpression) {
        val receiver = getReceiver(node)
        val declaration = getDeclaration(receiver)

        val sourcePsi = receiver?.sourcePsi as? KtExpression ?: return

        val bindingContext = ServiceManager.getService(
            sourcePsi.project,
            KotlinUastResolveProviderService::class.java
        )?.getBindingContext(sourcePsi)!!
        val type = bindingContext.getType(sourcePsi) ?: return

        val genericType = getGenericType(type) ?: return

        checkNullability(declaration, genericType, context, node)
    }

    private fun checkNullability(
        declaration: KtTypeReference?,
        genericType: KotlinType,
        context: JavaContext,
        node: UCallExpression,
    ) {
        if (genericType.nullability() == TypeNullability.NOT_NULL) {
            val fixes = mutableListOf<LintFix>()
            if (declaration != null) {
                fixes.add(
                    fix().name("Change `LiveData` type to nullable")
                        .replace().with("?").range(context.getLocation(declaration)).end()
                        .build()
                )
            }
            val argument = node.valueArguments.firstOrNull() ?: return
            when {
                argument.isNullLiteral() -> {
                    // Don't report null!! quick fix.
                    checkNullability(
                        context,
                        argument,
                        "Cannot set non-nullable LiveData value to `null`",
                        fixes
                    )
                }
                argument.isNullable() -> {
                    fixes.add(
                        fix().name("Add non-null asserted (!!) call")
                            .replace().with("!!").range(context.getLocation(argument)).end().build()
                    )
                    checkNullability(context, argument, "Expected non-nullable value", fixes)
                }
                argument.sourcePsi.isNullable() -> {
                    // Don't report !! quick fix for expression.
                    checkNullability(context, argument, "Expected non-nullable value", fixes)
                }
            }
        }
    }

    /**
     * Reports a lint error at [element]'s location with message and quick fixes.
     *
     * @param context The lint detector context.
     * @param element The [UElement] to report this error at.
     * @param message The error message to report.
     * @param fixes The Lint Fixes to report.
     */
    private fun checkNullability(
        context: JavaContext,
        element: UElement,
        message: String,
        fixes: List<LintFix>,
    ) {
        if (fixes.isEmpty()) {
            context.report(ISSUE, context.getLocation(element), message)
        } else {
            context.report(
                ISSUE, context.getLocation(element), message,
                fix().alternatives(*fixes.toTypedArray())
            )
        }
    }

    /**
     * Iterates [type]'s hierarchy to find its [androidx.lifecycle.LiveData] value type.
     *
     * @param type The [KotlinType] to search
     * @return The LiveData type argument.
     */
    private fun getGenericType(type: KotlinType): KotlinType? {
        return type.arguments.singleOrNull()?.type
            ?: type.getImmediateSuperclassNotAny()?.let(::getGenericType)
    }

    /**
     * If the node does not have a receiver, try fetching via
     * [KotlinUSimpleReferenceExpression.KotlinAccessorCallExpression]. For example
     * `liveData.apply { value = null }`
     *
     * @param node The [UCallExpression] to search
     * @return receiver of expression
     */
    private fun getReceiver(node: UCallExpression): UExpression? {
        val receiver = node.receiver
        if (receiver != null) {
            return receiver
        }
        if (node is KotlinUSimpleReferenceExpression.KotlinAccessorCallExpression) {
            var parent: UElement? = node.uastParent
            while (parent != null) {
                if (parent is KotlinUFunctionCallExpression) {
                    return parent.receiver
                }
                parent = parent.uastParent
            }
        }
        return null
    }

    /**
     * Try fetching via field declaration.
     *
     * @param receiver The [UElement] to search
     * @return receiver of expression
     */
    private fun getDeclaration(receiver: UElement?): KtTypeReference? {
        val declaration = (receiver as? UResolvable)?.resolve()?.toUElementOfType<UDeclaration>()
        return (declaration?.sourcePsi as? KtDeclaration)?.let {
            val expression = it
                .children.firstOrNull { it is KtCallExpression } as? KtCallExpression
            expression?.typeArguments?.singleOrNull()?.typeReference
        }
    }
}

/**
 * Checks if the [UElement] is nullable. Always returns `false` if the [UElement] is not a
 * [KotlinAbstractUExpression] or [UCallExpression].
 *
 * @return `true` if instance is nullable, `false` otherwise.
 */
internal fun UElement.isNullable(): Boolean {
    if (this is UCallExpression) {
        val psiMethod = resolve() ?: return false
        return psiMethod.hasAnnotation(NULLABLE_ANNOTATION)
    } else if (this is UReferenceExpression) {
        return (resolveToUElement() as? UAnnotated)?.findAnnotation(NULLABLE_ANNOTATION) != null
    }
    return false
}

internal fun PsiElement?.isNullable(): Boolean {
    return (this as? KtExpression)?.isNullable() == true
}

/**
 * @return `true` if expression type is nullable, `false` if expression type is flexible or
 * not-null.
 */
internal fun KtExpression.isNullable(bindingContext: BindingContext? = null): Boolean {
    val context = bindingContext ?: ServiceManager.getService(
        this.project,
        KotlinUastResolveProviderService::class.java
    )?.getBindingContext(this) ?: return false
    val type = context.getType(this) ?: return false
    val nullability = type.nullability()
    if (nullability == TypeNullability.NULLABLE) {
        return true
    }
    if (nullability == TypeNullability.NOT_NULL) {
        return false
    }

    val call = context[BindingContext.CALL, this]
        ?: run {
            // Check elvis expression
            val operationReference = (this as? KtBinaryExpression)?.operationReference
            context[BindingContext.CALL, operationReference]
        }
        ?: return false
    val args = call.valueArguments
    args.forEach {
        if (it.getArgumentExpression()?.isNullable(context) == true) {
            return true
        }
    }
    return false
}

const val NULLABLE_ANNOTATION = "org.jetbrains.annotations.Nullable"
