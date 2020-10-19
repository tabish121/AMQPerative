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
package org.apache.qpid.protonj2.client.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.qpid.protonj2.client.Client;
import org.apache.qpid.protonj2.client.Connection;
import org.apache.qpid.protonj2.client.ConnectionOptions;
import org.apache.qpid.protonj2.client.DeliveryState;
import org.apache.qpid.protonj2.client.Message;
import org.apache.qpid.protonj2.client.Receiver;
import org.apache.qpid.protonj2.client.ReceiverOptions;
import org.apache.qpid.protonj2.client.Sender;
import org.apache.qpid.protonj2.client.Session;
import org.apache.qpid.protonj2.client.Tracker;
import org.apache.qpid.protonj2.client.exceptions.ClientConnectionRemotelyClosedException;
import org.apache.qpid.protonj2.client.exceptions.ClientConnectionSecurityException;
import org.apache.qpid.protonj2.client.exceptions.ClientConnectionSecuritySaslException;
import org.apache.qpid.protonj2.client.exceptions.ClientException;
import org.apache.qpid.protonj2.client.test.ImperativeClientTestCase;
import org.apache.qpid.protonj2.client.util.StopWatch;
import org.apache.qpid.protonj2.test.driver.netty.NettyTestPeer;
import org.apache.qpid.protonj2.types.security.SaslCode;
import org.apache.qpid.protonj2.types.transport.AmqpError;
import org.apache.qpid.protonj2.types.transport.ConnectionError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test client implementation of connection recovery.
 */
@Timeout(20)
class ReconnectTest extends ImperativeClientTestCase {

    @Test
    public void testConnectionNotifiesReconnectionLifecycleEvents() throws Exception {
        try (NettyTestPeer firstPeer = new NettyTestPeer();
             NettyTestPeer finalPeer = new NettyTestPeer()) {

            firstPeer.expectSASLAnonymousConnect();
            firstPeer.expectOpen().respond();
            firstPeer.dropAfterLastHandler(5);
            firstPeer.start();

            finalPeer.expectSASLAnonymousConnect();
            finalPeer.expectOpen().respond();
            finalPeer.start();

            final URI primaryURI = firstPeer.getServerURI();
            final URI backupURI = finalPeer.getServerURI();

            final CountDownLatch connected = new CountDownLatch(1);
            final CountDownLatch disconnected = new CountDownLatch(1);
            final CountDownLatch reconnected = new CountDownLatch(1);
            final CountDownLatch failed = new CountDownLatch(1);

            ConnectionOptions options = new ConnectionOptions();
            options.reconnectOptions().reconnectEnabled(true);
            options.reconnectOptions().maxReconnectAttempts(5);
            options.reconnectOptions().reconnectDelay(10);
            options.reconnectOptions().useReconnectBackOff(false);
            options.reconnectOptions().addReconnectHost(backupURI.getHost(), backupURI.getPort());
            options.connectedHandler((connection, context) -> {
                connected.countDown();
            });
            options.interruptedHandler((connection, context) -> {
                disconnected.countDown();
            });
            options.reconnectedHandler((connection, context) -> {
                reconnected.countDown();
            });
            options.failedHandler((connection, context) -> {
                failed.countDown();
            });

            Client container = Client.create();
            Connection connection = container.connect(primaryURI.getHost(), primaryURI.getPort(), options);

            firstPeer.waitForScriptToComplete();

            connection.openFuture().get();

            finalPeer.waitForScriptToComplete();
            finalPeer.expectBegin().respond();
            finalPeer.expectEnd().respond();
            finalPeer.dropAfterLastHandler(10);

            Session session = connection.openSession().openFuture().get();

            session.close();

            finalPeer.waitForScriptToComplete();

            assertTrue(connected.await(5, TimeUnit.SECONDS));
            assertTrue(disconnected.await(5, TimeUnit.SECONDS));
            assertTrue(reconnected.await(5, TimeUnit.SECONDS));
            assertTrue(failed.await(5, TimeUnit.SECONDS));

            connection.close();

            finalPeer.waitForScriptToComplete();
        }
    }

    @Test
    public void testConnectThrowsSecurityViolationOnFailureSaslAuth() throws Exception {
        doTestConnectThrowsSecurityViolationOnFailuredSaslExchange(SaslCode.AUTH.byteValue());
    }

    @Test
    public void testConnectThrowsSecurityViolationOnFailureSaslSys() throws Exception {
        doTestConnectThrowsSecurityViolationOnFailuredSaslExchange(SaslCode.SYS.byteValue());
    }

    @Test
    public void testConnectThrowsSecurityViolationOnFailureSaslSysPerm() throws Exception {
        doTestConnectThrowsSecurityViolationOnFailuredSaslExchange(SaslCode.SYS_PERM.byteValue());
    }

    private void doTestConnectThrowsSecurityViolationOnFailuredSaslExchange(byte saslCode) throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectFailingSASLPlainConnect(saslCode);
            peer.dropAfterLastHandler(10);
            peer.start();

            ConnectionOptions options = new ConnectionOptions();
            options.reconnectOptions().reconnectEnabled(true);
            options.user("test");
            options.password("pass");

            URI remoteURI = peer.getServerURI();

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort(), options);

            try {
                connection.openFuture().get();
            } catch (ExecutionException exe) {
                assertTrue(exe.getCause() instanceof ClientConnectionSecuritySaslException);
            }

            connection.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testReconnectStopsAfterSaslAuthFailure() throws Exception {
        testReconnectStopsAfterSaslPermFailure(SaslCode.AUTH.byteValue());
    }

    @Test
    public void testReconnectStopsAfterSaslSysFailure() throws Exception {
        testReconnectStopsAfterSaslPermFailure(SaslCode.SYS.byteValue());
    }

    @Test
    public void testReconnectStopsAfterSaslPermFailure() throws Exception {
        testReconnectStopsAfterSaslPermFailure(SaslCode.SYS_PERM.byteValue());
    }

    private void testReconnectStopsAfterSaslPermFailure(byte saslCode) throws Exception {
        try (NettyTestPeer firstPeer = new NettyTestPeer();
             NettyTestPeer secondPeer = new NettyTestPeer();
             NettyTestPeer thirdPeer = new NettyTestPeer()) {

            firstPeer.expectSASLAnonymousConnect();
            firstPeer.expectOpen().respond();
            firstPeer.dropAfterLastHandler();
            firstPeer.start();

            secondPeer.expectSASLAnonymousConnect();
            secondPeer.expectOpen();
            secondPeer.dropAfterLastHandler();
            secondPeer.start();

            thirdPeer.expectFailingSASLPlainConnect(saslCode);
            thirdPeer.dropAfterLastHandler();
            thirdPeer.start();

            final CountDownLatch connected = new CountDownLatch(1);
            final CountDownLatch disconnected = new CountDownLatch(1);
            final CountDownLatch reconnected = new CountDownLatch(1);
            final CountDownLatch failed = new CountDownLatch(1);

            final URI firstURI = firstPeer.getServerURI();
            final URI secondURI = secondPeer.getServerURI();
            final URI thirdURI = thirdPeer.getServerURI();

            Client container = Client.create();
            ConnectionOptions options = new ConnectionOptions();
            options.user("test");
            options.password("pass");
            options.reconnectOptions().reconnectEnabled(true);
            options.reconnectOptions().addReconnectHost(secondURI.getHost(), secondURI.getPort())
                                      .addReconnectHost(thirdURI.getHost(), thirdURI.getPort());
            options.connectedHandler((connection, context) -> {
                connected.countDown();
            });
            options.interruptedHandler((connection, context) -> {
                disconnected.countDown();
            });
            options.reconnectedHandler((connection, context) -> {
                reconnected.countDown();  // This one should not be triggered
            });
            options.failedHandler((connection, context) -> {
                failed.countDown();
            });

            Connection connection = container.connect(firstURI.getHost(), firstURI.getPort(), options);

            connection.openFuture().get();

            firstPeer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            secondPeer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            thirdPeer.waitForScriptToComplete(5, TimeUnit.SECONDS);

            // Should connect, then fail and attempt to connect to second and third before stopping
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            assertTrue(disconnected.await(5, TimeUnit.SECONDS));
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertEquals(1, reconnected.getCount());

            connection.close();

            thirdPeer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testConnectHandlesSaslTempFailureAndReconnects() throws Exception {
        try (NettyTestPeer firstPeer = new NettyTestPeer();
             NettyTestPeer finalPeer = new NettyTestPeer()) {

            firstPeer.expectFailingSASLPlainConnect(SaslCode.SYS_TEMP.byteValue());
            firstPeer.dropAfterLastHandler();
            firstPeer.start();

            finalPeer.expectSASLPlainConnect("test", "pass");
            finalPeer.expectOpen().respond();
            finalPeer.start();

            final URI primaryURI = firstPeer.getServerURI();
            final URI backupURI = finalPeer.getServerURI();

            final CountDownLatch connected = new CountDownLatch(1);
            final AtomicReference<String> connectedHost = new AtomicReference<>();
            final AtomicReference<Integer> connectedPort = new AtomicReference<>();

            ConnectionOptions options = new ConnectionOptions();
            options.user("test");
            options.password("pass");
            options.reconnectOptions().reconnectEnabled(true);
            options.reconnectOptions().addReconnectHost(backupURI.getHost(), backupURI.getPort());
            options.connectedHandler((connection, event) -> {
                connectedHost.set(event.host());
                connectedPort.set(event.port());
                connected.countDown();
            });

            Client container = Client.create();
            Connection connection = container.connect(primaryURI.getHost(), primaryURI.getPort(), options);

            firstPeer.waitForScriptToComplete();

            connection.openFuture().get();

            assertTrue(connected.await(5, TimeUnit.SECONDS));

            // Should never have connected and exchanged Open performatives with first peer
            // so we won't have had a connection established event there.
            assertEquals(backupURI.getHost(), connectedHost.get());
            assertEquals(backupURI.getPort(), connectedPort.get());

            finalPeer.waitForScriptToComplete();

            finalPeer.expectClose().respond();
            connection.close();

            finalPeer.waitForScriptToComplete();
        }
    }

    @Test
    public void testConnectThrowsSecurityViolationOnFailureFromOpen() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {

            peer.expectSASLAnonymousConnect();
            peer.expectOpen().reject(AmqpError.UNAUTHORIZED_ACCESS.toString(), "Anonymous connections not allowed");
            peer.expectClose();
            peer.start();

            ConnectionOptions options = new ConnectionOptions();
            options.reconnectOptions().reconnectEnabled(true);

            URI remoteURI = peer.getServerURI();

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort(), options);

            try {
                connection.openFuture().get();
            } catch (ExecutionException exe) {
                assertTrue(exe.getCause() instanceof ClientConnectionSecurityException);
            }

            connection.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testReconnectHandlesDropThenRejectionCloseAfterConnect() throws Exception {
        try (NettyTestPeer firstPeer = new NettyTestPeer();
            NettyTestPeer secondPeer = new NettyTestPeer();
            NettyTestPeer thirdPeer = new NettyTestPeer()) {

           firstPeer.expectSASLAnonymousConnect();
           firstPeer.expectOpen().respond();
           firstPeer.start();

           secondPeer.expectSASLAnonymousConnect();
           secondPeer.expectOpen().reject(AmqpError.INVALID_FIELD.toString(), "Connection configuration has invalid field");
           secondPeer.expectClose();
           secondPeer.start();

           thirdPeer.expectSASLAnonymousConnect();
           thirdPeer.expectOpen().respond();
           thirdPeer.start();

           final CountDownLatch connected = new CountDownLatch(1);
           final CountDownLatch disconnected = new CountDownLatch(2);
           final CountDownLatch reconnected = new CountDownLatch(2);
           final CountDownLatch failed = new CountDownLatch(1);

           final URI firstURI = firstPeer.getServerURI();
           final URI secondURI = secondPeer.getServerURI();
           final URI thirdURI = thirdPeer.getServerURI();

           ConnectionOptions options = new ConnectionOptions();
           options.reconnectOptions().reconnectEnabled(true);
           options.reconnectOptions().addReconnectHost(secondURI.getHost(), secondURI.getPort())
                                     .addReconnectHost(thirdURI.getHost(), thirdURI.getPort());
           options.connectedHandler((connection, context) -> {
               connected.countDown();
           });
           options.interruptedHandler((connection, context) -> {
               disconnected.countDown();
           });
           options.reconnectedHandler((connection, context) -> {
               reconnected.countDown();
           });
           options.failedHandler((connection, context) -> {
               failed.countDown();  // Not expecting any failure in this test case
           });

           Connection connection = Client.create().connect(firstURI.getHost(), firstURI.getPort(), options);

           firstPeer.waitForScriptToComplete(5, TimeUnit.SECONDS);

           connection.openFuture().get();

           firstPeer.close();

           secondPeer.waitForScriptToComplete(5, TimeUnit.SECONDS);

           // Should connect, then fail and attempt to connect to second and be rejected then reconnect to third.
           assertTrue(connected.await(5, TimeUnit.SECONDS));
           assertTrue(disconnected.await(5, TimeUnit.SECONDS));
           assertTrue(reconnected.await(5, TimeUnit.SECONDS));
           assertEquals(1, failed.getCount());

           thirdPeer.expectClose().respond();
           connection.close();

           thirdPeer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testClientReconnectsWhenConnectionDropsAfterOpenReceived() throws Exception {
        doTestClientReconnectsWhenConnectionDropsAfterOpenReceived(0);
    }

    @Test
    public void testClientReconnectsWhenConnectionDropsAfterDelayAfterOpenReceived() throws Exception {
        doTestClientReconnectsWhenConnectionDropsAfterOpenReceived(20);
    }

    private void doTestClientReconnectsWhenConnectionDropsAfterOpenReceived(int dropDelay) throws Exception {
        try (NettyTestPeer firstPeer = new NettyTestPeer();
             NettyTestPeer finalPeer = new NettyTestPeer()) {

            firstPeer.expectSASLAnonymousConnect();
            firstPeer.expectOpen();
            if (dropDelay > 0) {
                firstPeer.dropAfterLastHandler(dropDelay);
            } else {
                firstPeer.dropAfterLastHandler();
            }
            firstPeer.start();

            finalPeer.expectSASLAnonymousConnect();
            finalPeer.expectOpen().respond();
            finalPeer.start();

            final URI primaryURI = firstPeer.getServerURI();
            final URI backupURI = finalPeer.getServerURI();

            final CountDownLatch connected = new CountDownLatch(1);
            final AtomicReference<String> connectedHost = new AtomicReference<>();
            final AtomicReference<Integer> connectedPort = new AtomicReference<>();

            ConnectionOptions options = new ConnectionOptions();
            options.reconnectOptions().reconnectEnabled(true);
            options.reconnectOptions().addReconnectHost(backupURI.getHost(), backupURI.getPort());
            options.connectedHandler((connection, event) -> {
                connectedHost.set(event.host());
                connectedPort.set(event.port());
                connected.countDown();
            });

            Client container = Client.create();
            Connection connection = container.connect(primaryURI.getHost(), primaryURI.getPort(), options);

            firstPeer.waitForScriptToComplete();

            connection.openFuture().get();

            assertTrue(connected.await(5, TimeUnit.SECONDS));
            assertEquals(backupURI.getHost(), connectedHost.get());
            assertEquals(backupURI.getPort(), connectedPort.get());

            finalPeer.waitForScriptToComplete();

            finalPeer.expectClose().respond();
            connection.close();

            finalPeer.waitForScriptToComplete();
        }
    }

    @Test
    public void testClientReconnectsWhenOpenRejected() throws Exception {
        try (NettyTestPeer firstPeer = new NettyTestPeer();
             NettyTestPeer finalPeer = new NettyTestPeer()) {

            firstPeer.expectSASLAnonymousConnect();
            firstPeer.expectOpen().reject(AmqpError.INVALID_FIELD.toString(), "Error with client Open performative");
            firstPeer.expectClose();
            firstPeer.start();

            finalPeer.expectSASLAnonymousConnect();
            finalPeer.expectOpen().respond();
            finalPeer.start();

            final URI primaryURI = firstPeer.getServerURI();
            final URI backupURI = finalPeer.getServerURI();

            final CountDownLatch connected = new CountDownLatch(1);
            final AtomicReference<String> connectedHost = new AtomicReference<>();
            final AtomicReference<Integer> connectedPort = new AtomicReference<>();

            ConnectionOptions options = new ConnectionOptions();
            options.reconnectOptions().reconnectEnabled(true);
            options.reconnectOptions().addReconnectHost(backupURI.getHost(), backupURI.getPort());
            options.connectedHandler((connection, event) -> {
                connectedHost.set(event.host());
                connectedPort.set(event.port());
                connected.countDown();
            });

            Client container = Client.create();
            Connection connection = container.connect(primaryURI.getHost(), primaryURI.getPort(), options);

            firstPeer.waitForScriptToComplete();

            connection.openFuture().get();

            assertTrue(connected.await(5, TimeUnit.SECONDS));
            assertEquals(primaryURI.getHost(), connectedHost.get());
            assertEquals(primaryURI.getPort(), connectedPort.get());

            finalPeer.waitForScriptToComplete();

            finalPeer.expectClose().respond();
            connection.close();

            finalPeer.waitForScriptToComplete();
        }
    }

    @Test
    public void testClientReconnectsWhenConnectionRemotelyClosedWithForced() throws Exception {
        try (NettyTestPeer firstPeer = new NettyTestPeer();
             NettyTestPeer finalPeer = new NettyTestPeer()) {

            firstPeer.expectSASLAnonymousConnect();
            firstPeer.expectOpen().respond();
            firstPeer.expectBegin();
            firstPeer.remoteClose().withErrorCondition(ConnectionError.CONNECTION_FORCED.toString(), "Forced disconnect").queue();
            firstPeer.expectClose();
            firstPeer.start();

            finalPeer.expectSASLAnonymousConnect();
            finalPeer.expectOpen().respond();
            finalPeer.expectBegin().respond();
            finalPeer.start();

            final URI primaryURI = firstPeer.getServerURI();
            final URI backupURI = finalPeer.getServerURI();

            final CountDownLatch connected = new CountDownLatch(1);
            final CountDownLatch disconnected = new CountDownLatch(1);
            final CountDownLatch reconnected = new CountDownLatch(1);
            final CountDownLatch failed = new CountDownLatch(1);

            ConnectionOptions options = new ConnectionOptions();
            options.reconnectOptions().reconnectEnabled(true);
            options.reconnectOptions().addReconnectHost(backupURI.getHost(), backupURI.getPort());
            options.connectedHandler((connection, context) -> {
                connected.countDown();
            });
            options.interruptedHandler((connection, context) -> {
                disconnected.countDown();
            });
            options.reconnectedHandler((connection, context) -> {
                reconnected.countDown();
            });
            options.failedHandler((connection, context) -> {
                failed.countDown();  // Not expecting any failure in this test case
            });

            Client container = Client.create();
            Connection connection = container.connect(primaryURI.getHost(), primaryURI.getPort(), options);
            Session session = connection.openSession();

            connection.openFuture().get();

            firstPeer.waitForScriptToComplete();

            try {
                session.openFuture().get();
            } catch (Exception ex) {
                fail("Should eventually succeed in opening this Session");
            }

            // Should connect, then be remotely closed and reconnect to the alternate
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            assertTrue(disconnected.await(5, TimeUnit.SECONDS));
            assertTrue(reconnected.await(5, TimeUnit.SECONDS));
            assertEquals(1, failed.getCount());

            finalPeer.waitForScriptToComplete();
            finalPeer.expectClose().respond();

            connection.close();

            finalPeer.waitForScriptToComplete();
        }
    }

    @Test
    public void testInitialReconnectDelayDoesNotApplyToInitialConnect() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectClose().respond();
            peer.start();

            ConnectionOptions options = new ConnectionOptions();
            options.reconnectOptions().reconnectEnabled(true);

            final URI remoteURI = peer.getServerURI();
            final int delay = 20000;
            final StopWatch watch = new StopWatch();

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort(), options);

            connection.openFuture().get();

            long taken = watch.taken();

            final String message = "Initial connect should not have delayed for the specified initialReconnectDelay." +
                                   "Elapsed=" + taken + ", delay=" + delay;
            assertTrue(taken < delay, message);
            assertTrue(taken < 5000, "Connection took longer than reasonable: " + taken);

            connection.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testOpenedSessionRecoveredAfterConnectionDropped() throws Exception {
        try (NettyTestPeer firstPeer = new NettyTestPeer();
             NettyTestPeer finalPeer = new NettyTestPeer()) {

            firstPeer.expectSASLAnonymousConnect();
            firstPeer.expectOpen().respond();
            firstPeer.expectBegin().respond();
            firstPeer.dropAfterLastHandler(5);
            firstPeer.start();

            finalPeer.expectSASLAnonymousConnect();
            finalPeer.expectOpen().respond();
            finalPeer.expectBegin().respond();
            finalPeer.start();

            final URI primaryURI = firstPeer.getServerURI();
            final URI backupURI = finalPeer.getServerURI();

            ConnectionOptions options = new ConnectionOptions();
            options.reconnectOptions().reconnectEnabled(true);
            options.reconnectOptions().addReconnectHost(backupURI.getHost(), backupURI.getPort());

            Client container = Client.create();
            Connection connection = container.connect(primaryURI.getHost(), primaryURI.getPort(), options);
            Session session = connection.openSession().openFuture().get();

            firstPeer.waitForScriptToComplete();

            connection.openFuture().get();

            finalPeer.waitForScriptToComplete();
            finalPeer.expectEnd().respond();
            finalPeer.expectClose().respond();

            session.close();
            connection.close();

            finalPeer.waitForScriptToComplete();
        }
    }

    @Test
    public void testOpenedSenderRecoveredAfterConnectionDropped() throws Exception {
        try (NettyTestPeer firstPeer = new NettyTestPeer();
             NettyTestPeer finalPeer = new NettyTestPeer()) {

            firstPeer.expectSASLAnonymousConnect();
            firstPeer.expectOpen().respond();
            firstPeer.expectBegin().respond();
            firstPeer.expectAttach().ofSender().withTarget().withAddress("test").and().respond();
            firstPeer.dropAfterLastHandler(5);
            firstPeer.start();

            finalPeer.expectSASLAnonymousConnect();
            finalPeer.expectOpen().respond();
            finalPeer.expectBegin().respond();
            finalPeer.expectAttach().ofSender().withTarget().withAddress("test").and().respond();
            finalPeer.start();

            final URI primaryURI = firstPeer.getServerURI();
            final URI backupURI = finalPeer.getServerURI();

            ConnectionOptions options = new ConnectionOptions();
            options.reconnectOptions().reconnectEnabled(true);
            options.reconnectOptions().addReconnectHost(backupURI.getHost(), backupURI.getPort());

            Client container = Client.create();
            Connection connection = container.connect(primaryURI.getHost(), primaryURI.getPort(), options);
            Session session = connection.openSession();
            Sender sender = session.openSender("test");

            firstPeer.waitForScriptToComplete();
            finalPeer.waitForScriptToComplete();
            finalPeer.expectDetach().withClosed(true).respond();
            finalPeer.expectEnd().respond();
            finalPeer.expectClose().respond();

            sender.close();
            session.close();
            connection.close();

            finalPeer.waitForScriptToComplete();
        }
    }

    @Test
    public void testInFlightSendFailedAfterConnectionDroppedAndNotResent() throws Exception {
        try (NettyTestPeer firstPeer = new NettyTestPeer();
             NettyTestPeer finalPeer = new NettyTestPeer()) {

           firstPeer.expectSASLAnonymousConnect();
           firstPeer.expectOpen().respond();
           firstPeer.expectBegin().respond();
           firstPeer.expectAttach().ofSender().withTarget().withAddress("test").and().respond();
           firstPeer.remoteFlow().withLinkCredit(1).queue();
           firstPeer.expectTransfer().withNonNullPayload();
           firstPeer.dropAfterLastHandler(15);
           firstPeer.start();

           finalPeer.expectSASLAnonymousConnect();
           finalPeer.expectOpen().respond();
           finalPeer.expectBegin().respond();
           finalPeer.expectAttach().ofSender().withTarget().withAddress("test").and().respond();
           finalPeer.start();

           final URI primaryURI = firstPeer.getServerURI();
           final URI backupURI = finalPeer.getServerURI();

           ConnectionOptions options = new ConnectionOptions();
           options.reconnectOptions().reconnectEnabled(true);
           options.reconnectOptions().addReconnectHost(backupURI.getHost(), backupURI.getPort());

           Client container = Client.create();
           Connection connection = container.connect(primaryURI.getHost(), primaryURI.getPort(), options);
           Session session = connection.openSession();
           Sender sender = session.openSender("test");

           final AtomicReference<Tracker> tracker = new AtomicReference<>();
           final AtomicReference<ClientException> error = new AtomicReference<>();
           final CountDownLatch latch = new CountDownLatch(1);

           ForkJoinPool.commonPool().execute(() -> {
               try {
                   tracker.set(sender.send(Message.create("Hello")));
               } catch (ClientException e) {
                   error.set(e);
               } finally {
                   latch.countDown();
               }
           });

           firstPeer.waitForScriptToComplete();
           finalPeer.waitForScriptToComplete();
           finalPeer.expectDetach().withClosed(true).respond();
           finalPeer.expectEnd().respond();
           finalPeer.expectClose().respond();

           assertTrue(latch.await(10, TimeUnit.SECONDS), "Should have failed previously sent message");
           assertNotNull(tracker.get());
           assertNull(error.get());
           assertThrows(ClientConnectionRemotelyClosedException.class, () -> tracker.get().awaitSettlement());

           sender.close();
           session.close();
           connection.close();

           finalPeer.waitForScriptToComplete();
       }
    }

    @Test
    public void testSendBlockedOnCreditGetsSentAfterReconnectAndCreditGranted() throws Exception {
        try (NettyTestPeer firstPeer = new NettyTestPeer();
             NettyTestPeer finalPeer = new NettyTestPeer()) {

           firstPeer.expectSASLAnonymousConnect();
           firstPeer.expectOpen().respond();
           firstPeer.expectBegin().respond();
           firstPeer.expectAttach().ofSender().withTarget().withAddress("test").and().respond();
           firstPeer.dropAfterLastHandler(15);
           firstPeer.start();

           finalPeer.expectSASLAnonymousConnect();
           finalPeer.expectOpen().respond();
           finalPeer.expectBegin().respond();
           finalPeer.expectAttach().ofSender().withTarget().withAddress("test").and().respond();
           finalPeer.start();

           final URI primaryURI = firstPeer.getServerURI();
           final URI backupURI = finalPeer.getServerURI();

           ConnectionOptions options = new ConnectionOptions();
           options.reconnectOptions().reconnectEnabled(true);
           options.reconnectOptions().addReconnectHost(backupURI.getHost(), backupURI.getPort());

           Client container = Client.create();
           Connection connection = container.connect(primaryURI.getHost(), primaryURI.getPort(), options);
           Session session = connection.openSession();
           Sender sender = session.openSender("test");

           final AtomicReference<Tracker> tracker = new AtomicReference<>();
           final CountDownLatch latch = new CountDownLatch(1);

           ForkJoinPool.commonPool().execute(() -> {
               try {
                   tracker.set(sender.send(Message.create("Hello")));
               } catch (ClientException e) {
               } finally {
                   latch.countDown();
               }
           });

           firstPeer.waitForScriptToComplete();
           finalPeer.waitForScriptToComplete();
           finalPeer.expectTransfer().withNonNullPayload()
                                     .respond()
                                     .withSettled(true).withState().accepted();
           finalPeer.expectDetach().withClosed(true).respond();
           finalPeer.expectEnd().respond();
           finalPeer.expectClose().respond();

           // Grant credit now and await expected message send.
           finalPeer.remoteFlow().withDeliveryCount(0)
                                 .withLinkCredit(10)
                                 .withIncomingWindow(10)
                                 .withOutgoingWindow(10)
                                 .withNextIncomingId(0)
                                 .withNextOutgoingId(1).now();

           assertTrue(latch.await(10, TimeUnit.SECONDS), "Should have sent blocked message");
           assertNotNull(tracker.get());

           Tracker send = tracker.get();
           assertSame(tracker.get(), send.awaitSettlement(10, TimeUnit.SECONDS));
           assertTrue(send.remoteSettled());
           assertEquals(DeliveryState.accepted(), send.remoteState());

           sender.close();
           session.close();
           connection.close();

           finalPeer.waitForScriptToComplete();
       }
    }

    @Test
    public void testOpenedreceiverRecoveredAfterConnectionDroppedCreditWindow() throws Exception {
        doTestOpenedReceiverRecoveredAfterConnectionDropped(false);
    }

    @Test
    public void testOpenedReceiverRecoveredAfterConnectionDroppedFixedCreditGrant() throws Exception {
        doTestOpenedReceiverRecoveredAfterConnectionDropped(true);
    }

    private void doTestOpenedReceiverRecoveredAfterConnectionDropped(boolean fixedCredit) throws Exception {
        try (NettyTestPeer firstPeer = new NettyTestPeer();
             NettyTestPeer finalPeer = new NettyTestPeer()) {

            final int FIXED_CREDIT = 25;
            final int CREDIT_WINDOW = 15;

            firstPeer.expectSASLAnonymousConnect();
            firstPeer.expectOpen().respond();
            firstPeer.expectBegin().respond();
            firstPeer.expectAttach().ofReceiver().withSource().withAddress("test").and().respond();
            if (fixedCredit) {
                firstPeer.expectFlow().withLinkCredit(FIXED_CREDIT);
            } else {
                firstPeer.expectFlow().withLinkCredit(CREDIT_WINDOW);
            }
            firstPeer.dropAfterLastHandler(5);
            firstPeer.start();

            finalPeer.expectSASLAnonymousConnect();
            finalPeer.expectOpen().respond();
            finalPeer.expectBegin().respond();
            finalPeer.expectAttach().ofReceiver().withSource().withAddress("test").and().respond();
            if (fixedCredit) {
                finalPeer.expectFlow().withLinkCredit(FIXED_CREDIT);
            } else {
                finalPeer.expectFlow().withLinkCredit(CREDIT_WINDOW);
            }
            finalPeer.start();

            final URI primaryURI = firstPeer.getServerURI();
            final URI backupURI = finalPeer.getServerURI();

            ConnectionOptions options = new ConnectionOptions();
            options.reconnectOptions().reconnectEnabled(true);
            options.reconnectOptions().addReconnectHost(backupURI.getHost(), backupURI.getPort());

            Client container = Client.create();
            Connection connection = container.connect(primaryURI.getHost(), primaryURI.getPort(), options);
            Session session = connection.openSession();
            ReceiverOptions receiverOptions = new ReceiverOptions();
            if (fixedCredit) {
                receiverOptions.creditWindow(0);
            } else {
                receiverOptions.creditWindow(CREDIT_WINDOW);
            }

            Receiver receiver = session.openReceiver("test", receiverOptions);
            if (fixedCredit) {
                receiver.addCredit(FIXED_CREDIT);
            }

            firstPeer.waitForScriptToComplete();
            finalPeer.waitForScriptToComplete();
            finalPeer.expectDetach().withClosed(true).respond();
            finalPeer.expectEnd().respond();
            finalPeer.expectClose().respond();

            receiver.close();
            session.close();
            connection.close();

            finalPeer.waitForScriptToComplete();
        }
    }
}
