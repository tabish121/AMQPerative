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

import java.io.OutputStream;

import org.apache.qpid.protonj2.types.messaging.Footer;

/**
 * Options class that controls various aspects of a {@link MessageOutputStream} instance.
 */
public class OutputStreamOptions {

    /**
     * Defines the default value for the complete parent send context on close option
     */
    public static final boolean DEFAULT_COMPLETE_CONTEXT_ON_CLOSE = true;

    private int streamSize;
    private boolean completeContextOnClose = DEFAULT_COMPLETE_CONTEXT_ON_CLOSE;

    /**
     * Creates a {@link OutputStreamOptions} instance with default values for all options
     */
    public OutputStreamOptions() {
    }

    /**
     * Create a {@link OutputStreamOptions} instance that copies all configuration from the given
     * {@link OutputStreamOptions} instance.
     *
     * @param options
     *      The options instance to copy all configuration values from.
     */
    public OutputStreamOptions(OutputStreamOptions options) {
        if (options != null) {
            options.copyInto(this);
        }
    }

    /**
     * Copy all options from this {@link OutputStreamOptions} instance into the instance
     * provided.
     *
     * @param other
     *      the target of this copy operation.
     *
     * @return this {@link OutputStreamOptions} class for chaining.
     */
    protected OutputStreamOptions copyInto(OutputStreamOptions other) {
        other.streamSize(streamSize);
        other.completeContextOnClose(completeContextOnClose);

        return this;
    }

    /**
     * @return the configured stream size limit for associated {@link RawOutputStream}
     */
    public int streamSize() {
        return streamSize;
    }

    /**
     * Sets the overall stream size for this associated {@link RawOutputStream} that the
     * options are applied to.
     *
     * @param streamSize
     *
     * @return this {@link OutputStreamOptions} instance.
     */
    public OutputStreamOptions streamSize(int streamSize) {
        this.streamSize = streamSize;
        return this;
    }

    /**
     * @return the whether the close of the {@link OutputStream} should complete the parent {@link SendContext}
     */
    public boolean completeContextOnClose() {
        return completeContextOnClose;
    }

    /**
     * Configures if the close of the {@link OutputStream} should result in a completion of the parent
     * {@link SendContext} (default is true).  If there is a configured stream size and the {@link OutputStream}
     * is closed the parent {@link SendContext} will always be aborted as the send would be incomplete, but the
     * close of an {@link OutputStream} may not always be the desired outcome.  In the case the user wishes to
     * add a {@link Footer} to the message transmitted by the {@link SendContext} this option should be set to
     * false and the user should complete the stream manually.
     *
     * @param completeContextOnClose
     *      Should the {@link OutputStream#close()} method complete the parent {@link SendContext}
     */
    public void completeContextOnClose(boolean completeContextOnClose) {
        this.completeContextOnClose = completeContextOnClose;
    }
}