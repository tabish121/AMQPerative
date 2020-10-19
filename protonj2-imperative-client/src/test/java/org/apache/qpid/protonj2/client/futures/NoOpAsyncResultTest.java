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
package org.apache.qpid.protonj2.client.futures;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.qpid.protonj2.client.exceptions.ClientException;
import org.junit.jupiter.api.Test;

public class NoOpAsyncResultTest {

    @Test
    public void testDefaultToComplete() {
        NoOpAsyncResult result = new NoOpAsyncResult();
        assertTrue(result.isComplete());
    }

    @Test
    public void testOnSuccess() {
        NoOpAsyncResult result = new NoOpAsyncResult();

        assertTrue(result.isComplete());
        result.complete(null);
        result.failed(new ClientException("Error"));
        assertTrue(result.isComplete());
    }

    @Test
    public void testOnFailure() {
        NoOpAsyncResult result = new NoOpAsyncResult();

        assertTrue(result.isComplete());
        result.failed(new ClientException("Error"));
        result.complete(null);
        assertTrue(result.isComplete());
    }
}
