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
package org.messaginghub.amqperative.debug;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.broker.region.policy.VMPendingQueueMessageStoragePolicy;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.messaginghub.amqperative.Client;
import org.messaginghub.amqperative.ClientOptions;
import org.messaginghub.amqperative.Connection;
import org.messaginghub.amqperative.Message;
import org.messaginghub.amqperative.Receiver;
import org.messaginghub.amqperative.Sender;
import org.messaginghub.amqperative.support.AMQPerativeTestSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Ignore
public class ProducerAndConsumerBench extends AMQPerativeTestSupport  {

    private static final Logger LOG = LoggerFactory.getLogger(ProducerAndConsumerBench.class);

    public static final int PAYLOAD_SIZE = 64 * 1024;
    public static final int ioBuffer = 2 * PAYLOAD_SIZE;
    public static final int socketBuffer = 64 * PAYLOAD_SIZE;

    private final byte[] payload = new byte[PAYLOAD_SIZE];
    private final int parallelProducer = 1;
    private final int parallelConsumer = 1;
    private final Vector<Throwable> exceptions = new Vector<Throwable>();

    private final long NUM_SENDS = 30000;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();

        for (int i = 0; i < PAYLOAD_SIZE; ++i) {
            payload[i] = (byte) (i % 255);
        }
    }

    @Test
    public void testProduceConsume() throws Exception {

        final AtomicLong sharedSendCount = new AtomicLong(NUM_SENDS);
        final AtomicLong sharedReceiveCount = new AtomicLong(NUM_SENDS);

        Thread.sleep(2000);

        long start = System.currentTimeMillis();
        ExecutorService executorService = Executors.newFixedThreadPool(parallelConsumer + parallelProducer);

        for (int i = 0; i < parallelConsumer; i++) {
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        consumeMessages(sharedReceiveCount);
                    } catch (Throwable e) {
                        exceptions.add(e);
                    }
                }
            });
        }
        for (int i = 0; i < parallelProducer; i++) {
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        publishMessages(sharedSendCount);
                    } catch (Throwable e) {
                        exceptions.add(e);
                    }
                }
            });
        }

        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.MINUTES);
        assertTrue("Producers done in time", executorService.isTerminated());
        assertTrue("No exceptions: " + exceptions, exceptions.isEmpty());

        double duration = System.currentTimeMillis() - start;
        LOG.info("Duration:            " + duration + "ms");
        LOG.info("Rate:                " + (NUM_SENDS * 1000 / duration) + "m/s");
    }

    private void consumeMessages(AtomicLong count) throws Exception {
        ClientOptions options = new ClientOptions();
        options.id(UUID.randomUUID().toString());
        Client container = Client.create(options);
        Connection connection = container.connect(getBrokerAmqpConnectionURI().getHost(), getBrokerAmqpConnectionURI().getPort());
        Receiver consumer = connection.openReceiver(getDestinationName());

        long v;
        while ((v = count.decrementAndGet()) > 0) {
            if ((count.get() % 10000) == 0) {
                LOG.info("Received message: {}", NUM_SENDS - count.get());
            }
            assertNotNull("got message " + v, consumer.receive());
        }
        consumer.close();
    }

    private void publishMessages(AtomicLong count) throws Exception {
        ClientOptions options = new ClientOptions();
        options.id(UUID.randomUUID().toString());
        Client container = Client.create(options);
        Connection connection = container.connect(getBrokerAmqpConnectionURI().getHost(), getBrokerAmqpConnectionURI().getPort());
        Sender sender = connection.openSender(getDestinationName());

        while (count.getAndDecrement() > 0) {
            Message<String> message = Message.create("Hello World").durable(false);
            sender.send(message).acknowledgeFuture().get();
            if ((count.get() % 10000) == 0) {
                LOG.info("Sent message: {}", NUM_SENDS - count.get());
            }
        }
        sender.close();
        connection.close();
    }

    @Override
    protected void configureBrokerPolicies(BrokerService broker) {
        PolicyEntry policyEntry = new PolicyEntry();
        policyEntry.setPendingQueuePolicy(new VMPendingQueueMessageStoragePolicy());
        policyEntry.setPrioritizedMessages(false);
        policyEntry.setExpireMessagesPeriod(0);
        policyEntry.setEnableAudit(false);
        policyEntry.setOptimizedDispatch(true);
        policyEntry.setQueuePrefetch(1); // ensure no contention on add with
                                         // matched producer/consumer

        PolicyMap policyMap = new PolicyMap();
        policyMap.setDefaultEntry(policyEntry);
        broker.setDestinationPolicy(policyMap);
    }

    @Override
    protected boolean isForceAsyncSends() {
        return false;
    }

    @Override
    protected boolean isForceSyncSends() {
        return false;
    }

    @Override
    protected String getAmqpTransformer() {
        return "jms";
    }

    @Override
    protected boolean isForceAsyncAcks() {
        return true;
    }

    @Override
    public String getAmqpConnectionURIOptions() {
        return "jms.presettlePolicy.presettleAll=false";
    }

    @Override
    protected int getSocketBufferSize() {
        return socketBuffer;
    }

    @Override
    protected int getIOBufferSize() {
        return ioBuffer;
    }
}
