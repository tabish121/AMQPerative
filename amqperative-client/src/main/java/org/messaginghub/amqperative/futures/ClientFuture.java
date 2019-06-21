/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.messaginghub.amqperative.futures;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.messaginghub.amqperative.util.IOExceptionSupport;

/**
 * Asynchronous Client Future class.
 */
public abstract class ClientFuture implements AsyncResult {

    protected final ClientSynchronization synchronization;

    // States used to track progress of this future
    protected static final int INCOMPLETE = 0;
    protected static final int COMPLETING = 1;
    protected static final int SUCCESS = 2;
    protected static final int FAILURE = 3;

    protected static final AtomicIntegerFieldUpdater<ClientFuture> STATE_FIELD_UPDATER =
        AtomicIntegerFieldUpdater.newUpdater(ClientFuture.class,"state");

    private volatile int state = INCOMPLETE;
    protected Throwable error;
    protected int waiting;

    public ClientFuture() {
        this(null);
    }

    public ClientFuture(ClientSynchronization synchronization) {
        this.synchronization = synchronization;
    }

    @Override
    public boolean isComplete() {
        return state > COMPLETING;
    }

    @Override
    public void onFailure(Throwable result) {
        if (STATE_FIELD_UPDATER.compareAndSet(this, INCOMPLETE, COMPLETING)) {
            error = result;
            if (synchronization != null) {
                synchronization.onPendingFailure(error);
            }

            STATE_FIELD_UPDATER.lazySet(this, FAILURE);

            synchronized(this) {
                if (waiting > 0) {
                    notifyAll();
                }
            }
        }
    }

    @Override
    public void onSuccess() {
        if (STATE_FIELD_UPDATER.compareAndSet(this, INCOMPLETE, COMPLETING)) {
            if (synchronization != null) {
                synchronization.onPendingSuccess();
            }

            STATE_FIELD_UPDATER.lazySet(this, SUCCESS);

            synchronized(this) {
                if (waiting > 0) {
                    notifyAll();
                }
            }
        }
    }

    /**
     * Waits for a response to some requested operation.
     *
     * @throws IOException if an error occurs while waiting for the response.
     */
    public abstract void sync() throws IOException;

    /**
     * Timed wait for a response to an operation.
     *
     * @param amount
     *        The amount of time to wait before abandoning the wait.
     * @param unit
     *        The unit to use for this wait period.
     *
     * @return true if the operation succeeded and false if the waiting time elapsed while
     * 	       waiting for the operation to complete.
     *
     * @throws IOException if an error occurs while waiting for the response.
     */
    public abstract boolean sync(long amount, TimeUnit unit) throws IOException;

    protected void failOnError() throws IOException {
        Throwable cause = error;
        if (cause != null) {
            throw IOExceptionSupport.create(cause);
        }
    }
}
