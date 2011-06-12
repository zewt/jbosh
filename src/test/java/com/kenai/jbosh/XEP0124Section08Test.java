/*
 * Copyright 2009 Mike Cumings
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kenai.jbosh;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * BOSH XEP-0124 specification section 8 tests: Sending and Receiving XML
 * Payloads.
 */
public class XEP0124Section08Test extends AbstractBOSHTest {

    /*
     * Upon receipt of a request, the connection manager SHOULD forward the
     * content of the <body/> element to the server as soon as possible.
     */
    // BOSH CM functionality not supported.

    /*
     * The connection manager MUST forward the content from different requests
     * in the order specified by their 'rid' attributes.
     */
    // BOSH CM functionality not supported.

    /*
     * The connection manager MUST also return an HTTP 200 OK response with a
     * <body/> element to the client.
     */
    // BOSH CM functionality not supported.

    /*
     * However, the connection manager SHOULD NOT wait longer than the time
     * specified by the client in the 'wait' attribute of its Session Creation
     * Request.
     */
    // BOSH CM functionality not supported.

    /*
     * The connection manager SHOULD NOT keep more HTTP requests waiting at a
     * time than the number specified in the 'hold' attribute of the session
     * creation request.
     */
    // BOSH CM functionality not supported.  Test our dependancy on this.

    @Test(timeout=5000)
    public void requestTimeout() throws Exception {
        logTestStart();

        final LinkedBlockingQueue<BOSHClientConnEvent> events = new LinkedBlockingQueue<BOSHClientConnEvent>();
        
        // Session creation
        session.send(ComposableBody.builder().build());
        StubConnection conn = cm.awaitConnection();
        
        // Empty messages must be enabled, or there won't be any messages to time out.
        AbstractBody scr = getSessionCreationResponse(conn.getRequest().getBody())
                .setAttribute(Attributes.WAIT, "1")
                .setAttribute(Attributes.DISABLE_EMPTY_MESSAGES, null)
                .build();
        conn.sendResponse(scr);
        session.drain();

        // If a response is not received within about WAIT seconds, a recoverable error will
        // be received.
        BOSHClientConnListener listener = new BOSHClientConnListener()
        {
            public void connectionEvent(BOSHClientConnEvent connEvent) { events.offer(connEvent); }
        };
        session.addBOSHClientConnListener(listener);

        BOSHClientConnEvent event = events.poll(2000, TimeUnit.MILLISECONDS);
        assertNotNull("Disconnection event not received", event);
        assertTrue("Event must be an error", event.isError());
        assertTrue("Event must be a recoverable error", session.isRecoverableConnectionLoss());
    }
    
    /*
     * The connection manager MUST respond to requests in the order specified
     * by their 'rid' attributes.
     */
    // BOSH CM functionality not supported.

    /*
     * If there are no payloads waiting or ready to be delivered within the
     * waiting period, then the connection manager SHOULD include an empty
     * <body/> element in the HTTP result.
     */
    // BOSH CM functionality not supported.

    /*
     * If the connection manager has received one or more payloads from the
     * application server for delivery to the client, then it SHOULD return
     * the payloads in the body of its response as soon as possible after
     * receiving them from the server.
     */
    // BOSH CM functionality not supported.

    /*
     * The client MAY poll the connection manager for incoming payloads by
     * sending an empty <body/> element.
     */
    @Test(timeout=5000)
    public void emptyBodyWorks() throws Exception {
        logTestStart();
        // Initiate a new session
        session.send(ComposableBody.builder().build());
        StubConnection conn = cm.awaitConnection();
        AbstractBody scr = ComposableBody.builder()
                .setAttribute(Attributes.SID, "123XYZ")
                .setAttribute(Attributes.WAIT, "1")
                .build();
        conn.sendResponse(scr);

        // Simulate polling with empty body
        session.send(ComposableBody.builder().build());
        conn = cm.awaitConnection();
        conn.sendResponse(ComposableBody.builder()
                .setAttribute(Attributes.SID, "123XYZ")
                .build());
        assertValidators(scr);
    }

    /*
     * The connection manager MUST wait and respond in the same way as it does
     * after receiving payloads from the client.
     */
    // BOSH CM functionality not supported.

}
