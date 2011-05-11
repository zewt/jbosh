/*
 * Copyright 2009 Guenther Niess
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
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Vector;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

import android.util.Log;

/**
 * Implementation of the {@code HTTPSender} interface which uses InternalHTTPConnection.
 */
final class HTTPSenderInternal implements HTTPSender {
    /** Value to use for the ACCEPT_ENCODING header. */
    private final String ACCEPT_ENCODING_VAL =
            ZLIBCodec.getID() + ", " + GZIPCodec.getID();

    /** Session configuration. */
    private BOSHClientConfig cfg;

    class ActiveConnection {
        InternalHTTPConnection connection;
        HashSet<InternalHTTPResponse> responses = new HashSet<InternalHTTPResponse>(); 
    };
    Vector<ActiveConnection> connections = new Vector<ActiveConnection>();

    LinkedList<InternalHTTPResponse> requestQueue = new LinkedList<InternalHTTPResponse>();
    
    /** If true, the server supports keep-alive connections; if false, it responded with
     * Connection: close.  If null, we havn't received a response yet, so we don't know. */
    private Boolean supportsKeepAlive = null;
    
    HTTPSenderInternal() {
    }

    public void init(final BOSHClientConfig session) {
        synchronized(this) {
            cfg = session;
        }
    }

    public synchronized void destroy() {
        android.util.Log.v("FOO", "XMPPSenderInternal: destroy");
        
        Vector<InternalHTTPResponse> connectionsToDestroy = new Vector<InternalHTTPResponse>();
        synchronized(this) {
            if(cfg == null)
                return;

            requestQueue.clear();
            for(ActiveConnection conn: connections) {
                connectionsToDestroy.addAll(conn.responses);
                conn.responses.clear();
            }
        }
        
        for(InternalHTTPResponse conn: connectionsToDestroy) {
            android.util.Log.v("FOO", "XMPPSenderInternal: destroy: aborting a connection");
            conn.abort();
        }
        cfg = null;
    }

    public synchronized HTTPResponse send(
            final CMSessionParams params,
            final AbstractBody body) {
        byte[] data;
        try {
            data = body.toXML().getBytes("UTF-8");
        } catch(UnsupportedEncodingException e) { throw new RuntimeException(e); }

        String encoding = null;
        if (cfg.isCompressionEnabled() && params != null) {
            AttrAccept accept = params.getAccept();
            if (accept != null) {
                if (accept.isAccepted(ZLIBCodec.getID())) {
                    encoding = ZLIBCodec.getID();
                    try {
                        data = ZLIBCodec.encode(data);
                    } catch (IOException e) { throw new RuntimeException(e); }
                } else if (accept.isAccepted(GZIPCodec.getID())) {
                    encoding = GZIPCodec.getID();
                    try {
                        data = GZIPCodec.encode(data);
                    } catch (IOException e) { throw new RuntimeException(e); }
                }
            }
        }

        // Set HTTP headers for this request.  We don't send User-Agent; BOSH itself has no
        // analogue to it, so it's not actually losing data, and it increases the size of every
        // request.  Likewise, we don't send Content-Type to reduce request size; servers
        // ignore it anyway.
        HashMap<String, String> headers = new HashMap<String, String>();
        headers.put("Host", cfg.getURI().getHost());
        if (encoding != null)
            headers.put("Content-Encoding", encoding);
        if (cfg.isCompressionEnabled())
            headers.put("Accept-Encoding", ACCEPT_ENCODING_VAL);
        headers.put("Content-Length", String.valueOf(data.length));
        
        // Construct the HTTP request header.
        StringBuilder sb = new StringBuilder();
        sb.append("POST "); sb.append(cfg.getURI().getPath()); sb.append(" HTTP/1.1\r\n");
        for(Entry<String, String> e: headers.entrySet()) {
            sb.append(e.getKey()); sb.append(": "); sb.append(e.getValue()); sb.append("\r\n");
        }
        sb.append("\r\n");

        String requestHeader = sb.toString();
        byte[] requestHeaderData;
        try {
            requestHeaderData = requestHeader.getBytes("UTF-8");
        } catch(UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        
        // Combine the header and payload to make the final request.
        byte[] requestData = new byte[requestHeaderData.length + data.length];
        System.arraycopy(requestHeaderData, 0, requestData, 0, requestHeaderData.length);
        System.arraycopy(data, 0, requestData, requestHeaderData.length, data.length);

        return new InternalHTTPResponse(requestData, params);
    }

    /** The given response just completed. */
    synchronized void requestCompleted(InternalHTTPResponse response, boolean success) {
        Log.w("HTTP", "Packet completed (" + (success? "success":"fail"));

        // Remove the request from the connection's request list.
        response.conn.responses.remove(response); 
        
        // If the request failed, all other requests on the same connection have failed as well.
        if(!success) {
            HashSet<InternalHTTPResponse> responsesFailed = response.conn.responses;
            response.conn.responses = new HashSet<InternalHTTPResponse>();
            for(InternalHTTPResponse resp: responsesFailed) {
                resp.abortWithError(new BOSHException("Previous request on pipeline failed"));
            }
        }

        // If a request is queued, start it using the same connection.
        try {
            InternalHTTPResponse nextInQueue = requestQueue.removeFirst();
            Log.w("HTTP", "Starting previously queued packet");

            nextInQueue.sendRequest(response.conn);
        } catch(NoSuchElementException e) {
        }
    }

    final class InternalHTTPResponse implements HTTPResponse {
        /** The request to be sent. */
        byte[] requestData;

        /* The connection this response was sent over, or null if this request has not yet been
         * sent. */
        ActiveConnection conn;
        
        /** Exception to throw when the response data is attempted to be accessed,
         * or {@code null} if no exception should be thrown. */
        private BOSHException toThrow;

        /** The response body which was received from the server or {@code null}
         * if that has not yet happened. */
        private AbstractBody body;

        /** The HTTP response status code. */
        private int statusCode;

        private void sendRequest(ActiveConnection conn) {
            conn.connection.sendRequest(requestData);
            conn.responses.add(this);
            this.conn = conn;
        }
                
        /**
         * Create and send a new request to the upstream connection manager,
         * providing deferred access to the results to be returned.
         *
         * This is called with HTTPSenderInternal locked.
         *  
         * @param client client instance to use when sending the request
         * @param params connection manager parameters from the session creation
         *  response, or {@code null} if the session has not yet been established
         * @param request body of the client request
         */
        InternalHTTPResponse(byte[] requestData, final CMSessionParams params)
        {
            super();
            this.requestData = requestData;
            
            if(!requestQueue.isEmpty()) {
                // If requests are already being queued, then this request will be queued too.
                Log.w("HTTP", "Queueing packet because we're already queueing");
                requestQueue.addLast(this);
                return;
            }
            
            // Try sending the request to each available connection.
            Log.w("HTTP", "Attemping to send packet...");
            for(ActiveConnection conn: connections) {
                if(conn.connection.getRequestsOutstanding() > 0) {
                    // If there's already a request on this connection, and it doesn't support
                    // keepalive or we don't yet know if it does, then don't send another request
                    // on this connection yet.
                    if(supportsKeepAlive == null || !supportsKeepAlive)
                        continue;
                }
                Log.w("HTTP", "Sending packet");
                sendRequest(conn);
                return;
            }
            Log.w("HTTP", "No connection took our packet");
            
            // No connections were available to take the request.  If we're allowed to start another
            // connection, do so; otherwise queue the request for later.
            //
            // If pipelining is known to be supported, maxConnections is 1.  This is only
            // known after the first request to a server has completed.
            //
            // If pipelining is known to be not supported, maxConnections is 2.
            //
            // If we don't yet know whether pipelining is supported, maxConnections is 1 until we
            // find out.  This requires that the first request to the server always return without
            // waiting; this is guaranteed so long as the same HTTPSender class is used for a whole
            // session. 
            //
            // We place no restrictions on the number of requests that can be queued in a single
            // pipelined request.
            int maxConnections;
            if(supportsKeepAlive == null)
                maxConnections = 1; // keepalive support not yet known
            else if(supportsKeepAlive)
                maxConnections = 1; // keepalive support is available; use pipelining
            else {
                // If the server has told us how many parallel connections to make, use that value.
                // We should always have params from the first packet by now, but if we still don't
                // have it fall back on one connection.
                if(params != null)
                    maxConnections = params.getRequests().getValue();
                else
                    maxConnections = 1;
            }
            
            if(connections.size() == maxConnections) {
                // We have too many connections outstanding to make any more, so queue the request.
                Log.w("HTTP", "Queueing packet");
                requestQueue.addLast(this);
                return;
                
            }

            Log.w("HTTP", "Starting a new connection");

            ActiveConnection conn = new ActiveConnection();
            SocketFactory socketFactory = null;
            if(cfg.getURI().getScheme().equals("https")) {
                // Use the supplied SSLSocketFactory, if any.  Otherwise, use the system-provided one.
                socketFactory = cfg.getSocketFactory();
                if(socketFactory == null)
                    socketFactory = SSLSocketFactory.getDefault();
            }
            
            conn.connection = new InternalHTTPConnection(cfg.getURI(), socketFactory); 
            connections.add(conn);

            // Send the request over the connection we just created.
            sendRequest(conn);
        }

        BOSHException abortWithError(BOSHException e) {
            // Cancel the request.
            InternalHTTPConnection connectionToCancel = null;
            synchronized(this) {
                android.util.Log.w("FOO", "HTTPSender abortWithError " + (conn.connection != null? "set":"null"));
                connectionToCancel = conn.connection;
                conn.connection = null;
            }
            
            if(connectionToCancel != null)
                connectionToCancel.abort();

            body = null;
            toThrow = e;
            requestCompleted(this, false);
            android.util.Log.w("FOO", "HTTPSender abortWithError done");
            return toThrow;
        }

        /** Abort the client transmission and response processing. */
        public void abort() {
            android.util.Log.v("FOO", "HTTPSender abort()");
            abortWithError(new BOSHException("HTTP request aborted"));
        }

        /**
         * Wait for and then return the response body.
         *
         * @return body of the response
         * @throws InterruptedException if interrupted while awaiting the response
         * @throws BOSHException on communication failure
         */
        public AbstractBody getBody() throws InterruptedException, BOSHException {
            if (toThrow != null)
                throw toThrow;
            awaitResponse();
            return body;
        }

        /**
         * Wait for and then return the response HTTP status code.
         *
         * @return HTTP status code of the response
         * @throws InterruptedException if interrupted while awaiting the response
         * @throws BOSHException on communication failure
         */
        public int getHTTPStatus() throws InterruptedException, BOSHException {
            if (toThrow != null)
                throw toThrow;
            awaitResponse();
            return statusCode;
        }

        ///////////////////////////////////////////////////////////////////////////
        // Package-private methods:

        /**
         * Await the response, storing the result in the instance variables of
         * this class when they arrive.
         *
         * @throws InterruptedException if interrupted while awaiting the response
         * @throws BOSHException on communication failure
         */
        private void awaitResponse() throws BOSHException {
            /* Synchronize to take a reference to the connection.  If another
             * thread calls abort() it may abort the connection and set
             * conn.connection to null while we're using it. */
            InternalHTTPConnection connection;
            synchronized(this) {
                if(toThrow != null)
                    throw toThrow;
                if(body != null)
                    return;

                connection = conn.connection;
            }                    

            // At this point, we can safely access connection's methods, which are threadsafe,
            // but we can't access conn.
            try {
                connection.waitForNextResponse();
                
                byte[] data = connection.getData();

                String encoding = connection.getResponseHeader("Content-Encoding");
                if (ZLIBCodec.getID().equalsIgnoreCase(encoding)) {
                    data = ZLIBCodec.decode(data);
                } else if (GZIPCodec.getID().equalsIgnoreCase(encoding)) {
                    data = GZIPCodec.decode(data);
                }
                String bodyData = new String(data, "UTF-8");

                body = StaticBody.fromString(bodyData);
                statusCode = connection.getStatusCode();
                
                // After a response, detect whether keepalives are supported.  Don't support keepalives
                // for HTTP/1.0 servers; there shouldn't be any, and we'd have to handle max keepalives
                // to handle it.  
                if(connection.getResponseMajorVersion() == 1 && connection.getResponseMinorVersion() == 0)
                    supportsKeepAlive = false;
                else if(connection.getResponseHeader("Connection").equalsIgnoreCase("close"))
                    supportsKeepAlive = false;
                else
                    supportsKeepAlive = true;
            } catch (IOException e) {
                throw abortWithError(new BOSHException("Could not obtain response", e));
            }

            // Tell HTTPSenderInternal that we're done.  Don't call this with this object locked. 
            requestCompleted(this, true);
        }
    }
}
