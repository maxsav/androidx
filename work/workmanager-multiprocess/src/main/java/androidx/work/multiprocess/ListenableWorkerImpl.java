/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.work.multiprocess;

import static androidx.work.multiprocess.ListenableCallback.ListenableCallbackRunnable.failureCallback;
import static androidx.work.multiprocess.ListenableCallback.ListenableCallbackRunnable.successCallback;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.work.Configuration;
import androidx.work.ListenableWorker;
import androidx.work.Logger;
import androidx.work.WorkerParameters;
import androidx.work.impl.WorkManagerImpl;
import androidx.work.impl.utils.futures.SettableFuture;
import androidx.work.impl.utils.taskexecutor.TaskExecutor;
import androidx.work.multiprocess.parcelable.ParcelConverters;
import androidx.work.multiprocess.parcelable.ParcelableRemoteWorkRequest;
import androidx.work.multiprocess.parcelable.ParcelableResult;
import androidx.work.multiprocess.parcelable.ParcelableWorkerParameters;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 * An implementation of ListenableWorker that can be executed in a remote process.
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class ListenableWorkerImpl extends IListenableWorkerImpl.Stub {
    // Synthetic access
    static final String TAG = Logger.tagWithPrefix("ListenableWorkerImpl");
    // Synthetic access
    static byte[] sEMPTY = new byte[0];
    // Synthetic access
    static final Object sLock = new Object();

    // Synthetic access
    final Context mContext;
    // Synthetic access
    final WorkManagerImpl mWorkManager;
    // Synthetic access
    final Configuration mConfiguration;
    // Synthetic access
    final TaskExecutor mTaskExecutor;
    // Synthetic access
    final Map<String, ListenableFuture<ListenableWorker.Result>> mFutureMap;

    ListenableWorkerImpl(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mWorkManager = WorkManagerImpl.getInstance(mContext);
        mConfiguration = mWorkManager.getConfiguration();
        mTaskExecutor = mWorkManager.getWorkTaskExecutor();
        mFutureMap = new HashMap<>();
    }

    @Override
    public void startWork(
            @NonNull final byte[] request,
            @NonNull final IWorkManagerImplCallback callback) {
        try {
            ParcelableRemoteWorkRequest parcelableRemoteWorkRequest =
                    ParcelConverters.unmarshall(request, ParcelableRemoteWorkRequest.CREATOR);

            ParcelableWorkerParameters parcelableWorkerParameters =
                    parcelableRemoteWorkRequest.getParcelableWorkerParameters();

            WorkerParameters workerParameters =
                    parcelableWorkerParameters.toWorkerParameters(mWorkManager);

            final String id = workerParameters.getId().toString();
            final String workerClassName = parcelableRemoteWorkRequest.getWorkerClassName();

            Logger.get().debug(TAG,
                    String.format("Executing work request (%s, %s)", id, workerClassName));

            final ListenableFuture<ListenableWorker.Result> futureResult =
                    executeWorkRequest(id, workerClassName, workerParameters);

            futureResult.addListener(new Runnable() {
                @Override
                public void run() {
                    try {
                        ListenableWorker.Result result = futureResult.get();
                        ParcelableResult parcelableResult = new ParcelableResult(result);
                        byte[] response = ParcelConverters.marshall(parcelableResult);
                        successCallback(callback, response);
                    } catch (ExecutionException | InterruptedException exception) {
                        failureCallback(callback, exception);
                    } catch (CancellationException cancellationException) {
                        Logger.get().debug(TAG, String.format("Worker (%s) was cancelled", id));
                        failureCallback(callback, cancellationException);
                    } finally {
                        synchronized (sLock) {
                            mFutureMap.remove(id);
                        }
                    }
                }
            }, mTaskExecutor.getBackgroundExecutor());
        } catch (Throwable throwable) {
            failureCallback(callback, throwable);
        }
    }

    @Override
    public void interrupt(
            @NonNull final byte[] request,
            @NonNull final IWorkManagerImplCallback callback) {
        try {
            ParcelableWorkerParameters parcelableWorkerParameters =
                    ParcelConverters.unmarshall(request, ParcelableWorkerParameters.CREATOR);
            final String id = parcelableWorkerParameters.getId().toString();
            Logger.get().debug(TAG, String.format("Interrupting work with id (%s)", id));

            final ListenableFuture<ListenableWorker.Result> future;
            synchronized (sLock) {
                future = mFutureMap.remove(id);
            }
            if (future != null) {
                mWorkManager.getWorkTaskExecutor().getBackgroundExecutor()
                        .execute(new Runnable() {
                            @Override
                            public void run() {
                                future.cancel(true);
                                successCallback(callback, sEMPTY);
                            }
                        });
            } else {
                // Nothing to do.
                successCallback(callback, sEMPTY);
            }
        } catch (Throwable throwable) {
            failureCallback(callback, throwable);
        }
    }

    @NonNull
    private ListenableFuture<ListenableWorker.Result> executeWorkRequest(
            @NonNull String id,
            @NonNull String workerClassName,
            @NonNull WorkerParameters workerParameters) {

        final SettableFuture<ListenableWorker.Result> future = SettableFuture.create();

        Logger.get().debug(TAG,
                String.format("Tracking execution of %s (%s)", id, workerClassName));

        synchronized (sLock) {
            mFutureMap.put(id, future);
        }

        ListenableWorker worker = mConfiguration.getWorkerFactory()
                .createWorkerWithDefaultFallback(mContext, workerClassName, workerParameters);

        if (worker == null) {
            String message = String.format(
                    "Unable to create an instance of %s", workerClassName);
            Logger.get().error(TAG, message);
            future.setException(new IllegalStateException(message));
            return future;
        }

        if (!(worker instanceof RemoteListenableWorker)) {
            String message = String.format(
                    "%s does not extend %s",
                    workerClassName,
                    RemoteListenableWorker.class.getName()
            );
            Logger.get().error(TAG, message);
            future.setException(new IllegalStateException(message));
            return future;
        }

        try {
            RemoteListenableWorker remoteListenableWorker = (RemoteListenableWorker) worker;
            future.setFuture(remoteListenableWorker.startRemoteWork());
        } catch (Throwable throwable) {
            future.setException(throwable);
        }

        return future;
    }
}
