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

import androidx.lifecycle.lint.stubs.STUBS
import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NonNullableMutableLiveDataDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = NonNullableMutableLiveDataDetector()

    override fun getIssues(): MutableList<Issue> =
        mutableListOf(NonNullableMutableLiveDataDetector.ISSUE)

    private fun check(vararg files: TestFile): TestLintResult {
        return lint().files(*files, *STUBS)
            .run()
    }

    @Test
    fun pass() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                fun foo() {
                    val liveData = MutableLiveData<Boolean>()
                    val x = true
                    liveData.value = x
                    liveData.postValue(bar(5))
                    val myLiveData = MyLiveData()
                    liveData.value = x
                }

                fun bar(x: Int): Boolean {
                    return x > 0
                }
            """
            ).indented(),
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyLiveData : MyLiveData2()
                open class MyLiveData2 : GenericLiveData<Boolean>()
                open class GenericLiveData<T> : MutableLiveData<T>()
            """
            ).indented()
        ).expectClean()
    }

    @Test
    fun mutableListAssignmentPass() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                fun foo() {
                    val lists = MutableLiveData<List<Int>>()
                    val map = HashMap<Int, Int>()

                    map[1] = 1

                    lists.value = map.values.toMutableList()
                }
            """
            ).indented()
        ).expectClean()
    }

    @Test
    fun helperMethodFails() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                fun foo() {
                    val liveData = MutableLiveData<Boolean>()
                    liveData.value = bar(5)
                }

                fun bar(x: Int): Boolean? {
                    if (x > 0) return true
                    return null
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/test.kt:7: Error: Expected non-nullable value [NullSafeMutableLiveData]
    liveData.value = bar(5)
                     ~~~~~~
1 errors, 0 warnings
        """
        )
    }

    @Test
    fun variableAssignmentFails() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                fun foo() {
                    val liveData = MutableLiveData<Boolean>()
                    val bar: Boolean? = null
                    liveData.value = bar
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/test.kt:8: Error: Expected non-nullable value [NullSafeMutableLiveData]
    liveData.value = bar
                     ~~~
1 errors, 0 warnings
        """
        ).expectFixDiffs(
            """
Fix for src/com/example/test.kt line 8: Change `LiveData` type to nullable:
@@ -6 +6
-     val liveData = MutableLiveData<Boolean>()
+     val liveData = MutableLiveData<Boolean?>()
Fix for src/com/example/test.kt line 8: Add non-null asserted (!!) call:
@@ -8 +8
-     liveData.value = bar
+     liveData.value = bar!!
        """
        )
    }

    @Test
    fun nullLiteralFailField() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                val liveDataField = MutableLiveData<Boolean>()

                fun foo() {
                    liveDataField.value = null
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/test.kt:8: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
    liveDataField.value = null
                          ~~~~
1 errors, 0 warnings
        """
        )
    }

    @Test
    fun nullLiteralFailMultipleFields() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                val liveDataField = MutableLiveData<Boolean>()
                val secondLiveDataField = MutableLiveData<String>()

                fun foo() {
                    liveDataField.value = null
                    secondLiveDataField.value = null
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/test.kt:9: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
    liveDataField.value = null
                          ~~~~
src/com/example/test.kt:10: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
    secondLiveDataField.value = null
                                ~~~~
2 errors, 0 warnings
        """
        )
    }

    @Test
    fun nullLiteralFailMultipleFieldsDifferentNullability() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                val liveDataField = MutableLiveData<Boolean>()
                val secondLiveDataField = MutableLiveData<String?>()

                fun foo() {
                    liveDataField.value = false
                    secondLiveDataField.value = null
                }
            """
            ).indented()
        ).expectClean()
    }

    @Test
    fun nullLiteralFailMultipleAssignment() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                val liveDataField = MutableLiveData<Boolean>()

                fun foo() {
                    liveDataField.value = false
                    liveDataField.value = null
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/test.kt:9: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
    liveDataField.value = null
                          ~~~~
1 errors, 0 warnings
        """
        )
    }

    @Test
    fun nullLiteralFailFieldAndIgnore() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                val liveDataField = MutableLiveData<Boolean>()
                val ignoreThisField = ArrayList<String>(arrayListOf("a", "b"))

                fun foo() {
                    liveDataField.value = null
                    ignoreThisField[0] = null
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/test.kt:9: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
    liveDataField.value = null
                          ~~~~
1 errors, 0 warnings
        """
        )
    }

    @Test
    fun nullLiteralFieldApply() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass {
                    val liveDataField = MutableLiveData<Boolean>().apply { value = null }

                    fun foo() {
                        liveDataField.value = false
                    }
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/MyClass.kt:6: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
    val liveDataField = MutableLiveData<Boolean>().apply { value = null }
                                                                   ~~~~
1 errors, 0 warnings
        """
        )
    }

    @Test
    fun companionObjectCheck() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass {
                    companion object {
                        val liveDataField = MutableLiveData(true)
                    }

                    fun foo() {
                        liveDataField.value = false
                    }
                }
            """
            ).indented()
        ).expectClean()
    }

    @Test
    fun nullLiteralFailFieldAndLocalVariable() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                val liveDataField = MutableLiveData<Boolean>()

                fun foo() {
                    liveDataField.value = null
                    val liveDataVariable = MutableLiveData<Boolean>()
                    liveDataVariable.value = null
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/test.kt:8: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
    liveDataField.value = null
                          ~~~~
src/com/example/test.kt:10: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
    liveDataVariable.value = null
                             ~~~~
2 errors, 0 warnings
        """
        )
    }

    @Test
    fun nullLiteralQuickFix() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                fun foo() {
                    val liveData = MutableLiveData<Boolean>()
                    liveData.value = null
                }
            """
            ).indented()
        ).expectFixDiffs(
            """
Fix for src/com/example/test.kt line 7: Change `LiveData` type to nullable:
@@ -6 +6
-     val liveData = MutableLiveData<Boolean>()
+     val liveData = MutableLiveData<Boolean?>()
        """
        )
    }

    @Test
    fun classHierarchyTest() {
        check(
            kotlin(
                """
                package com.example

                fun foo() {
                    val liveData = MyLiveData()
                    val bar: Boolean? = true
                    liveData.value = bar
                }
            """
            ).indented(),
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyLiveData : MyLiveData2()
                open class MyLiveData2 : GenericLiveData<Boolean>()
                open class GenericLiveData<T> : MutableLiveData<T> {
                    constructor() : this()
                    constructor(value: T): this(value)
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/test.kt:6: Error: Expected non-nullable value [NullSafeMutableLiveData]
    liveData.value = bar
                     ~~~
1 errors, 0 warnings
        """
        ).expectFixDiffs(
            """
Fix for src/com/example/test.kt line 6: Add non-null asserted (!!) call:
@@ -6 +6
-     liveData.value = bar
+     liveData.value = bar!!
        """
        )
    }

    @Test
    fun differentClassSameFieldTestFirstNull() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass1 {
                    val liveDataField = MutableLiveData<Boolean>()

                    fun foo() {
                        liveDataField.value = null
                    }
                }
            """
            ).indented(),
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass2 {
                    val liveDataField = MutableLiveData<Boolean>()

                    fun foo() {
                        liveDataField.value = false
                    }
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/MyClass1.kt:9: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
        liveDataField.value = null
                              ~~~~
1 errors, 0 warnings
        """
        ).expectFixDiffs(
            """
Fix for src/com/example/MyClass1.kt line 9: Change `LiveData` type to nullable:
@@ -6 +6
-     val liveDataField = MutableLiveData<Boolean>()
+     val liveDataField = MutableLiveData<Boolean?>()
        """
        )
    }

    @Test
    fun differentClassSameFieldTestSecondNull() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass1 {
                    val liveDataField = MutableLiveData<Boolean>()

                    fun foo() {
                        liveDataField.value = false
                    }
                }
            """
            ).indented(),
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass2 {
                    val liveDataField = MutableLiveData<Boolean>()

                    fun foo() {
                        liveDataField.value = null
                    }
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/MyClass2.kt:9: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
        liveDataField.value = null
                              ~~~~
1 errors, 0 warnings
        """
        ).expectFixDiffs(
            """
Fix for src/com/example/MyClass2.kt line 9: Change `LiveData` type to nullable:
@@ -6 +6
-     val liveDataField = MutableLiveData<Boolean>()
+     val liveDataField = MutableLiveData<Boolean?>()
        """
        )
    }

    @Test
    fun nestedClassSameFieldTest() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass1 {
                    val liveDataField = MutableLiveData<Boolean>()

                    fun foo() {
                        liveDataField.value = false
                    }

                    class MyClass2 {
                        val liveDataField = MutableLiveData<Boolean>()

                        fun foo() {
                            liveDataField.value = null
                        }
                    }
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/MyClass1.kt:16: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
            liveDataField.value = null
                                  ~~~~
1 errors, 0 warnings
        """
        ).expectFixDiffs(
            """
Fix for src/com/example/MyClass1.kt line 16: Change `LiveData` type to nullable:
@@ -13 +13
-         val liveDataField = MutableLiveData<Boolean>()
+         val liveDataField = MutableLiveData<Boolean?>()
        """
        )
    }

    @Test
    fun modifiersFieldTest() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.LiveData
                import androidx.lifecycle.MutableLiveData

                class MyClass1 {
                    internal val firstLiveDataField = MutableLiveData<Boolean>()
                    protected val secondLiveDataField = MutableLiveData<Boolean?>()

                    fun foo() {
                        firstLiveDataField.value = false
                        firstLiveDataField.value = null
                        secondLiveDataField.value = null
                        secondLiveDataField.value = false
                    }
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/MyClass1.kt:12: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
        firstLiveDataField.value = null
                                   ~~~~
1 errors, 0 warnings
        """
        ).expectFixDiffs(
            """
Fix for src/com/example/MyClass1.kt line 12: Change `LiveData` type to nullable:
@@ -7 +7
-     internal val firstLiveDataField = MutableLiveData<Boolean>()
+     internal val firstLiveDataField = MutableLiveData<Boolean?>()
        """
        )
    }

    @Test
    fun implementationClassTest() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.LiveData
                import androidx.lifecycle.MutableLiveData

                interface MyClass2 {
                    val firstLiveDataField : LiveData<Boolean>
                    val secondLiveDataField : LiveData<Boolean?>
                    val thirdLiveDataField : LiveData<Boolean?>
                    val fourLiveDataField : LiveData<List<Boolean>?>
                    val fiveLiveDataField : LiveData<List<Boolean>?>
                }

                class MyClass1 : MyClass2 {
                    override val firstLiveDataField = MutableLiveData<Boolean>()
                    override val secondLiveDataField = MutableLiveData<Boolean?>()
                    override val thirdLiveDataField = MutableLiveData<Boolean?>(null)
                    override val fourLiveDataField = MutableLiveData<List<Boolean>?>(null)
                    override val fiveLiveDataField : MutableLiveData<List<Boolean>?> = MutableLiveData(null)

                    fun foo() {
                        firstLiveDataField.value = false
                        firstLiveDataField.value = null
                        secondLiveDataField.value = null
                        secondLiveDataField.value = false
                        thirdLiveDataField.value = null
                        thirdLiveDataField.value = false
                        fourLiveDataField.value = null
                        fourLiveDataField.value = emptyList()
                        fiveLiveDataField.value = null
                        fiveLiveDataField.value = emptyList()
                    }
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/MyClass2.kt:23: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
        firstLiveDataField.value = null
                                   ~~~~
1 errors, 0 warnings
        """
        ).expectFixDiffs(
            """
Fix for src/com/example/MyClass2.kt line 23: Change `LiveData` type to nullable:
@@ -15 +15
-     override val firstLiveDataField = MutableLiveData<Boolean>()
+     override val firstLiveDataField = MutableLiveData<Boolean?>()
        """
        )
    }

    @Test
    fun extendClassTest() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.LiveData
                import androidx.lifecycle.MutableLiveData

                abstract class MyClass2 {
                    val firstLiveDataField : LiveData<Boolean>
                    val secondLiveDataField : LiveData<Boolean?>
                    val thirdLiveDataField : LiveData<Boolean?>
                    val fourLiveDataField : LiveData<List<Boolean>?>
                    val fiveLiveDataField : LiveData<List<Boolean>>
                }

                class MyClass1 : MyClass2() {
                    override val firstLiveDataField = MutableLiveData<Boolean>()
                    override val secondLiveDataField = MutableLiveData<Boolean?>()
                    override val thirdLiveDataField = MutableLiveData<Boolean?>(null)
                    override val fourLiveDataField = MutableLiveData<List<Boolean>?>(null)
                    override val fiveLiveDataField = MutableLiveData<List<Boolean>>()

                    fun foo() {
                        firstLiveDataField.value = false
                        firstLiveDataField.value = null
                        secondLiveDataField.value = null
                        secondLiveDataField.value = false
                        thirdLiveDataField.value = null
                        thirdLiveDataField.value = false
                        fourLiveDataField.value = null
                        fourLiveDataField.value = emptyList()
                        fiveLiveDataField.value = null
                        fiveLiveDataField.value = emptyList()
                    }
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/MyClass2.kt:23: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
        firstLiveDataField.value = null
                                   ~~~~
src/com/example/MyClass2.kt:30: Error: Cannot set non-nullable LiveData value to null [NullSafeMutableLiveData]
        fiveLiveDataField.value = null
                                  ~~~~
2 errors, 0 warnings
        """
        ).expectFixDiffs(
            """
Fix for src/com/example/MyClass2.kt line 23: Change `LiveData` type to nullable:
@@ -15 +15
-     override val firstLiveDataField = MutableLiveData<Boolean>()
+     override val firstLiveDataField = MutableLiveData<Boolean?>()
Fix for src/com/example/MyClass2.kt line 30: Change `LiveData` type to nullable:
@@ -19 +19
-     override val fiveLiveDataField = MutableLiveData<List<Boolean>>()
+     override val fiveLiveDataField = MutableLiveData<List<Boolean>?>()
        """
        )
    }

    @Test
    fun ifExpressionTest() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass2

                class MyClass1 {
                    val firstLiveDataField = MutableLiveData<MyClass2>()
                    val secondLiveDataField = MutableLiveData<MyClass2?>()

                    fun foo() {
                        var bar: MyClass2? = MyClass2() 
                        firstLiveDataField.value = if (true) MyClass2() else MyClass2()
                        firstLiveDataField.value = if (true) MyClass2() else null
                        firstLiveDataField.value = if (true) null else MyClass2()
                        firstLiveDataField.value = if (true) MyClass2() else bar
                        firstLiveDataField.value = if (true) {
                            MyClass2()
                        } else if (true) {
                            bar
                        } else {
                            MyClass2()
                        }
                        firstLiveDataField.value = if (true) {
                            MyClass2()
                        } else if (true) {
                            ""+""
                            MyClass2()
                        } else {
                            MyClass2()
                        }
                        firstLiveDataField.value = MyClass2()?.takeIf { false }
                        firstLiveDataField.value = MyClass2()?.takeIf { true }

                        secondLiveDataField.value = if (true) MyClass2() else MyClass2()
                        secondLiveDataField.value = if (true) MyClass2() else null
                        secondLiveDataField.value = if (true) MyClass2() else bar
                        secondLiveDataField.value = MyClass2()?.takeIf { false }
                        secondLiveDataField.value = MyClass2()?.takeIf { true }

                        var strings = listOf("foo")
                        firstLiveDataField.value = if (strings.contains("foo")) MyClass2() else MyClass2()
                    }
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/MyClass2.kt:14: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = if (true) MyClass2() else null
                                   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/com/example/MyClass2.kt:15: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = if (true) null else MyClass2()
                                   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/com/example/MyClass2.kt:16: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = if (true) MyClass2() else bar
                                   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/com/example/MyClass2.kt:17: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = if (true) {
                                   ^
src/com/example/MyClass2.kt:32: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = MyClass2()?.takeIf { false }
                                   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/com/example/MyClass2.kt:33: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = MyClass2()?.takeIf { true }
                                   ~~~~~~~~~~~~~~~~~~~~~~~~~~~
6 errors, 0 warnings
        """
        ).expectFixDiffs(
            """
Fix for src/com/example/MyClass2.kt line 14: Change `LiveData` type to nullable:
@@ -8 +8
-     val firstLiveDataField = MutableLiveData<MyClass2>()
+     val firstLiveDataField = MutableLiveData<MyClass2?>()
Fix for src/com/example/MyClass2.kt line 15: Change `LiveData` type to nullable:
@@ -8 +8
-     val firstLiveDataField = MutableLiveData<MyClass2>()
+     val firstLiveDataField = MutableLiveData<MyClass2?>()
Fix for src/com/example/MyClass2.kt line 16: Change `LiveData` type to nullable:
@@ -8 +8
-     val firstLiveDataField = MutableLiveData<MyClass2>()
+     val firstLiveDataField = MutableLiveData<MyClass2?>()
Fix for src/com/example/MyClass2.kt line 17: Change `LiveData` type to nullable:
@@ -8 +8
-     val firstLiveDataField = MutableLiveData<MyClass2>()
+     val firstLiveDataField = MutableLiveData<MyClass2?>()
Fix for src/com/example/MyClass2.kt line 32: Change `LiveData` type to nullable:
@@ -8 +8
-     val firstLiveDataField = MutableLiveData<MyClass2>()
+     val firstLiveDataField = MutableLiveData<MyClass2?>()
Fix for src/com/example/MyClass2.kt line 33: Change `LiveData` type to nullable:
@@ -8 +8
-     val firstLiveDataField = MutableLiveData<MyClass2>()
+     val firstLiveDataField = MutableLiveData<MyClass2?>()
        """
        )
    }

    @Test
    fun whenExpressionTest() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass2

                class MyClass1 {
                    val firstLiveDataField = MutableLiveData<MyClass2>()
                    val secondLiveDataField = MutableLiveData<MyClass2?>()

                    fun foo() {
                        var str: String? = "bar"
                        firstLiveDataField.value = when(str) {
                            "foo" -> MyClass2()
                            "bar" -> null
                            else -> MyClass2()
                        }
                        firstLiveDataField.postValue(
                            when(str) {
                                "foo" -> MyClass2()
                                "bar" -> null
                                else -> MyClass2()
                            }.apply { this }
                        )
                        firstLiveDataField.postValue(
                            when(str) {
                                "foo" -> MyClass2()
                                "bar" -> null
                                else -> MyClass2()
                            }.also { }
                        )
                        firstLiveDataField.value = when(str) {
                            "foo" -> MyClass2()
                            else -> MyClass2()
                        }

                        firstLiveDataField.value = when {
                            str?.substring(0) == "bar" -> MyClass2()
                            str?.substring(0) == "ar" -> MyClass2()
                            str?.substring(0) == "r" -> MyClass2()
                            else -> MyClass2()
                        }

                        secondLiveDataField.value = when(str) {
                            "foo" -> MyClass2()
                            "bar" -> null
                            else -> MyClass2()
                        }
                        secondLiveDataField.postValue(
                            when(str) {
                                "foo" -> MyClass2()
                                "bar" -> null
                                else -> MyClass2()
                            }.apply { this }
                        )
                        secondLiveDataField.value = when(str) {
                            "foo" -> MyClass2()
                            else -> MyClass2()
                        }
                    }
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/MyClass2.kt:13: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = when(str) {
                                   ^
src/com/example/MyClass2.kt:19: Error: Expected non-nullable value [NullSafeMutableLiveData]
            when(str) {
            ^
src/com/example/MyClass2.kt:26: Error: Expected non-nullable value [NullSafeMutableLiveData]
            when(str) {
            ^
3 errors, 0 warnings
        """
        )
    }

    @Test
    fun methodCallTest() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass2

                class MyClass1 {
                    val firstLiveDataField = MutableLiveData<MyClass2>()
                    val secondLiveDataField = MutableLiveData<MyClass2?>()

                    fun foo() {
                        firstLiveDataField.value = nullableMethod1()
                        firstLiveDataField.value = nullableMethod2()
                        firstLiveDataField.value = nonNullableMethod()

                        secondLiveDataField.value = nullableMethod1()
                        secondLiveDataField.value = nullableMethod2()
                        secondLiveDataField.value = nonNullableMethod()
                    }

                    fun nullableMethod1() : MyClass2? = null
                    fun nullableMethod2() = MyClass2().takeIf { false }
                    fun nonNullableMethod() = MyClass2()
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/MyClass2.kt:12: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = nullableMethod1()
                                   ~~~~~~~~~~~~~~~~~
src/com/example/MyClass2.kt:13: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = nullableMethod2()
                                   ~~~~~~~~~~~~~~~~~
2 errors, 0 warnings
        """
        ).expectFixDiffs(
            """
Fix for src/com/example/MyClass2.kt line 12: Change `LiveData` type to nullable:
@@ -8 +8
-     val firstLiveDataField = MutableLiveData<MyClass2>()
+     val firstLiveDataField = MutableLiveData<MyClass2?>()
Fix for src/com/example/MyClass2.kt line 12: Add non-null asserted (!!) call:
@@ -12 +12
-         firstLiveDataField.value = nullableMethod1()
+         firstLiveDataField.value = nullableMethod1()!!
Fix for src/com/example/MyClass2.kt line 13: Change `LiveData` type to nullable:
@@ -8 +8
-     val firstLiveDataField = MutableLiveData<MyClass2>()
+     val firstLiveDataField = MutableLiveData<MyClass2?>()
Fix for src/com/example/MyClass2.kt line 13: Add non-null asserted (!!) call:
@@ -13 +13
-         firstLiveDataField.value = nullableMethod2()
+         firstLiveDataField.value = nullableMethod2()!!
        """
        )
    }

    @Test
    fun javaLambdaTest() {
        check(
            java(
                """
                    package com.example;

                    public interface Consumer<T> {
                        void accept(T t) throws Exception;
                    }

            """
            ).indented(),
            java(
                """
                    package com.example;

                    public class ConsumerCaller<T> {
                        void bar(Consumer<T> consumer){
                        }
                    }

            """
            ).indented(),
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass2

                class MyClass1 {
                    val firstLiveDataField = MutableLiveData<MyClass2>()

                    fun foo() {
                        ConsumerCaller().bar {
                            firstLiveDataField.value = it
                        }
                    }
                }
            """
            ).indented()
        ).expectClean()
    }

    @Test
    fun tryCatchTest() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass1 {
                    val firstLiveDataField = MutableLiveData<Int>()

                    fun foo() {
                        firstLiveDataField.value = try {
                            Integer.parseInt("bar")
                        } catch (_: NumberFormatException) {
                            null
                        } catch (_: RuntimeException) {
                            0
                        } catch (_: Throwable) {
                            123
                        }
                        firstLiveDataField.value = try {
                            Integer.parseInt("bar")
                        } catch (_: RuntimeException) {
                            0
                        } catch (_: Throwable) {
                            123
                        }
                    }
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/MyClass1.kt:9: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = try {
                                   ^
1 errors, 0 warnings
            """.trimIndent()
        )
    }

    @Test
    fun elvisTest() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass1 {
                    val firstLiveDataField = MutableLiveData<String>()

                    fun foo() {
                        var nullStr: String? = null
                        var notNullStr: String? = null
                        firstLiveDataField.value = nullStr ?: ""
                        firstLiveDataField.value = nullStr ?: "" ?: null
                        firstLiveDataField.value = "" ?: ""
                        firstLiveDataField.value = nullStr ?: nullStr
                        firstLiveDataField.value = nullStr ?: null

                        firstLiveDataField.value = notNullStr ?: ""
                        firstLiveDataField.value = notNullStr ?: "" ?: null
                        firstLiveDataField.value = "" ?: ""
                        firstLiveDataField.value = notNullStr ?: nullStr
                        firstLiveDataField.value = notNullStr ?: null
                    }
                }
            """
            ).indented()
        ).expect(
            """
src/com/example/MyClass1.kt:12: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = nullStr ?: "" ?: null
                                   ~~~~~~~~~~~~~~~~~~~~~
src/com/example/MyClass1.kt:14: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = nullStr ?: nullStr
                                   ~~~~~~~~~~~~~~~~~~
src/com/example/MyClass1.kt:15: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = nullStr ?: null
                                   ~~~~~~~~~~~~~~~
src/com/example/MyClass1.kt:18: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = notNullStr ?: "" ?: null
                                   ~~~~~~~~~~~~~~~~~~~~~~~~
src/com/example/MyClass1.kt:20: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = notNullStr ?: nullStr
                                   ~~~~~~~~~~~~~~~~~~~~~
src/com/example/MyClass1.kt:21: Error: Expected non-nullable value [NullSafeMutableLiveData]
        firstLiveDataField.value = notNullStr ?: null
                                   ~~~~~~~~~~~~~~~~~~
6 errors, 0 warnings
            """.trimIndent()
        )
    }

    @Test
    fun constructorTest() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.MutableLiveData

                class MyClass1 {
                    val firstLiveDataField = MutableLiveData<Int>(null)
                    val thirdLiveDataField = MutableLiveData<Int?>(null)

                    fun foo() {
                        val secondLiveDataField = MutableLiveData<Int>(null)
                        val fourLiveDataField = MutableLiveData<Int?>(null)
                    }
                }
            """
            ).indented()
        ).expectClean()
    }

    @Test
    fun objectLiveData() {
        check(
            kotlin(
                """
                package com.example

                import androidx.lifecycle.LiveData

                val foo = object : LiveData<Int>() {
                    private fun bar() {
                        value = 0
                    }
                }

                val bar1 = object : LiveData<Int>() {
                    private fun bar() {
                        value = null
                    }
                }
            """
            ).indented()
        ).expectClean()
    }
}
