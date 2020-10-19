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
package org.apache.qpid.protonj2.client.examples;

import org.apache.qpid.protonj2.client.Client;
import org.apache.qpid.protonj2.client.Connection;
import org.apache.qpid.protonj2.client.Delivery;
import org.apache.qpid.protonj2.client.Message;
import org.apache.qpid.protonj2.client.Receiver;

public class Receive {

    public static void main(String[] argv) throws Exception {
        String serverHost = "localhost";
        int serverPort = 5672;
        String address = "examples";
        int count = 100;

        Client client = Client.create();

        try (Connection connection = client.connect(serverHost, serverPort)) {
            Receiver receiver = connection.openReceiver(address);

            for (int i = 0; i < count; ++i) {
                Delivery delivery = receiver.receive();
                Message<String> message = delivery.message();
                System.out.println("Received message with body: " + message.body());
            }
        }
    }
}
