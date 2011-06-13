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

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.kenai.jbosh.HttpServer.HttpExchange;
import com.kenai.jbosh.HttpServer.HttpResponse;
import com.kenai.jbosh.HttpServer.HttpResponseHeader;

/**
 * Request/response pair as exposed from the stub connection manager
 * implementation.
 */
public class StubConnection {

    private static final Logger LOG =
            Logger.getLogger(StubConnection.class.getName());
    private final HttpExchange exchange;
    private final AtomicReference<HttpResponse> httpResp =
            new AtomicReference<HttpResponse>();
    private final StubRequest req;
    private final AtomicReference<StubResponse> resp =
            new AtomicReference<StubResponse>();
    private boolean closeConnection = false;
    private StubCM cm;
    private String protocol = "HTTP/1.1";

    ///////////////////////////////////////////////////////////////////////////
    // Constructor:

    StubConnection(
            final StubCM cm,
            final HttpExchange exch) {
        this.cm = cm;
        req = new StubRequest(exch.getRequest());
        exchange = exch;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Public methods:

    public StubRequest getRequest() {
        return req;
    }

    public StubResponse getResponse() {
        return resp.get();
    }

    public void sendResponse(final AbstractBody respBody) throws IOException {
        sendResponseWithStatus(respBody, 200);
    }

    /** Close the connection without sending a response. */
    public void closeConnection() {
        synchronized(this) {
            closeConnection = true;
            notifyAll();
        }

        cm.notifyResponseSent(this);
    }

    public void sendResponseWithStatus(
            final AbstractBody respBody,
            final int httpStatus)
            throws IOException {
        sendResponseWithStatusAndHeaders(respBody, httpStatus, null);
    }

    /**
     * Send the response as HTTP/1.0 instead of HTTP/1.1.  This will disable keepalives
     * and pipelining.
     */
    public void forceHTTP1() {
        protocol = "HTTP/1.0";
    }

    public void sendResponseWithStatusAndHeaders(
            final AbstractBody respBody,
            final int httpStatus,
            final Map<String,String> headers)
            throws IOException {
        HttpResponseHeader respHead = new HttpResponseHeader(httpStatus);
        respHead.setProtocol(protocol);
        if (headers != null) {
            for (Map.Entry<String,String> entry : headers.entrySet()) {
                respHead.setHeader(entry.getKey(), entry.getValue());
            }
        }
        
        String bodyStr = respBody.toXML();
        byte[] data = bodyStr.getBytes("UTF-8");
        try {
            String acceptStr = req.getHeader("Accept-Encoding");
            AttrAccept accept = AttrAccept.createFromString(acceptStr);
            if (accept != null) {
                String encoding = null;
                if (accept.isAccepted(ZLIBCodec.getID())) {
                    encoding = ZLIBCodec.getID();
                    data = ZLIBCodec.encode(data);
                } else if (accept.isAccepted(GZIPCodec.getID())) {
                    encoding = GZIPCodec.getID();
                    data = GZIPCodec.encode(data);
                }
                if (encoding != null) {
                    LOG.fine("Encoding: " + encoding);
                    respHead.setHeader("Content-Encoding", encoding);
                }
            }
        } catch (BOSHException boshx) {
            LOG.log(Level.WARNING,
                    "Could not respond to Accept-Encoding", boshx);
        }
        respHead.setContentLength(data.length);

        HttpResponse response = new HttpResponse(respHead, data);
        if (!httpResp.compareAndSet(null, response)) {
            throw(new IllegalStateException("HTTP Response already sent"));
        }

        if (!resp.compareAndSet(null,
                new StubResponse(respHead, respBody))) {
            throw(new IllegalStateException("Response already sent"));
        }

        synchronized(this) {
            notifyAll();
        }
        
        cm.notifyResponseSent(this);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Package methods:

    public boolean hasResponse() {
        return resp.get() != null || closeConnection;
    }

    private void awaitResponse() {
        synchronized(this) {
            while (resp.get() == null && !closeConnection) {
                try {
                    wait();
                } catch (InterruptedException intx) {
                    // Ignore
                }
            }
        }
    }

    public void executeResponse() throws IOException {
        awaitResponse();
        HttpResponse hr = httpResp.getAndSet(null);
        if (closeConnection) {
            exchange.destroy();
            return;
        }

        if (hr == null) {
            // Already executed the response
            return;
        }
        exchange.send(hr);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Private methods:


}
