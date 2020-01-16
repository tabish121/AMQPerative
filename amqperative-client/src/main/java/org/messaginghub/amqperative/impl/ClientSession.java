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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.qpid.proton4j.engine.Engine;
import org.apache.qpid.proton4j.engine.SessionState;
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
import org.messaginghub.amqperative.impl.exceptions.ClientOperationTimedOutException;
import org.messaginghub.amqperative.impl.exceptions.ClientResourceClosedException;
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

    private volatile int closed;
    private ClientException failureCause;

    private final ClientFuture<Session> openFuture;
    private final ClientFuture<Session> closeFuture;

    private final SessionOptions options;
    private final ClientConnection connection;
    private final org.apache.qpid.proton4j.engine.Session protonSession;
    private final ScheduledExecutorService serializer;
    private final String sessionId;
    private final ClientSenderBuilder senderBuilder;
    private final ClientReceiverBuilder receiverBuilder;

    private volatile ThreadPoolExecutor deliveryExecutor;
    private final AtomicReference<Thread> deliveryThread = new AtomicReference<Thread>();

    // TODO - Ensure closed resources are removed from these or find a way to use what the proton session knows
    //        about sender and receiver links.
    private final List<ClientSender> senders = new ArrayList<>();
    private final List<ClientReceiver> receivers = new ArrayList<>();

    public ClientSession(SessionOptions options, ClientConnection connection, org.apache.qpid.proton4j.engine.Session session) {
        this.options = new SessionOptions(options);
        this.connection = connection;
        this.protonSession = session;
        this.sessionId = connection.nextSessionId();
        this.serializer = connection.getScheduler();
        this.openFuture = connection.getFutureFactory().createFuture();
        this.closeFuture = connection.getFutureFactory().createFuture();
        this.senderBuilder = new ClientSenderBuilder(this);
        this.receiverBuilder = new ClientReceiverBuilder(this);

        configureSession();
    }

    @Override
    public ClientInstance client() {
        return connection.client();
    }

    @Override
    public ClientConnection connection() {
        return connection;
    }

    @Override
    public Future<Session> openFuture() {
        return openFuture;
    }

    @Override
    public Future<Session> close() {
        if (CLOSED_UPDATER.compareAndSet(this, 0, 1)) {
            serializer.execute(() -> {
                try {
                    protonSession.close();
                } catch (Throwable error) {
                    connection.handleClientIOException(error);
                }
            });
        }
        return closeFuture;
    }

    @Override
    public Receiver openReceiver(String address) throws ClientException {
        return openReceiver(address, null);
    }

    @Override
    public Receiver openReceiver(String address, ReceiverOptions receiverOptions) throws ClientException {
        checkClosed();
        Objects.requireNonNull(address, "Cannot create a receiver with a null address");
        final ClientFuture<Receiver> createReceiver = getFutureFactory().createFuture();

        serializer.execute(() -> {
            try {
                checkClosed();
                createReceiver.complete(internalOpenReceiver(address, receiverOptions));
            } catch (Throwable error) {
                createReceiver.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return connection.request(createReceiver, options.requestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Receiver openDynamicReceiver() throws ClientException {
        return openDynamicReceiver(null, null);
    }

    @Override
    public Receiver openDynamicReceiver(Map<String, Object> dynamicNodeProperties) throws ClientException {
        return openDynamicReceiver(null, null);
    }

    @Override
    public Receiver openDynamicReceiver(ReceiverOptions receiverOptions) throws ClientException {
        return openDynamicReceiver(null, null);
    }

    @Override
    public Receiver openDynamicReceiver(Map<String, Object> dynamicNodeProperties, ReceiverOptions receiverOptions) throws ClientException {
        checkClosed();
        final ClientFuture<Receiver> createReceiver = getFutureFactory().createFuture();

        serializer.execute(() -> {
            try {
                checkClosed();
                createReceiver.complete(internalOpenDynamicReceiver(dynamicNodeProperties, receiverOptions));
            } catch (Throwable error) {
                createReceiver.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return connection.request(createReceiver, options.requestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Sender openSender(String address) throws ClientException {
        return openSender(address, null);
    }

    @Override
    public Sender openSender(String address, SenderOptions senderOptions) throws ClientException {
        checkClosed();
        Objects.requireNonNull(address, "Cannot create a sender with a null address");
        final ClientFuture<Sender> createSender = getFutureFactory().createFuture();

        serializer.execute(() -> {
            try {
                checkClosed();
                createSender.complete(internalOpenSender(address, senderOptions));
            } catch (Throwable error) {
                createSender.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return connection.request(createSender, options.requestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Sender openAnonymousSender() throws ClientException {
        return openAnonymousSender(null);
    }

    @Override
    public Sender openAnonymousSender(SenderOptions senderOptions) throws ClientException {
        checkClosed();
        final ClientFuture<Sender> createSender = getFutureFactory().createFuture();

        serializer.execute(() -> {
            try {
                checkClosed();
                createSender.complete(internalOpenAnonymousSender(senderOptions));
            } catch (Throwable error) {
                createSender.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return connection.request(createSender, options.requestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Map<String, Object> properties() throws ClientException {
        waitForOpenToComplete();
        return ClientConversionSupport.toStringKeyedMap(protonSession.getRemoteProperties());
    }

    @Override
    public String[] offeredCapabilities() throws ClientException {
        waitForOpenToComplete();
        return ClientConversionSupport.toStringArray(protonSession.getRemoteOfferedCapabilities());
    }

    @Override
    public String[] desiredCapabilities() throws ClientException {
        waitForOpenToComplete();
        return ClientConversionSupport.toStringArray(protonSession.getRemoteDesiredCapabilities());
    }

    //----- Internal resource open APIs expected to be called from the connection event loop

    ClientReceiver internalOpenReceiver(String address, ReceiverOptions receiverOptions) throws ClientException {
        return register(receiverBuilder.receiver(address, receiverOptions).open());
    }

    ClientReceiver internalOpenDynamicReceiver(Map<String, Object> dynamicNodeProperties, ReceiverOptions receiverOptions) throws ClientException {
        return register(receiverBuilder.dynamicReceiver(dynamicNodeProperties, receiverOptions).open());
    }

    ClientSender internalOpenSender(String address, SenderOptions senderOptions) throws ClientException {
        return register(senderBuilder.sender(address, senderOptions).open());
    }

    ClientSender internalOpenAnonymousSender(SenderOptions senderOptions) throws ClientException {
        // When the connection is opened we are ok to check that the anonymous relay is supported
        // and open the sender if so, otherwise we need to wait.
        if (connection.openFuture().isDone()) {
            connection.checkAnonymousRelaySupported();
            return register(senderBuilder.anonymousSender(senderOptions).open());
        } else {
            return register(senderBuilder.anonymousSender(senderOptions));
        }
    }

    //----- Internal API accessible for use within the package

    ClientSession open() {
        protonSession.localOpenHandler(session -> handleLocalOpen(session))
                     .localCloseHandler(session -> handleLocalClose(session))
                     .openHandler(session -> handleRemoteOpen(session))
                     .closeHandler(session -> handleRemoteClose(session));

        try {
            protonSession.open();
        } catch (Throwable error) {
            connection.handleClientIOException(ClientExceptionSupport.createOrPassthroughFatal(error));
        }

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

    ClientException getFailureCause() {
        return failureCause;
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

    String id() {
        return sessionId;
    }

    SessionOptions options() {
        return options;
    }

    org.apache.qpid.proton4j.engine.Session getProtonSession() {
        return protonSession;
    }

    //----- Private implementation methods

    private <T extends ClientSender> T register(T sender) {
        senders.add(sender);
        return sender;
    }

    private <T extends ClientReceiver> T register(T receiver) {
        receivers.add(receiver);
        return receiver;
    }

    private void configureSession() {
        protonSession.setOfferedCapabilities(ClientConversionSupport.toSymbolArray(options.offeredCapabilities()));
        protonSession.setDesiredCapabilities(ClientConversionSupport.toSymbolArray(options.desiredCapabilities()));
        protonSession.setProperties(ClientConversionSupport.toSymbolKeyedMap(options.properties()));
    }

    private void checkClosed() throws IllegalStateException {
        if (isClosed()) {
            IllegalStateException error = null;

            if (failureCause == null) {
                error = new IllegalStateException("The Session is closed");
            } else {
                error = new IllegalStateException("The Session was closed due to an unrecoverable error.");
                error.initCause(failureCause);
            }

            throw error;
        }
    }

    private void waitForOpenToComplete() throws ClientException {
        if (!openFuture.isComplete() || openFuture.isFailed()) {
            try {
                openFuture.get();
            } catch (ExecutionException | InterruptedException e) {
                Thread.interrupted();
                if (failureCause != null) {
                    throw failureCause;
                } else {
                    throw ClientExceptionSupport.createNonFatalOrPassthrough(e.getCause());
                }
            }
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

    private void handleLocalOpen(org.apache.qpid.proton4j.engine.Session session) {
        if (options.openTimeout() > 0) {
            serializer.schedule(() -> {
                if (!openFuture.isDone()) {
                    if (failureCause == null) {
                        failureCause = new ClientOperationTimedOutException("Session open timed out waiting for remote to respond");
                    }

                    openFuture.failed(failureCause);

                    if (protonSession.isLocallyClosed()) {
                        // We didn't hear back from open and session was since closed so just fail
                        // the close as we don't want to doubly wait for something that can't come.
                        closeFuture.failed(failureCause);
                    } else {
                        try {
                            protonSession.close();
                        } catch (Throwable error) {
                            connection.handleClientIOException(ClientExceptionSupport.createOrPassthroughFatal(error));
                        }
                    }
                }
            }, options.openTimeout(), TimeUnit.MILLISECONDS);
        }
    }

    private void handleLocalClose(org.apache.qpid.proton4j.engine.Session session) {
        CLOSED_UPDATER.lazySet(this, 1);

        if (failureCause == null) {
            failureCause = connection.getFailureCause();
        }

        // TODO - Need handling support for engine shutdown or failed at connection level
        //        to trigger failure of any open / close futures waiting for responses
        //        after the resource has been locally closed.

        // If not yet remotely closed we only wait for a remote close if the connection isn't
        // already failed and we have successfully opened the session without a timeout.
        if (!connection.isClosed() && !openFuture.isFailed() && !session.isRemotelyClosed()) {
            final long timeout = options.closeTimeout() >= 0 ?
                    options.closeTimeout() : options.requestTimeout();

            if (timeout > 0) {
                connection.scheduleRequestTimeout(closeFuture, timeout, () ->
                    new ClientOperationTimedOutException("Session close timed out waiting for remote to respond"));
            }
        } else {
            if (failureCause != null) {
                openFuture.failed(failureCause);
            } else {
                openFuture.complete(this);
            }

            closeFuture.complete(this);
        }
    }

    private void handleRemoteOpen(org.apache.qpid.proton4j.engine.Session session) {
        openFuture.complete(this);
        LOG.trace("Session:{} opened successfully.", id());

        senders.forEach(sender -> {
            if (sender.isAnonymous()) {
                if (connection.getCapabilities().anonymousRelaySupported()) {
                    sender.open();
                } else {
                    sender.handleAnonymousRelayNotSupported();
                }
            }
        });
    }

    private void handleRemoteClose(org.apache.qpid.proton4j.engine.Session session) {
        if (session.getState() != SessionState.CLOSED) {
            final ClientException error;
            if (session.getRemoteCondition() != null) {
                error = ClientErrorSupport.convertToNonFatalException(session.getRemoteCondition());
            } else {
                error = new ClientResourceClosedException("Session remotely closed without explanation");
            }

            if (failureCause != null) {
                failureCause = error;
            }

            // TODO - If Senders are linked into local open / close events
            //        we could just close them locally after the session is closed.
            senders.forEach(sender -> {
                if (sender.isAnonymous() && !sender.openFuture().isDone()) {
                    sender.handleSessionRemotelyClosedBeforeSenderOpened();
                }
            });

            try {
                session.close();
            } catch (Throwable ignore) {
                LOG.trace("Error ignored from call to close session after remote close.", ignore);
            }
        } else {
            if (failureCause != null) {
                openFuture.failed(failureCause);
            } else {
                openFuture.complete(this);
            }

            closeFuture.complete(this);
        }
    }
}
