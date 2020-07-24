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
package org.apache.qpid.protonj2.client;

import java.util.concurrent.Future;

import org.apache.qpid.protonj2.client.exceptions.ClientException;

/**
 * Tracker object used to track the state of a sent {@link Message} at the remote
 * and allows for local settlement and disposition management.
 */
public interface Tracker {

    /**
     * @return the {@link Sender} that was used to send the delivery that is being tracked.
     */
    Sender sender();

    /**
     * Settles the delivery locally, if not {@link SenderOptions#autoSettle() auto-settling}.
     *
     * @return the delivery
     * @throws ClientException
     */
    Tracker settle() throws ClientException;

    /**
     * @return true if the sent message has been locally settled.
     */
    boolean settled();

    /**
     * Gets the current local state for the tracked delivery.
     *
     * @return the delivery state
     */
    DeliveryState state();

    /**
     * Gets the current remote state for the tracked delivery.
     *
     * @return the remote {@link DeliveryState} once a value is received from the remote.
     */
    DeliveryState remoteState();

    /**
     * Gets whether the delivery was settled by the remote peer yet.
     *
     * @return whether the delivery is remotely settled
     */
    boolean remoteSettled();

    /**
     * Updates the DeliveryState, and optionally settle the delivery as well.
     *
     * @param state
     *            the delivery state to apply
     * @param settle
     *            whether to {@link #settle()} the delivery at the same time
     *
     * @return itself
     * @throws ClientException
     */
    Tracker disposition(DeliveryState state, boolean settle) throws ClientException;

    /**
     * Returns a future that can be used to wait for the remote to acknowledge receipt of
     * a sent message.
     *
     * @return a {@link Future} that can be used to wait on remote acknowledgement.
     */
    Future<Tracker> acknowledgeFuture();

}