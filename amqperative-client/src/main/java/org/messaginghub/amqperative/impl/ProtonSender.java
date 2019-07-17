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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import org.apache.qpid.proton4j.amqp.messaging.AmqpValue;
import org.apache.qpid.proton4j.buffer.ProtonBuffer;
import org.apache.qpid.proton4j.buffer.ProtonByteBufferAllocator;
import org.apache.qpid.proton4j.codec.CodecFactory;
import org.apache.qpid.proton4j.codec.Encoder;
import org.apache.qpid.proton4j.codec.EncoderState;
import org.apache.qpid.proton4j.engine.OutgoingDelivery;
import org.messaginghub.amqperative.DeliveryState;
import org.messaginghub.amqperative.Message;
import org.messaginghub.amqperative.Sender;
import org.messaginghub.amqperative.SenderOptions;
import org.messaginghub.amqperative.Tracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proton based AMQP Sender
 */
public class ProtonSender implements Sender {

    private static final Logger LOG = LoggerFactory.getLogger(ProtonSender.class);

    private CompletableFuture<Sender> openFuture = new CompletableFuture<Sender>();
    private CompletableFuture<Sender> closeFuture = new CompletableFuture<Sender>();

    private final ProtonSenderOptions options;
    private final ProtonSession session;
    private final org.apache.qpid.proton4j.engine.Sender sender;
    private final ScheduledExecutorService executor;

    public ProtonSender(SenderOptions options, ProtonSession session, org.apache.qpid.proton4j.engine.Sender sender) {
        this.options = new ProtonSenderOptions(options);
        this.session = session;
        this.sender = sender;
        this.executor = session.getScheduler();

    }

    @Override
    public Future<Sender> openFuture() {
        return openFuture;
    }

    @Override
    public Tracker send(Message message) {
        //TODO: block for credit
        //TODO: check sender.isSendable();
        ProtonMessage msg = (ProtonMessage) message;

        // TODO: implement message handling properly
        Object o = msg.getBody();
        AmqpValue body = new AmqpValue(o);

        Encoder encoder = CodecFactory.getDefaultEncoder();
        EncoderState encoderState = encoder.newEncoderState();
        ProtonBuffer buffer = ProtonByteBufferAllocator.DEFAULT.allocate(4096);

        try {
            encoder.writeObject(buffer, encoderState, body);
        } finally {
            encoderState.reset();
        }

        //TODO: this is not thread safe, at all
        OutgoingDelivery delivery = sender.next();
        delivery.setTag(new byte[] {0});
        delivery.writeBytes(buffer);

        return new Tracker() {

            @Override
            public Tracker settle() {
                // TODO Auto-generated method stub
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isRemotelySettled() {
                return delivery.isRemotelySettled();
            }

            @Override
            public byte[] getTag() {
                // TODO Auto-generated method stub
                throw new UnsupportedOperationException();
            }

            @Override
            public DeliveryState getRemoteState() {
                // TODO Auto-generated method stub
                throw new UnsupportedOperationException();
            }

            @Override
            public Message getMessage() {
                // TODO Auto-generated method stub
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public Future<Sender> close() {
        return closeFuture;
    }

    @Override
    public Future<Sender> detach() {
        return closeFuture;
    }

    @Override
    public Tracker trySend(Message message, Consumer<Tracker> onUpdated) throws IllegalStateException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Tracker send(Message message, Consumer<Tracker> onUpdated) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Tracker send(Message message, Consumer<Tracker> onUpdated, ExecutorService executor) {
        // TODO Auto-generated method stub
        return null;
    }

    //----- Internal API

    ProtonSender open() {
        executor.execute(() -> {
            sender.openHandler(result -> {
                if (result.succeeded()) {
                    openFuture.complete(this);
                    LOG.trace("Sender opened successfully");
                } else {
                    openFuture.completeExceptionally(result.error());
                    LOG.error("Sender failed to open: ", result.error());
                }
            });
            sender.open();
        });

        return this;
    }
}
