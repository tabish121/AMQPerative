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
package org.messaginghub.amqperative.client;

import java.net.URI;
import java.net.URISyntaxException;

import org.messaginghub.amqperative.ConnectionOptions;

/**
 * Connection Options for the ProtonConnection implementation
 */
public class ClientConnectionOptions extends ConnectionOptions {

    private final String hostname;
    private final int port;

    private String futureType;

    // TODO - For failover the single host / port configuration is not sufficient.

    public ClientConnectionOptions(String hostname, int port) {
        this(hostname, port, null);
    }

    public ClientConnectionOptions(String hostname, int port, ConnectionOptions options) {
        this.hostname = hostname;
        this.port = port;

        if (options != null) {
            options.copyInto(this);
        }
    }

    /**
     * @return the host name that this connection should resolve and connect to.
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * @return the port on the remote that the connection should attach to.
     */
    public int getPort() {
        return port;
    }

    /**
     * @return the configure future type to use for this client connection
     */
    public String getFutureType() {
        return futureType;
    }

    /**
     * Sets the desired future type that the client connection should use when creating
     * the futures used by the API.
     *
     * @param futureType
     *      The name of the future type to use.
     *
     * @return this options object for chaining.
     */
    public ClientConnectionOptions setFutureType(String futureType) {
        this.futureType = futureType;
        return this;
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
}
