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
package org.apache.qpid.protonj2.client.futures;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A more balanced implementation of a ClientFuture that works better on some
 * platforms such as windows where the thread park and atomic operations used by
 * a more aggressive implementation could result in poor performance.
 *
 * @param <V> The type that result from completion of this Future
 */
public class BalancedClientFuture<V> extends ClientFuture<V> {

    // Using a progressive wait strategy helps to avoid wait happening before
    // completion and avoid using expensive thread signaling
    private static final int SPIN_COUNT = 10;
    private static final int YIELD_COUNT = 100;

    public BalancedClientFuture() {
        this(null);
    }

    public BalancedClientFuture(ClientSynchronization synchronization) {
        super(synchronization);
    }

    @Override
    public V get(long amount, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        try {
            if (isComplete() || amount == 0) {
                failOnError();
                return getResult();
            }

            final long timeout = unit.toNanos(amount);
            long maxParkNanos = timeout / 8;
            maxParkNanos = maxParkNanos > 0 ? maxParkNanos : timeout;
            final long startTime = System.nanoTime();
            int idleCount = 0;

            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            while (true) {
                final long elapsed = System.nanoTime() - startTime;
                final long diff = elapsed - timeout;

                if (diff >= 0) {
                    failOnError();
                    if (isComplete()) {
                        return getResult();
                    }
                    throw new TimeoutException("Timed out waiting for completion");
                }

                if (isComplete()) {
                    failOnError();
                    return getResult();
                }

                if (idleCount < SPIN_COUNT) {
                    idleCount++;
                } else if (idleCount < YIELD_COUNT) {
                    Thread.yield();
                    idleCount++;
                } else {
                    synchronized (this) {
                        if (isComplete()) {
                            failOnError();
                            return getResult();
                        }

                        waiting++;
                        try {
                            wait(-diff / 1000000, (int) (-diff % 1000000));
                        } finally {
                            waiting--;
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.interrupted();
            throw e;
        }
    }

    @Override
    public V get() throws InterruptedException, ExecutionException {
        try {
            if (isComplete()) {
                failOnError();
                return getResult();
            }

            int idleCount = 0;

            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            while (true) {
                if (isComplete()) {
                    failOnError();
                    return getResult();
                }

                if (idleCount < SPIN_COUNT) {
                    idleCount++;
                } else if (idleCount < YIELD_COUNT) {
                    Thread.yield();
                    idleCount++;
                } else {
                    synchronized (this) {
                        if (isComplete()) {
                            failOnError();
                            return getResult();
                        }

                        waiting++;
                        try {
                            wait();
                        } finally {
                            waiting--;
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.interrupted();
            throw e;
        }
    }
}
