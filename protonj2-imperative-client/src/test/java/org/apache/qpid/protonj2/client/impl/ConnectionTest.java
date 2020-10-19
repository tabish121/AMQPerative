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

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.qpid.protonj2.client.Client;
import org.apache.qpid.protonj2.client.Connection;
import org.apache.qpid.protonj2.client.ConnectionOptions;
import org.apache.qpid.protonj2.client.ErrorCondition;
import org.apache.qpid.protonj2.client.Receiver;
import org.apache.qpid.protonj2.client.Sender;
import org.apache.qpid.protonj2.client.Session;
import org.apache.qpid.protonj2.client.exceptions.ClientConnectionRemotelyClosedException;
import org.apache.qpid.protonj2.client.exceptions.ClientException;
import org.apache.qpid.protonj2.client.exceptions.ClientIOException;
import org.apache.qpid.protonj2.client.exceptions.ClientUnsupportedOperationException;
import org.apache.qpid.protonj2.client.test.ImperativeClientTestCase;
import org.apache.qpid.protonj2.test.driver.matchers.messaging.SourceMatcher;
import org.apache.qpid.protonj2.test.driver.netty.NettyTestPeer;
import org.apache.qpid.protonj2.types.transport.AMQPHeader;
import org.apache.qpid.protonj2.types.transport.AmqpError;
import org.apache.qpid.protonj2.types.transport.ConnectionError;
import org.apache.qpid.protonj2.types.transport.Role;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test for the Connection class
 */
@Timeout(20)
public class ConnectionTest extends ImperativeClientTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionTest.class);

    @Test
    public void testCreateTwoDistinctConnectionsFromSingleClientInstance() throws Exception {
        try (NettyTestPeer firstPeer = new NettyTestPeer();
             NettyTestPeer secondPeer = new NettyTestPeer()) {

            firstPeer.expectSASLAnonymousConnect();
            firstPeer.expectOpen().respond();
            firstPeer.expectClose().respond();
            firstPeer.start();

            secondPeer.expectSASLAnonymousConnect();
            secondPeer.expectOpen().respond();
            secondPeer.expectClose().respond();
            secondPeer.start();

            final URI firstURI = firstPeer.getServerURI();
            final URI secondURI = secondPeer.getServerURI();

            Client container = Client.create();
            Connection connection1 = container.connect(firstURI.getHost(), firstURI.getPort());
            Connection connection2 = container.connect(secondURI.getHost(), secondURI.getPort());

            connection1.openFuture().get();
            connection2.openFuture().get();

            connection1.closeAsync().get();
            connection2.closeAsync().get();

            firstPeer.waitForScriptToComplete();
            secondPeer.waitForScriptToComplete();
        }
    }

    @Test
    public void testCreateConnectionToNonSaslPeer() throws Exception {
        doConnectionWithUnexpectedHeaderTestImpl(AMQPHeader.getAMQPHeader().toArray());
    }

    @Test
    public void testCreateConnectionToNonAmqpPeer() throws Exception {
        doConnectionWithUnexpectedHeaderTestImpl(new byte[] { 'N', 'O', 'T', '-', 'A', 'M', 'Q', 'P' });
    }

    private void doConnectionWithUnexpectedHeaderTestImpl(byte[] responseHeader) throws Exception, IOException {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLHeader().respondWithBytes(responseHeader);
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            ConnectionOptions options = new ConnectionOptions();
            options.user("guest");
            options.password("guest");
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort(), options);

            try {
                connection.openFuture().get(10, TimeUnit.SECONDS);
            } catch (ExecutionException ex) {}

            connection.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testCreateConnectionString() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectClose().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());

            connection.openFuture().get(10, TimeUnit.SECONDS);
            connection.closeAsync().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testCreateConnectionStringWithDefaultTcpPort() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectClose().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            ConnectionOptions options = new ConnectionOptions();
            options.transportOptions().defaultTcpPort(remoteURI.getPort());
            Connection connection = container.connect(remoteURI.getHost(), options);

            connection.openFuture().get(10, TimeUnit.SECONDS);
            connection.closeAsync().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testCreateConnectionEstablishedHandlerGetsCalled() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectClose().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            final CountDownLatch established = new CountDownLatch(1);
            final ConnectionOptions options = new ConnectionOptions();

            options.connectedHandler((connection, location) -> {
                LOG.info("Connection signaled that it was established");
                established.countDown();
            });

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort(), options);

            assertTrue(established.await(10, TimeUnit.SECONDS));

            connection.openFuture().get(10, TimeUnit.SECONDS);
            connection.closeAsync().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testCreateConnectionFailedHandlerGetsCalled() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin();
            peer.dropAfterLastHandler(10);
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            final CountDownLatch failed = new CountDownLatch(1);
            final ConnectionOptions options = new ConnectionOptions();

            options.failedHandler((connection, location) -> {
                LOG.info("Connection signaled that it has failed");
                failed.countDown();
            });

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort(), options);

            connection.openFuture().get(10, TimeUnit.SECONDS);
            connection.openSession();

            assertTrue(failed.await(10, TimeUnit.SECONDS));

            connection.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testConnectionCloseGetsResponseWithErrorDoesNotThrowTimedGet() throws Exception {
        doTestConnectionCloseGetsResponseWithErrorDoesNotThrow(true);
    }

    @Test
    public void testConnectionCloseGetsResponseWithErrorDoesNotThrowUntimedGet() throws Exception {
        doTestConnectionCloseGetsResponseWithErrorDoesNotThrow(false);
    }

    protected void doTestConnectionCloseGetsResponseWithErrorDoesNotThrow(boolean tiemout) throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectClose().respond().withErrorCondition(ConnectionError.CONNECTION_FORCED.toString(), "Not accepting connections");
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());

            if (tiemout) {
                connection.openFuture().get(10, TimeUnit.SECONDS);
                // Should close normally and not throw error as we initiated the close.
                connection.closeAsync().get(10, TimeUnit.SECONDS);
            } else {
                connection.openFuture().get();
                // Should close normally and not throw error as we initiated the close.
                connection.closeAsync().get();
            }

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testConnectionBlockingCloseGetsResponseWithErrorDoesNotThrow() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectClose().respond().withErrorCondition(ConnectionError.CONNECTION_FORCED.toString(), "Not accepting connections");
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());

            connection.openFuture().get();
            // Should close normally and not throw error as we initiated the close.
            connection.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testConnectionRemoteClosedAfterOpened() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().reject(ConnectionError.CONNECTION_FORCED.toString(), "Not accepting connections");
            peer.expectClose();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());

            connection.openFuture().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);

            connection.closeAsync().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete();
        }
    }

    @Test
    public void testConnectionOpenFutureWaitCancelledOnConnectionDropWithTimeout() throws Exception {
        doTestConnectionOpenFutureWaitCancelledOnConnectionDrop(true);
    }

    @Test
    public void testConnectionOpenFutureWaitCancelledOnConnectionDropNoTimeout() throws Exception {
        doTestConnectionOpenFutureWaitCancelledOnConnectionDrop(false);
    }

    protected void doTestConnectionOpenFutureWaitCancelledOnConnectionDrop(boolean timeout) throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.close();

            try {
                if (timeout) {
                    connection.openFuture().get(10, TimeUnit.SECONDS);
                } else {
                    connection.openFuture().get();
                }
                fail("Should have thrown an execution error due to connection drop");
            } catch (ExecutionException error) {
                LOG.info("connection open failed with error: ", error);
            }

            try {
                if (timeout) {
                    connection.closeAsync().get(10, TimeUnit.SECONDS);
                } else {
                    connection.closeAsync().get();
                }
            } catch (Throwable error) {
                LOG.info("connection close failed with error: ", error);
                fail("Close should ignore connect error and complete without error.");
            }

            peer.waitForScriptToComplete();
        }
    }

    @Test
    public void testRemotelyCloseConnectionDuringSessionCreation() throws Exception {
        final String BREAD_CRUMB = "ErrorMessageBreadCrumb";

        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin();
            peer.remoteClose().withErrorCondition(AmqpError.NOT_ALLOWED.toString(), BREAD_CRUMB).queue();
            peer.expectClose();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            connection.openFuture().get();

            Session session = connection.openSession();

            try {
                session.openFuture().get();
                fail("Open should throw error when waiting for remote open and connection remotely closed.");
            } catch (ExecutionException error) {
                LOG.info("Session open failed with error: ", error);
                assertNotNull(error.getMessage(), "Expected exception to have a message");
                assertTrue(error.getMessage().contains(BREAD_CRUMB), "Expected breadcrumb to be present in message");
                assertNotNull(error.getCause(), "Execution error should convery the cause");
                assertTrue(error.getCause() instanceof ClientConnectionRemotelyClosedException);
            }

            session.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);

            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testConnectionOpenTimeoutWhenNoRemoteOpenArrivesTimeout() throws Exception {
        doTestConnectionOpenTimeoutWhenNoRemoteOpenArrives(true);
    }

    @Test
    public void testConnectionOpenTimeoutWhenNoRemoteOpenArrivesNoTimeout() throws Exception {
        doTestConnectionOpenTimeoutWhenNoRemoteOpenArrives(false);
    }

    private void doTestConnectionOpenTimeoutWhenNoRemoteOpenArrives(boolean timeout) throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen();
            peer.expectClose();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            ConnectionOptions options = new ConnectionOptions();
            options.openTimeout(75);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort(), options);

            try {
                if (timeout) {
                    connection.openFuture().get(10, TimeUnit.SECONDS);
                } else {
                    connection.openFuture().get();
                }

                fail("Open should timeout when no open response and complete future with error.");
            } catch (Throwable error) {
                LOG.info("connection open failed with error: ", error);
            }

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testConnectionOpenWaitWithTimeoutCanceledWhenConnectionDrops() throws Exception {
        doTestConnectionOpenWaitCanceledWhenConnectionDrops(true);
    }

    @Test
    public void testConnectionOpenWaitWithNoTimeoutCanceledWhenConnectionDrops() throws Exception {
        doTestConnectionOpenWaitCanceledWhenConnectionDrops(false);
    }

    private void doTestConnectionOpenWaitCanceledWhenConnectionDrops(boolean timeout) throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen();
            peer.dropAfterLastHandler(10);
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);
            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());

            try {
                if (timeout) {
                    connection.openFuture().get(10, TimeUnit.SECONDS);
                } else {
                    connection.openFuture().get();
                }

                fail("Open should timeout when no open response and complete future with error.");
            } catch (ExecutionException error) {
                LOG.info("connection open failed with error: ", error);
                assertTrue(error.getCause() instanceof ClientIOException);
            }

            connection.client();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testConnectionCloseTimeoutWhenNoRemoteCloseArrivesTimeout() throws Exception {
        doTestConnectionCloseTimeoutWhenNoRemoteCloseArrives(true);
    }

    @Test
    public void testConnectionCloseTimeoutWhenNoRemoteCloseArrivesNoTimeout() throws Exception {
        doTestConnectionCloseTimeoutWhenNoRemoteCloseArrives(false);
    }

    private void doTestConnectionCloseTimeoutWhenNoRemoteCloseArrives(boolean timeout) throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectClose();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            ConnectionOptions options = new ConnectionOptions();
            options.closeTimeout(75);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort(), options);

            connection.openFuture().get(10, TimeUnit.SECONDS);

            // Shouldn't throw from close, nothing to be done anyway.
            try {
                if (timeout) {
                    connection.closeAsync().get(10, TimeUnit.SECONDS);
                } else {
                    connection.closeAsync().get();
                }
            } catch (Throwable error) {
                LOG.info("connection close failed with error: ", error);
                fail("Close should ignore lack of close response and complete without error.");
            }

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testConnectionCloseWaitWithTimeoutCompletesAfterRemoteConnectionDrops() throws Exception {
        doTestConnectionCloseWaitCompletesAfterRemoteConnectionDrops(true);
    }

    @Test
    public void testConnectionCloseWaitWithNoTimeoutCompletesAfterRemoteConnectionDrops() throws Exception {
        doTestConnectionCloseWaitCompletesAfterRemoteConnectionDrops(false);
    }

    private void doTestConnectionCloseWaitCompletesAfterRemoteConnectionDrops(boolean timeout) throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectClose();
            peer.dropAfterLastHandler(10);
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());

            connection.openFuture().get(10, TimeUnit.SECONDS);

            // Shouldn't throw from close, nothing to be done anyway.
            try {
                if (timeout) {
                    connection.closeAsync().get(10, TimeUnit.SECONDS);
                } else {
                    connection.closeAsync().get();
                }
            } catch (Throwable error) {
                LOG.info("connection close failed with error: ", error);
                fail("Close should treat Connection drop as success and complete without error.");
            }

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testCreateDefaultSenderFailsOnConnectionWithoutSupportForAnonymousRelay() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectClose().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort()).openFuture().get();

            try {
                connection.defaultSender();
                fail("Should not be able to get the default sender when remote does not offer anonymous relay");
            } catch (ClientUnsupportedOperationException unsupported) {
                LOG.info("Caught expected error: ", unsupported);
            }

            connection.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testCreateDefaultSenderOnConnectionWithSupportForAnonymousRelay() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().withDesiredCapabilities(ClientConstants.ANONYMOUS_RELAY.toString())
                             .respond()
                             .withOfferedCapabilities(ClientConstants.ANONYMOUS_RELAY.toString());
            peer.expectBegin().respond();
            peer.expectAttach().withRole(Role.SENDER.getValue()).respond();
            peer.expectClose().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            connection.openFuture().get(10, TimeUnit.SECONDS);

            Sender defaultSender = connection.defaultSender().openFuture().get(5, TimeUnit.SECONDS);
            assertNotNull(defaultSender);

            connection.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testConnectionRecreatesAnonymousRelaySenderAfterRemoteCloseOfSender() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().withDesiredCapabilities(ClientConstants.ANONYMOUS_RELAY.toString())
                             .respond()
                             .withOfferedCapabilities(ClientConstants.ANONYMOUS_RELAY.toString());
            peer.expectBegin().respond();
            peer.expectAttach().withRole(Role.SENDER.getValue()).respond();
            peer.remoteDetach().queue();
            peer.expectDetach();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            connection.openFuture().get(10, TimeUnit.SECONDS);

            Sender defaultSender = connection.defaultSender().openFuture().get(5, TimeUnit.SECONDS);
            assertNotNull(defaultSender);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectAttach().withRole(Role.SENDER.getValue()).respond();
            peer.expectClose().respond();

            defaultSender = connection.defaultSender().openFuture().get(5, TimeUnit.SECONDS);
            assertNotNull(defaultSender);

            connection.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testCreateDynamicReceiver() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().withRole(Role.RECEIVER.getValue())
                               .withSource(new SourceMatcher().withDynamic(true).withAddress(nullValue()))
                               .respond();
            peer.expectFlow();
            peer.expectDetach().respond();
            peer.expectClose().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            connection.openFuture().get(10, TimeUnit.SECONDS);

            Receiver receiver = connection.openDynamicReceiver();
            receiver.openFuture().get(10, TimeUnit.SECONDS);

            assertNotNull("Remote should have assigned the address for the dynamic receiver", receiver.address());

            receiver.closeAsync().get(10, TimeUnit.SECONDS);

            connection.closeAsync().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testConnectionSenderOpenHeldUntilConnectionOpenedAndRelaySupportConfirmed() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen();
            peer.expectBegin();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            Sender sender = connection.defaultSender();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);

            // This should happen after we inject the held open and attach
            peer.expectAttach().withRole(Role.SENDER.getValue()).withTarget().withAddress(Matchers.nullValue()).and().respond();
            peer.expectClose().respond();

            // Inject held responses to get the ball rolling again
            peer.remoteOpen().withOfferedCapabilities("ANONYMOUS-RELAY").now();
            peer.respondToLastBegin().now();

            try {
                sender.openFuture().get();
            } catch (ExecutionException ex) {
                fail("Open of Sender failed waiting for response: " + ex.getCause());
            }

            connection.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testConnectionGetRemotePropertiesWaitsForRemoteBegin() throws Exception {
        tryReadConnectionRemoteProperties(true);
    }

    @Test
    public void testConnectionGetRemotePropertiesFailsAfterOpenTimeout() throws Exception {
        tryReadConnectionRemoteProperties(false);
    }

    private void tryReadConnectionRemoteProperties(boolean openResponse) throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            ConnectionOptions options = new ConnectionOptions().openTimeout(100);
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort(), options);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);

            Map<String, Object> expectedProperties = new HashMap<>();
            expectedProperties.put("TEST", "test-property");

            if (openResponse) {
                peer.expectClose().respond();
                peer.remoteOpen().withProperties(expectedProperties).later(10);
            } else {
                peer.expectClose();
            }

            if (openResponse) {
                assertNotNull(connection.properties(), "Remote should have responded with a remote properties value");
                assertEquals(expectedProperties, connection.properties());
            } else {
                try {
                    connection.properties();
                    fail("Should failed to get remote state due to no open response");
                } catch (ClientException ex) {
                    LOG.debug("Caught expected exception from blocking call", ex);
                }
            }

            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testConnectionGetRemoteOfferedCapabilitiesWaitsForRemoteBegin() throws Exception {
        tryReadConnectionRemoteOfferedCapabilities(true);
    }

    @Test
    public void testConnectionGetRemoteOfferedCapabilitiesFailsAfterOpenTimeout() throws Exception {
        tryReadConnectionRemoteOfferedCapabilities(false);
    }

    private void tryReadConnectionRemoteOfferedCapabilities(boolean openResponse) throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            ConnectionOptions options = new ConnectionOptions().openTimeout(100);
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort(), options);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);

            if (openResponse) {
                peer.expectClose().respond();
                peer.remoteOpen().withOfferedCapabilities("transactions").later(10);
            } else {
                peer.expectClose();
            }

            if (openResponse) {
                assertNotNull(connection.offeredCapabilities(), "Remote should have responded with a remote offered Capabilities value");
                assertEquals(1, connection.offeredCapabilities().length);
                assertEquals("transactions", connection.offeredCapabilities()[0]);
            } else {
                try {
                    connection.offeredCapabilities();
                    fail("Should failed to get remote state due to no open response");
                } catch (ClientException ex) {
                    LOG.debug("Caught expected exception from blocking call", ex);
                }
            }

            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testConnectionGetRemoteDesiredCapabilitiesWaitsForRemoteBegin() throws Exception {
        tryReadConnectionRemoteDesiredCapabilities(true);
    }

    @Test
    public void testConnectionGetRemoteDesiredCapabilitiesFailsAfterOpenTimeout() throws Exception {
        tryReadConnectionRemoteDesiredCapabilities(false);
    }

    private void tryReadConnectionRemoteDesiredCapabilities(boolean openResponse) throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Connect test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            ConnectionOptions options = new ConnectionOptions().openTimeout(100);
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort(), options);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);

            if (openResponse) {
                peer.expectClose().respond();
                peer.remoteOpen().withDesiredCapabilities("Error-Free").later(10);
            } else {
                peer.expectClose();
            }

            if (openResponse) {
                assertNotNull(connection.desiredCapabilities(), "Remote should have responded with a remote desired Capabilities value");
                assertEquals(1, connection.desiredCapabilities().length);
                assertEquals("Error-Free", connection.desiredCapabilities()[0]);
            } else {
                try {
                    connection.desiredCapabilities();
                    fail("Should failed to get remote state due to no open response");
                } catch (ClientException ex) {
                    LOG.debug("Caught expected exception from blocking call", ex);
                }
            }

            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testCloseWithErrorCondition() throws Exception {
        final String condition = "amqp:precondition-failed";
        final String description = "something bad happened.";

        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectClose().withError(condition, description).respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort()).openFuture().get();

            connection.close(ErrorCondition.create(condition, description, null));

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }
}