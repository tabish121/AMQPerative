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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.qpid.proton4j.amqp.messaging.Source;
import org.apache.qpid.proton4j.amqp.messaging.Target;

/**
 * Base options type for configuration of {@link Source} and {@link Target} types
 * used by {@link Sender} and {@link Receiver} end points.
 *
 * @param <E> the subclass that implements this terminus options type.
 */
public abstract class TerminusOptions<E extends TerminusOptions<E>> {

    /**
     * Control the persistence of source or target state.
     */
    public enum DurabilityMode {
        NONE,
        CONFIGURATION,
        UNSETTLED_STATE
    }

    /**
     * Control when the clock for expiration begins.
     */
    public enum ExpiryPolicy {
        LINK_CLOSE,
        SESSION_CLOSE,
        CONNECTION_CLOSE,
        NEVER
    }

    private String address;
    private DurabilityMode durabilityMode;
    private boolean dynamic;
    private Map<String, Object> dynamicNodeProperties;
    private long timeout;
    private ExpiryPolicy expiryPolicy;
    private String[] capabilities;

    abstract E self();

    /**
     * @return the address
     */
    public String getAddress() {
        return address;
    }

    /**
     * @param address
     * 		the address to set
     *
     * @return this options instance.
     */
    public E setAddress(String address) {
        this.address = address;
        return self();
    }

    /**
     * @return the durabilityMode
     */
    public DurabilityMode getDurabilityMode() {
        return durabilityMode;
    }

    /**
     * @param durabilityMode the durabilityMode to set
     *
     * @return this options instance.
     */
    public E setDurabilityMode(DurabilityMode durabilityMode) {
        this.durabilityMode = durabilityMode;
        return self();
    }

    /**
     * @return the dynamic
     */
    public boolean isDynamic() {
        return dynamic;
    }

    /**
     * @param dynamic the dynamic to set
     *
     * @return this options instance.
     */
    public E setDynamic(boolean dynamic) {
        this.dynamic = dynamic;
        return self();
    }

    /**
     * @return the dynamicNodeProperties
     */
    public Map<String, Object> getDynamicNodeProperties() {
        return dynamicNodeProperties;
    }

    /**
     * @param dynamicNodeProperties the dynamicNodeProperties to set
     *
     * @return this options instance.
     */
    public E setDynamicNodeProperties(Map<String, Object> dynamicNodeProperties) {
        this.dynamicNodeProperties = dynamicNodeProperties;
        return self();
    }

    /**
     * @return the timeout
     */
    public long getTimeout() {
        return timeout;
    }

    /**
     * @param timeout the timeout to set
     *
     * @return this options instance.
     */
    public E setTimeout(long timeout) {
        this.timeout = timeout;
        return self();
    }

    /**
     * @return the expiryPolicy
     */
    public ExpiryPolicy getExpiryPolicy() {
        return expiryPolicy;
    }

    /**
     * @param expiryPolicy the expiryPolicy to set
     *
     * @return this options instance.
     */
    public E setExpiryPolicy(ExpiryPolicy expiryPolicy) {
        this.expiryPolicy = expiryPolicy;
        return self();
    }

    /**
     * @return the capabilities
     */
    public String[] getCapabilities() {
        return capabilities;
    }

    /**
     * @param capabilities the capabilities to set
     *
     * @return this options instance.
     */
    public E setCapabilities(String[] capabilities) {
        this.capabilities = capabilities;
        return self();
    }

    protected void copyInto(TerminusOptions<E> other) {
        other.setAddress(address);
        other.setDurabilityMode(durabilityMode);
        other.setDynamic(dynamic);
        if (dynamicNodeProperties != null) {
            other.setDynamicNodeProperties(new HashMap<>(dynamicNodeProperties));
        }
        other.setTimeout(timeout);
        other.setExpiryPolicy(expiryPolicy);
        if (capabilities != null) {
            other.setCapabilities(Arrays.copyOf(capabilities, capabilities.length));
        }
    }
}