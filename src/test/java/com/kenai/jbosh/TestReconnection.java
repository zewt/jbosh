/*
 * Copyright 2011 Glenn Maynard
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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class TestReconnection extends AbstractBOSHTest {
    class DisconnectionListener implements BOSHClientConnListener {
        AtomicReference<BOSHClientConnEvent> disconnectReceived =
            new AtomicReference<BOSHClientConnEvent>(null);

        public synchronized void connectionEvent(final BOSHClientConnEvent connEvent) {
            if(connEvent.isConnected())
                return;

            disconnectReceived.set(connEvent);
            this.notify();
        }
        public synchronized BOSHClientConnEvent waitForDisconnection() throws InterruptedException {
            while(disconnectReceived.get() == null) {
                wait();
            }
            return disconnectReceived.getAndSet(null);
        }
    };

    class RequestListener implements BOSHClientResponseListener {
        Queue<BOSHMessageEvent> eventsReceived = new LinkedList<BOSHMessageEvent>();

        public synchronized void responseReceived(final BOSHMessageEvent event) {
            eventsReceived.add(event);
            this.notify();
        }

        public synchronized BOSHMessageEvent waitForEvent() throws InterruptedException {
            while(eventsReceived.isEmpty())
                wait();
            return eventsReceived.remove();
        }
    };

    DisconnectionListener disc = new DisconnectionListener();
    RequestListener reqListener = new RequestListener();

    /**
     * Accept a connection, close it immediately, and return the RID of the
     * request that was received.
     */
    private String getRIDAndClose() throws Exception {
        StubConnection conn = cm.awaitConnection();
        String RID = conn.getRequest().getBody().getAttribute(Attributes.RID);
        conn.closeConnection();
        return RID;
    }

    /*
     * Basic reconnection test: check that reconnection works on the first packet,
     * before cmParams is set.
     */
    @Test(timeout=5000)
    public void earlyReconnectionTest() throws Exception {
        logTestStart();
        session.addBOSHClientConnListener(disc);

        // Send a packet to the CM, and receive it at the CM.
        ComposableBody packet = ComposableBody.builder().build();
        session.send(packet);
        StubConnection conn1 = cm.awaitConnection();

        // Close the connection at the CM without sending a response.
        String rid1 = conn1.getRequest().getBody().getAttribute(Attributes.RID);
        conn1.closeConnection();

        // Wait for the client to see the disconnection.
        disc.waitForDisconnection();

        // The disconnection should be recoverable.
        assertTrue(session.isRecoverableConnectionLoss());

        session.attemptReconnection();

        // Receive the retransmission.
        String rid2 = getRIDAndClose();

        assertEquals(rid1, rid2);
    }

    /**
     * Basic reconnection test: check that reconnection works on the second packet,
     * after cmParams is set.
     */
    @Test(timeout=5000)
    public void testReconnection() throws Exception {
        logTestStart();
        session.addBOSHClientConnListener(disc);

        // Open the connection.
        session.send(ComposableBody.builder().build());
        StubConnection conn = cm.awaitConnection();
        AbstractBody scr = getSessionCreationResponse(conn.getRequest().getBody()).build();
        conn.sendResponse(scr);
        session.drain();

        // Send another request.
        session.send(ComposableBody.builder().build());

        // Close the connection at the CM without sending a response.
        String rid1 = getRIDAndClose();

        // Wait for the client to see the disconnection.
        disc.waitForDisconnection();

        // The disconnection should be recoverable.
        assertTrue(session.isRecoverableConnectionLoss());

        session.attemptReconnection();

        // Receive the retransmission.
        String rid2 = getRIDAndClose();

        assertEquals(rid1, rid2);
    }

    /**
     * Verify that calling send() during a recoverable disconnection blocks until
     * the connection is reconnected.
     * <p>
     * This is testing implementation-specific behavior: attemptReconnection sends
     * an additional request.  On reconnection, the first unanswered request will
     * be resent, along with a second request to reach HOLD+1 requests.  This reaches
     * REQUESTS, so the final send() must block until at least one response is received.
     */
    @Test(timeout=5000)
    public void testReconnectionBlockingUntilReconnect() throws Exception {
        testReconnectionBlocking(false);
    }

    /**
     * Verify that calling send() during a recoverable disconnection blocks until
     * the connection is permanently disconnected, and throws an exception.
     */
    @Test(timeout=5000)
    public void testReconnectionBlockingUntilDisconnect() throws Exception {
        testReconnectionBlocking(true);
    }

    void testReconnectionBlocking(final boolean testDisconnect) throws Exception {
        logTestStart();
        session.addBOSHClientConnListener(disc);

        // Send a packet to the CM, and receive it at the CM.
        session.send(ComposableBody.builder().build());
        StubConnection conn1 = cm.awaitConnection();
        AbstractBody scr = getSessionCreationResponse(conn1.getRequest().getBody())
            .setAttribute(Attributes.HOLD, "1")
            .setAttribute(Attributes.REQUESTS, "2")
            .build();
        conn1.sendResponse(scr);
        session.drain();

        // Send another request.
        session.send(ComposableBody.builder().build());

        // Close the connection at the CM without sending a response.
        conn1 = cm.awaitConnection();
        conn1.closeConnection();

        // Wait for the client to see the disconnection.
        disc.waitForDisconnection();

        // The disconnection should be recoverable.
        assertTrue(session.isRecoverableConnectionLoss());

        // Start a thread to check for blocking.
        final AtomicBoolean finishedBlocking = new AtomicBoolean(false);
        Thread thread = new Thread() {
            public void run() {
                // Wait 250ms, and make sure that finishedBlocking is still unset.
                try {
                    // Wait 250ms to make sure the below send() call blocks.
                    Thread.sleep(250);

                    // Begin reconnection.
                    session.attemptReconnection();

                    // The send() call should continue to block, because the reconnection
                    // hasn't yet succeeded.  Wait briefly to make sure it continues to block.
                    Thread.sleep(250);

                    finishedBlocking.set(true);

                    if(testDisconnect) {
                        // Permanently disconnecting the session should wake up the blocking read().
                        session.close();
                    } else {
                        // Responding to the request should wake up the blocking send().
                        StubConnection conn = cm.awaitConnection();
                        AbstractBody body = conn.getRequest().getBody();
                        conn.sendResponse(ComposableBody.builder()
                                .setAttribute(Attributes.RID, body.getAttribute(Attributes.RID))
                                .build());
                    }
                } catch(Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        thread.setName("Blocking monitor thread");
        thread.start();

        // Attempt to send a packet.  This must block.
        ComposableBody packet = ComposableBody.builder().build();
        BOSHException exceptionThrown = null;
        try {
            session.send(packet);
        } catch(BOSHException e) {
            exceptionThrown = e;
        }

        // If finishedBlocking is false, then we woke up too soon.
        assertTrue("Expected to block", finishedBlocking.get());

        if(testDisconnect) {
            // The thread called disconnect(), which causes send() to throw an exception.
            assertNotNull("Expected an exception to be thrown", exceptionThrown);
        } else {
            // The thread reconnected the connection, so the send() call succeeds.
            assertNull("Unexpected exception", exceptionThrown);
        }
    }

    /**
     * Test that calling close() always fires a disconnection event, whether or not
     * one was already sent due to a recoverable disconnection.
     */
    @Test(timeout=5000)
    public void testDisconnectionEvents() throws Exception {
        logTestStart();
        session.addBOSHClientConnListener(disc);

        // Send a packet to the CM, and receive it at the CM.
        session.send(ComposableBody.builder().build());
        StubConnection conn1 = cm.awaitConnection();

        AbstractBody scr = getSessionCreationResponse(conn1.getRequest().getBody()).build();
        conn1.sendResponse(scr);
        session.drain();

        // Send a request, and close it on the CM without a response.
        session.send(ComposableBody.builder().build());
        cm.awaitConnection().closeConnection();

        // Wait for the client to see the disconnection.
        disc.waitForDisconnection();
        assertTrue(session.isRecoverableConnectionLoss());

        // Closing the connection will fire another disconnection event.
        session.close();
        disc.waitForDisconnection();
    }

    /**
     * Test that disconnection events always include all unsent responses.
     */
    @Test(timeout=5000)
    public void testOutstandingRequests() throws Exception {
        logTestStart();
        session.addBOSHClientConnListener(disc);

        // Open the connection.
        session.send(ComposableBody.builder().build());
        StubConnection conn = cm.awaitConnection();
        AbstractBody scr = getSessionCreationResponse(conn.getRequest().getBody()).build();
        conn.sendResponse(scr);
        session.drain();

        // Send two requests.
        ComposableBody packet1 = ComposableBody.builder().build();
        ComposableBody packet2 = ComposableBody.builder().build();
        session.send(packet1);
        session.send(packet2);

        // Close both connections without sending a response.
        cm.awaitConnection().closeConnection();
        cm.awaitConnection().closeConnection();

        // Wait for the client to see the disconnection.
        BOSHClientConnEvent discEvent = disc.waitForDisconnection();
        assertTrue(session.isRecoverableConnectionLoss());

        // The recoverable disconnection event includes both requests.
        assertEquals(2, discEvent.getOutstandingRequests().size());

        // Unsuccessfully attempt to reconnect and wait for the next disconnection event.
        session.attemptReconnection();
        cm.awaitConnection().closeConnection();
        cm.awaitConnection().closeConnection();
        discEvent = disc.waitForDisconnection();

        // The disconnection event still includes both requests.
        assertEquals(2, discEvent.getOutstandingRequests().size());

        // Close the connection.  The resulting disconnection event will also include
        // both requests.
        session.close();
        discEvent = disc.waitForDisconnection();
        assertEquals(2, discEvent.getOutstandingRequests().size());
    }

    @Test(timeout=5000)
    public void testMultiplePacketRetransmission() throws Exception {
        logTestStart();
        session.addBOSHClientConnListener(disc);

        // Open the connection.
        session.send(ComposableBody.builder().build());
        StubConnection conn = cm.awaitConnection();
        AbstractBody scr = getSessionCreationResponse(conn.getRequest().getBody()).build();
        conn.sendResponse(scr);
        session.drain();

        // Send two requests.
        ComposableBody packet1 = ComposableBody.builder().build();
        ComposableBody packet2 = ComposableBody.builder().build();
        session.send(packet1);
        session.send(packet2);

        // Close both connections without sending a response.
        String expectedRid1 = getRIDAndClose();
        String expectedRid2 = getRIDAndClose();

        // Wait for the client to see the disconnection.
        disc.waitForDisconnection();
        assertTrue(session.isRecoverableConnectionLoss());

        // Both requests will be resent on reconnection.
        session.attemptReconnection();
        String rid1 = getRIDAndClose();
        String rid2 = getRIDAndClose();

        // The retransmissions may be received in either order, since this test doesn't
        // use pipelining.  TestPipelining tests this more thoroughly.
        if(expectedRid1.equals(rid1) && expectedRid2.equals(rid2))
            ; // OK
        else if(expectedRid1.equals(rid2) && expectedRid2.equals(rid1))
            ; // OK
        else {
            fail("Expected RID " + expectedRid1 + " and " + expectedRid2 +
                    ", got " + rid1 + ", " + rid2);
        }
    }

    /**
     * Verify that outstanding requests are tracked and reported correctly during
     * recoverable errors, when request acks are enabled.
     */
    @Test(timeout=5000)
    public void testPacketRetransmissionAfterAck() throws Exception {
        logTestStart();
        session.addBOSHClientConnListener(disc);
        session.addBOSHClientResponseListener(reqListener);

        // Open the connection with ACK enabled.
        session.send(ComposableBody.builder().build());
        StubConnection conn = cm.awaitConnection();
        AbstractBody req = conn.getRequest().getBody();
        AbstractBody scr = getSessionCreationResponse(req)
            .setAttribute(Attributes.ACK, req.getAttribute(Attributes.RID))
            .build();
        conn.sendResponse(scr);
        reqListener.waitForEvent();

        // This test requires at least three concurrent requests.
        int requests = Integer.parseInt(req.getAttribute(Attributes.REQUESTS, "3"));
        assertTrue("Insufficient requests allowed for this test", requests >= 3);

        // Send three requests.
        ComposableBody packet1 = ComposableBody.builder().build();
        ComposableBody packet2 = ComposableBody.builder().build();
        ComposableBody packet3 = ComposableBody.builder().build();
        session.send(packet1);
        session.send(packet2);
        session.send(packet3);

        // Reply to the first request, acknowledging the second.
        StubConnection conn1 = cm.awaitConnection();
        StubConnection conn2 = cm.awaitConnection();
        conn1.sendResponse(ComposableBody.builder()
                .setAttribute(Attributes.SID, conn1.getRequest().getBody().getAttribute(Attributes.SID))
                .setAttribute(Attributes.ACK, conn2.getRequest().getBody().getAttribute(Attributes.RID))
                .build());

        // Close the second connection without replying to it.
        conn2.closeConnection();

        reqListener.waitForEvent();

        // Wait for the client to see the disconnection.
        BOSHClientConnEvent discEvent = disc.waitForDisconnection();
        assertTrue(session.isRecoverableConnectionLoss());

        // The recoverable disconnection event includes only the third, unacknowleded request.
        assertEquals(1, discEvent.getOutstandingRequests().size());
    }
}
