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
package org.messaginghub.amqperative.transport;

import org.messaginghub.amqperative.SslOptions;
import org.messaginghub.amqperative.TransportOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class NettyBlackHoleServer extends NettyServer {

    private static final Logger LOG = LoggerFactory.getLogger(NettyBlackHoleServer.class);

    public NettyBlackHoleServer(TransportOptions options, SslOptions sslOptions) {
        super(options, sslOptions);
    }

    public NettyBlackHoleServer(TransportOptions options, SslOptions sslOptions, boolean needClientAuth) {
        super(options, sslOptions, needClientAuth);
    }

    @Override
    protected ChannelHandler getServerHandler() {
        return new BlackHoleInboundHandler();
    }

    private class BlackHoleInboundHandler extends ChannelInboundHandlerAdapter  {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            LOG.trace("BlackHoleInboundHandler: Channel read, dropping: {}", msg);
        }
    }
}