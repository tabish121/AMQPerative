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

import java.util.Map;

import org.apache.qpid.proton4j.amqp.Symbol;
import org.apache.qpid.proton4j.amqp.messaging.Accepted;
import org.apache.qpid.proton4j.amqp.messaging.Modified;
import org.apache.qpid.proton4j.amqp.messaging.Rejected;
import org.apache.qpid.proton4j.amqp.messaging.Released;
import org.apache.qpid.proton4j.amqp.transport.ErrorCondition;
import org.messaginghub.amqperative.DeliveryState;

/**
 * Client internal implementation of a DeliveryState type.
 */
public abstract class ClientDeliveryState implements DeliveryState {

    //----- Abstract methods for nested types

    /**
     * Returns the Proton version of the specific {@link org.apache.qpid.proton4j.amqp.transport.DeliveryState} that
     * this type represents.
     *
     * @return the Proton state object that this type maps to.
     */
    abstract org.apache.qpid.proton4j.amqp.transport.DeliveryState getProtonDeliveryState();

    //----- Create Delivery State from Proton instance

    static DeliveryState fromProtonType(org.apache.qpid.proton4j.amqp.transport.DeliveryState state) {
        if (state == null) {
            return null;
        }

        switch (state.getType()) {
            case Accepted:
                return ClientAccepted.getInstance();
            case Released:
                return ClientReleased.getInstance();
            case Rejected:
                return ClientReleased.fromProtonType(state);
            case Modified:
                return ClientModified.fromProtonType(state);
            case Transactional:
                // TODO - Currently don't support transactions in the API
            default:
                throw new IllegalArgumentException("Cannot map to unknown Proton Delivery State type");
        }
    }

    //----- Delivery State implementations

    public static class ClientAccepted extends ClientDeliveryState {

        private static final ClientAccepted INSTANCE = new ClientAccepted();

        @Override
        public Type getType() {
            return Type.ACCEPTED;
        }

        @Override
        org.apache.qpid.proton4j.amqp.transport.DeliveryState getProtonDeliveryState() {
            return Accepted.getInstance();
        }

        public static ClientAccepted getInstance() {
            return INSTANCE;
        }
    }

    public static class ClientReleased extends ClientDeliveryState {

        private static final ClientReleased INSTANCE = new ClientReleased();

        @Override
        public Type getType() {
            return Type.RELEASED;
        }

        @Override
        org.apache.qpid.proton4j.amqp.transport.DeliveryState getProtonDeliveryState() {
            return Released.getInstance();
        }

        public static ClientReleased getInstance() {
            return INSTANCE;
        }
    }

    public static class ClientRejected extends ClientDeliveryState {

        private Rejected rejected = new Rejected();

        public ClientRejected(String condition, String description) {
            if (condition != null || description != null) {
                rejected.setError(new ErrorCondition(Symbol.valueOf(condition), description));
            }
        }

        public ClientRejected(String condition, String description, Map<String, Object> info) {
            if (condition != null || description != null) {
                rejected.setError(new ErrorCondition(
                    Symbol.valueOf(condition), description, ClientConversionSupport.toSymbolKeyedMap(info)));
            }
        }

        @Override
        public Type getType() {
            return Type.RELEASED;
        }

        @Override
        org.apache.qpid.proton4j.amqp.transport.DeliveryState getProtonDeliveryState() {
            return rejected;
        }
    }

    public static class ClientModified extends ClientDeliveryState {

        private Modified modified = new Modified();

        public ClientModified(boolean failed, boolean undeliverable) {
            modified.setDeliveryFailed(failed);
            modified.setUndeliverableHere(undeliverable);
        }

        public ClientModified(boolean failed, boolean undeliverable, Map<String, Object> annotations) {
            modified.setDeliveryFailed(failed);
            modified.setUndeliverableHere(undeliverable);
            modified.setMessageAnnotations(ClientConversionSupport.toSymbolKeyedMap(annotations));
        }

        @Override
        public Type getType() {
            return Type.RELEASED;
        }

        @Override
        org.apache.qpid.proton4j.amqp.transport.DeliveryState getProtonDeliveryState() {
            return modified;
        }
    }
}