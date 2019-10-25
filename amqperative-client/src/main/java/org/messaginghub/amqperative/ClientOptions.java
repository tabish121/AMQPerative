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
package org.messaginghub.amqperative;

/**
 * Container Options for customizing the behavior of the Container
 */
public class ClientOptions {

    private String containerId;

    public ClientOptions() {}

    public ClientOptions(ClientOptions options) {
        if (options != null) {
            options.copyInto(this);
        }
    }

    /**
     * @return the ID configured the Container
     */
    public String containerId() {
        return containerId;
    }

    /**
     * Sets the container ID that should be used when creating Connections
     *
     * @param containerId
     *      The container Id that should be assigned to container connections.
     *
     * @return this options class for chaining.
     */
    public ClientOptions containerId(String containerId) {
        this.containerId = containerId;
        return this;
    }

    /**
     * Copy all options from this {@link ClientOptions} instance into the instance
     * provided.
     *
     * @param other
     *      the target of this copy operation.
     *
     * @return this options class for chaining.
     */
    public ClientOptions copyInto(ClientOptions other) {
        other.containerId(containerId);
        return this;
    }
}
