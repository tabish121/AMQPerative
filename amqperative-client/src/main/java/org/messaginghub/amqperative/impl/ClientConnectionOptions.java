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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

import org.apache.qpid.proton4j.engine.Connection;
import org.messaginghub.amqperative.ConnectionOptions;
import org.messaginghub.amqperative.ReceiverOptions;
import org.messaginghub.amqperative.SenderOptions;
import org.messaginghub.amqperative.SessionOptions;

/**
 * Connection Options for the ProtonConnection implementation
 */
public final class ClientConnectionOptions extends ConnectionOptions {

    private final String hostname;
    private final int port;

    private SessionOptions defaultSessionOptions;
    private SenderOptions defaultSenderOptions;
    private ReceiverOptions defaultReceivernOptions;

    ClientConnectionOptions(String hostname, int port) {
        this(hostname, port, null);
    }

    ClientConnectionOptions(String hostname, int port, ConnectionOptions options) {
        super(options);

        Objects.requireNonNull(hostname);

        this.hostname = hostname;
        this.port = port;
    }

    public String getHostname() {
        return hostname;
    }

    public int getPort() {
        return port;
    }

    /**
     * @return a URI indicating the remote peer to connect to.
     */
    public URI getRemoteURI() {
        try {
            return new URI(null, null, getHostname(), getPort(), null, null, null);
        } catch (URISyntaxException uriEx) {
            throw new IllegalArgumentException("Could not create URI from provided host and port");
        }
    }

    //----- Internal support methods used by the client

    static ClientConnectionOptions getDefaultConnectionOptions(String hostname, int port) {
        ClientConnectionOptions options = new ClientConnectionOptions(hostname, port);

        // TODO - Defaults for SASL mechanisms
        options.addAllowedMechanism("PLAIN");
        options.addAllowedMechanism("ANONYMOUS");

        return options;
    }

    /*
     * Session options used when none specified by the caller creating a new session.
     */
    SessionOptions getDefaultSessionOptions() {
        SessionOptions options = defaultSessionOptions;
        if (options == null) {
            synchronized (this) {
                options = defaultSessionOptions;
                if (options == null) {
                    options = new SessionOptions();
                    options.setOpenTimeout(getOpenTimeout());
                    options.setCloseTimeout(getCloseTimeout());
                    options.setRequestTimeout(getRequestTimeout());
                    options.setSendTimeout(getSendTimeout());
                }

                defaultSessionOptions = options;
            }
        }

        return options;
    }

    /*
     * Sender options used when none specified by the caller creating a new sender.
     */
    SenderOptions getDefaultSenderOptions() {
        SenderOptions options = defaultSenderOptions;
        if (options == null) {
            synchronized (this) {
                options = defaultSenderOptions;
                if (options == null) {
                    options = new SenderOptions();
                    options.setOpenTimeout(getOpenTimeout());
                    options.setCloseTimeout(getCloseTimeout());
                    options.setRequestTimeout(getRequestTimeout());
                    options.setSendTimeout(getSendTimeout());
                }

                defaultSenderOptions = options;
            }
        }

        return options;
    }

    /*
     * Receiver options used when none specified by the caller creating a new receiver.
     */
    ReceiverOptions getDefaultReceiverOptions() {
        ReceiverOptions options = defaultReceivernOptions;
        if (options == null) {
            synchronized (this) {
                options = defaultReceivernOptions;
                if (options == null) {
                    options = new ReceiverOptions();
                    options.setOpenTimeout(getOpenTimeout());
                    options.setCloseTimeout(getCloseTimeout());
                    options.setRequestTimeout(getRequestTimeout());
                    options.setSendTimeout(getSendTimeout());
                }

                defaultReceivernOptions = options;
            }
        }

        return options;
    }

    Connection configureConnection(Connection protonConnection) {
        protonConnection.setChannelMax(getChannelMax());
        protonConnection.setMaxFrameSize(getMaxFrameSize());
        protonConnection.setHostname(getHostname());
        protonConnection.setIdleTimeout((int) getIdleTimeout());
        protonConnection.setOfferedCapabilities(ClientConversionSupport.toSymbolArray(getOfferedCapabilities()));
        protonConnection.setDesiredCapabilities(ClientConversionSupport.toSymbolArray(getDesiredCapabilities()));
        protonConnection.setProperties(ClientConversionSupport.toSymbolKeyedMap(getProperties()));

        return protonConnection;
    }
}
