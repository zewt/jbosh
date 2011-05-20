/*
 * Copyright 2011 Glenn Maynard
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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import org.junit.Test;

public class TestPipelining extends AbstractBOSHTest {
    @Test(timeout=5000)
    public void testSendWindow() throws Exception {
        // Send session initialization.
        session.send(ComposableBody.builder().build());

        StubConnection conn = cm.awaitConnection();
        AbstractBody req = conn.getRequest().getBody();

        // This test only makes sense if BOSHClient supports pipelining, which
        // is indicated by @hold being greater than 1.
        int hold = Integer.parseInt(req.getAttribute(Attributes.HOLD));
        assertTrue("Receive windowing is not enabled", hold > 1);

        int requests = hold + 1;

        // Send a session creation request acknowledging the higher hold size.
        AbstractBody scr = getSessionCreationResponse(req)
                .setAttribute(Attributes.HOLD, new Integer(hold).toString())
                .setAttribute(Attributes.REQUESTS, new Integer(requests).toString())
                .build();
        conn.sendResponse(scr);

        // We should be able to pipeline up to "requests" requests without blocking.
        for(int i = 0; i < requests; ++i)
            session.send(ComposableBody.builder().build());

        // The CM should receive all of the requests, without having to respond to
        // any of them.
        int expectedResponses = requests;
        while(expectedResponses > 0) {
            cm.awaitConnection();
            --expectedResponses;
        }
    }

    /**
     * If 'hold' is set to a value greater than 1, the client can hold more
     * than one connection idle at a time.  This increases the number of
     * responses the server can send to the client without waiting for a
     * new empty request.  This is analogous to the receive window for TCP.
     */
    @Test
    public void testReceiveWindow() throws Exception {
        // Send session initialization.
        session.send(ComposableBody.builder().build());

        StubConnection conn = cm.awaitConnection();
        AbstractBody req = conn.getRequest().getBody();

        // This test only makes sense if BOSHClient supports pipelining, which
        // is indicated by @hold being greater than 1.
        int hold = Integer.parseInt(req.getAttribute(Attributes.HOLD));
        assertTrue("Receive windowing is not enabled", hold > 1);

        // Send a session creation request acknowledging the higher hold size,
        // and setting POLLING to 0, so no limits are placed on how quickly the
        // empty requests can be sent.
        AbstractBody scr = getSessionCreationResponse(req)
                .setAttribute(Attributes.HOLD, new Integer(hold).toString())
                .setAttribute(Attributes.REQUESTS, new Integer(hold+1).toString())
                .setAttribute(Attributes.DISABLE_EMPTY_MESSAGES, null)
                .build();
        conn.sendResponse(scr);

        // Send a request; the response will be held by the CM.  This tests that
        // the total number of empty requests is enough to fill @hold, even when
        // existing requests are already waiting.
        session.send(ComposableBody.builder().build());

        // Give BOSHClient a chance to send the empty requests.
        Thread.sleep(250);

        // 'hold' requests should now be waiting.
        assertEquals(hold, cm.pendingConnectionCount());
    }

    /**
     * After an empty request is sent and responded to, verify that another
     * empty request is sent.
     */
    @Test(timeout=5000)
    public void testEmptyRequests() throws Exception {
        // Send session initialization.
        session.send(ComposableBody.builder().build());

        StubConnection conn = cm.awaitConnection();
        AbstractBody req = conn.getRequest().getBody();

        // Send a session creation request acknowledging the higher hold size,
        // and setting POLLING to 0, so no limits are placed on how quickly the
        // empty requests can be sent.
        AbstractBody scr = getSessionCreationResponse(req)
                .setAttribute(Attributes.HOLD, "1")
                .setAttribute(Attributes.DISABLE_EMPTY_MESSAGES, null)
                .build();
        conn.sendResponse(scr);

        // We should receive an empty response quickly.

        // Give BOSHClient a chance to send the empty request.
        Thread.sleep(250);

        // One requests should now be waiting.
        assertEquals(1, cm.pendingConnectionCount());

        // Read the request, and respond to it.
        conn = cm.awaitConnection();
        conn.sendResponse(ComposableBody.builder().build());

        // Another response should arrive quickly.
        Thread.sleep(250);
        assertEquals(1, cm.pendingConnectionCount());
    }

    /**
     * The POLLING value tells how quickly polling may happen: how often a
     * request will be sent when it's known that the response will not be held.
     *
     * Specifically: the polling rule in section 11 only applies to non-held
     * requests; section 12 is ambiguous but clearly seems intended to apply only
     * to polling sessions.
     *
     * Make sure that no matter how high POLLING is set, so long as we're in
     * a non-polling session (hold > 0) we always send empty requests immediately.
     * Some servers set a high POLLING value (Prosidy uses 5 seconds), so if we
     * use the polling value when not polling, everything slows to a crawl.
     */
    @Test(timeout=5000)
    public void testEmptyRequestsHighPolling() throws Exception {
        // Send session initialization.
        session.send(ComposableBody.builder().build());

        StubConnection conn = cm.awaitConnection();
        AbstractBody req = conn.getRequest().getBody();

        // This test only makes sense if BOSHClient supports pipelining, which
        // is indicated by @hold being greater than 1.
        int hold = Integer.parseInt(req.getAttribute(Attributes.HOLD));
        assertTrue("Receive windowing is not enabled", hold > 1);

        // Set a high POLLING value.  This should have no effect on non-polling
        // connections.
        AbstractBody scr = getSessionCreationResponse(req)
                .setAttribute(Attributes.HOLD, new Integer(hold).toString())
                .setAttribute(Attributes.POLLING, "9999")
                .setAttribute(Attributes.REQUESTS, new Integer(hold+1).toString())
                .setAttribute(Attributes.DISABLE_EMPTY_MESSAGES, null)
                .build();
        conn.sendResponse(scr);

        // The POLLING value should not affect sending empty requests when we're
        // not a polling session.  Empty requests should be sent immediately in
        // response to the session creation response.
        Thread.sleep(250);
        assertEquals(hold, cm.pendingConnectionCount());
    }
};
