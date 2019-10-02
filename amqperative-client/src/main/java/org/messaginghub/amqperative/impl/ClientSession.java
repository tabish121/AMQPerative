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
package org.messaginghub.amqperative.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.qpid.proton4j.engine.Engine;
import org.messaginghub.amqperative.Client;
import org.messaginghub.amqperative.Connection;
import org.messaginghub.amqperative.Receiver;
import org.messaginghub.amqperative.ReceiverOptions;
import org.messaginghub.amqperative.Sender;
import org.messaginghub.amqperative.SenderOptions;
import org.messaginghub.amqperative.Session;
import org.messaginghub.amqperative.SessionOptions;
import org.messaginghub.amqperative.futures.AsyncResult;
import org.messaginghub.amqperative.futures.ClientFuture;
import org.messaginghub.amqperative.futures.ClientFutureFactory;
import org.messaginghub.amqperative.impl.exceptions.ClientExceptionSupport;
import org.messaginghub.amqperative.util.NoOpExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client implementation of the Session API.
 */
public class ClientSession implements Session {

    private static final Logger LOG = LoggerFactory.getLogger(ClientSession.class);

    private static final AtomicIntegerFieldUpdater<ClientSession> CLOSED_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ClientSession.class, "closed");

    private final ClientFuture<Session> openFuture;
    private final ClientFuture<Session> closeFuture;

    private final ClientSessionOptions options;
    private final ClientConnection connection;
    private final org.apache.qpid.proton4j.engine.Session session;
    private final ScheduledExecutorService serializer;
    private final String sessionId;
    private volatile int closed;
    private final AtomicInteger senderCounter = new AtomicInteger();
    private final AtomicInteger receiverCounter = new AtomicInteger();

    private volatile ThreadPoolExecutor deliveryExecutor;
    private final AtomicReference<Thread> deliveryThread = new AtomicReference<Thread>();
    private final AtomicReference<Throwable> failureCause = new AtomicReference<>();

    // TODO - Ensure closed resources are removed from these
    private final List<ClientSender> senders = new ArrayList<>();
    private final List<ClientReceiver> receivers = new ArrayList<>();

    public ClientSession(SessionOptions options, ClientConnection connection, org.apache.qpid.proton4j.engine.Session session) {
        this.options = new ClientSessionOptions(options);
        this.connection = connection;
        this.session = session;
        this.sessionId = connection.nextSessionId();
        this.serializer = connection.getScheduler();
        this.openFuture = connection.getFutureFactory().createFuture();
        this.closeFuture = connection.getFutureFactory().createFuture();
    }

    @Override
    public Client getClient() {
        return connection.getClient();
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public Future<Session> openFuture() {
        return openFuture;
    }

    @Override
    public Future<Session> close() {
        if (CLOSED_UPDATER.compareAndSet(this, 0, 1) && !openFuture.isFailed()) {
            serializer.execute(() -> {
                session.close();
            });
        }
        return closeFuture;
    }

    @Override
    public Receiver openReceiver(String address) throws ClientException {
        return openReceiver(address, options.getDefaultReceiverOptions());
    }

    @Override
    public Receiver openReceiver(String address, ReceiverOptions receiverOptions) throws ClientException {
        checkClosed();
        Objects.requireNonNull(address, "Cannot create a receiver with a null address");
        final ClientFuture<Receiver> createReceiver = getFutureFactory().createFuture();
        final ReceiverOptions receiverOpts = receiverOptions == null ? options.getDefaultReceiverOptions() : receiverOptions;

        serializer.execute(() -> {
            try {
                checkClosed();
                createReceiver.complete(internalCreateReceiver(address, receiverOpts).open());
            } catch (Throwable error) {
                createReceiver.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return connection.request(createReceiver, options.getRequestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Receiver openDynamicReceiver() throws ClientException {
        return openDynamicReceiver(options.getDefaultDynamicReceiverOptions());
    }

    @Override
    public Receiver openDynamicReceiver(ReceiverOptions receiverOptions) throws ClientException {
        checkClosed();

        if (!receiverOptions.isDynamic()) {
            throw new IllegalArgumentException("Dynamic receiver requires the options configured for dynamic.");
        }

        final ClientFuture<Receiver> createReceiver = getFutureFactory().createFuture();
        final ReceiverOptions receiverOpts = receiverOptions == null ? options.getDefaultReceiverOptions() : receiverOptions;

        serializer.execute(() -> {
            try {
                checkClosed();
                createReceiver.complete(internalCreateReceiver(null, receiverOpts).open());
            } catch (Throwable error) {
                createReceiver.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return connection.request(createReceiver, options.getRequestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Sender openSender(String address) throws ClientException {
        return openSender(address, options.getDefaultSenderOptions());
    }

    @Override
    public Sender openSender(String address, SenderOptions senderOptions) throws ClientException {
        checkClosed();
        Objects.requireNonNull(address, "Cannot create a sender with a null address");
        final ClientFuture<Sender> createSender = getFutureFactory().createFuture();
        final SenderOptions senderOpts = senderOptions == null ? options.getDefaultSenderOptions() : senderOptions;

        serializer.execute(() -> {
            try {
                checkClosed();
                createSender.complete(internalCreateSender(address, senderOpts).open());
            } catch (Throwable error) {
                createSender.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return connection.request(createSender, options.getRequestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Sender openAnonymousSender() throws ClientException {
        return openAnonymousSender(options.getDefaultSenderOptions());
    }

    @Override
    public Sender openAnonymousSender(SenderOptions senderOptions) throws ClientException {
        checkClosed();
        final ClientFuture<Sender> createSender = getFutureFactory().createFuture();
        final SenderOptions senderOpts = senderOptions == null ? options.getDefaultSenderOptions() : senderOptions;

        serializer.execute(() -> {
            try {
                checkClosed();
                createSender.complete(internalCreateSender(null, senderOpts).open());
            } catch (Throwable error) {
                createSender.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return connection.request(createSender, options.getRequestTimeout(), TimeUnit.MILLISECONDS);
    }

    //----- Internal API accessible for use within the package

    ClientReceiver internalCreateReceiver(String address, ReceiverOptions options) {
        ClientReceiver result = new ClientReceiver(options, this, address);
        receivers.add(result);
        return result;
    }

    ClientSender internalCreateSender(String address, SenderOptions options) {
        ClientSender result = new ClientSender(options, this, address);
        senders.add(result);
        return result;
    }

    ClientSession open() {
        serializer.execute(() -> {
            session.openHandler(result -> {
                openFuture.complete(this);
                LOG.trace("Connection session opened successfully");
            });
            session.closeHandler(result -> {
                if (result.getRemoteCondition() != null) {
                    // TODO - Process as failure cause if none set
                }
                CLOSED_UPDATER.lazySet(this, 1);
                closeFuture.complete(this);
            });

            options.configureSession(session).open();
        });

        return this;
    }

    ScheduledExecutorService getScheduler() {
        return serializer;
    }

    Engine getEngine() {
        return connection.getEngine();
    }

    ClientFutureFactory getFutureFactory() {
        return connection.getFutureFactory();
    }

    Executor getDeliveryExecutor() {
        ThreadPoolExecutor exec = deliveryExecutor;
        if (exec == null) {
            synchronized (options) {
                if (deliveryExecutor == null) {
                    if (!isClosed()) {
                        deliveryExecutor = exec = createExecutor("delivery dispatcher", deliveryThread);
                    } else {
                        return NoOpExecutor.INSTANCE;
                    }
                } else {
                    exec = deliveryExecutor;
                }
            }
        }

        return exec;
    }

    void setFailureCause(Throwable failureCause) {
        this.failureCause.set(failureCause);
    }

    Throwable getFailureCause() {
        return failureCause.get();
    }

    String nextReceiverId() {
        return sessionId + ":" + receiverCounter.incrementAndGet();
    }

    String nextSenderId() {
        return sessionId + ":" + senderCounter.incrementAndGet();
    }

    boolean isClosed() {
        return closed > 0;
    }

    ScheduledFuture<?> scheduleRequestTimeout(final AsyncResult<?> request, long timeout, final ClientException error) {
        return connection.scheduleRequestTimeout(request, timeout, error);
    }

    ScheduledFuture<?> scheduleRequestTimeout(final AsyncResult<?> request, long timeout, Supplier<ClientException> errorSupplier) {
        return connection.scheduleRequestTimeout(request, timeout, errorSupplier);
    }

    <T> T request(ClientFuture<T> request, long timeout, TimeUnit units) throws ClientException {
        return connection.request(request, timeout, units);
    }

    org.apache.qpid.proton4j.engine.Session getProtonSession() {
        return session;
    }

    //----- Private implementation methods

    private void checkClosed() throws IllegalStateException {
        if (isClosed()) {
            IllegalStateException error = null;

            if (failureCause.get() == null) {
                error = new IllegalStateException("The Session is closed");
            } else {
                error = new IllegalStateException("The Session was closed due to an unrecoverable error.");
                error.initCause(failureCause.get());
            }

            throw error;
        }
    }

    private ThreadPoolExecutor createExecutor(final String threadNameSuffix, AtomicReference<Thread> threadTracker) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<Runnable>(),
            new ClientThreadFactory("ClientSession ["+ sessionId + "] " + threadNameSuffix, true, threadTracker));

        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy() {

            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                // Completely ignore the task if the session has closed.
                if (!isClosed()) {
                    LOG.trace("Task {} rejected from executor: {}", r, e);
                    super.rejectedExecution(r, e);
                }
            }
        });

        return executor;
    }
}