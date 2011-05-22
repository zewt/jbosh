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
import java.net.Socket;
import java.net.URI;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Vector;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Implementation of the {@code HTTPSender} interface which uses InternalHTTPConnection.
 */
final class HTTPSenderInternal implements HTTPSender {
    private static final Logger LOG =
        Logger.getLogger(HTTPSenderInternal.class.getName());

    /** Value to use for the ACCEPT_ENCODING header. */
    private final String ACCEPT_ENCODING_VAL =
            ZLIBCodec.getID() + ", " + GZIPCodec.getID();

    /** Session configuration. */
    private BOSHClientConfig cfg;

    Vector<InternalHTTPConnection<InternalHTTPResponse>> connections = new Vector<InternalHTTPConnection<InternalHTTPResponse>>();

    /** If true, the server supports keep-alive connections; if false, it responded with
     * Connection: close.  If null, we havn't received a response yet, so we don't know. */
    private Boolean supportsKeepAlive = null;

    public void init(final BOSHClientConfig session) {
        synchronized(this) {
            cfg = session;
        }
    }

    public synchronized void destroy() {
        // LOG.log(Level.WARNING, "XMPPSenderInternal: destroy");

        Vector<InternalHTTPConnection<InternalHTTPResponse>> connectionsToDestroy;
        synchronized(this) {
            if(cfg == null)
                return;

            connectionsToDestroy = connections;
            connections = null;
        }

        for(InternalHTTPConnection<InternalHTTPResponse> connection: connectionsToDestroy) {
            // LOG.log(Level.WARNING, "XMPPSenderInternal: destroy: aborting a connection");
            connection.abort();
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

        // If cfg is null, destroy() has already been called.  Return an HTTPResponse
        // that always fails.
        if (cfg == null) {
            return new HTTPResponse() {
                public void abort() { }

                public int getHTTPStatus() throws BOSHException {
                    throw new BOSHException("Connection was destroyed");
                }

                public AbstractBody getBody() throws BOSHException {
                    throw new BOSHException("Connection was destroyed");
                }
            };
        }

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

        URI uri = cfg.getURI();
        String host = uri.getHost();
        int defaultPort = 80;
        if(uri.getScheme().equalsIgnoreCase("https"))
            defaultPort = 443;
        if(uri.getPort() != defaultPort)
            host += ":" + uri.getPort();
        headers.put("Host", host);

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

        return new InternalHTTPResponse(requestData);
    }

    /** A request has completed, and the given connection is being returned. */
    synchronized void requestCompleted(
            InternalHTTPConnection<InternalHTTPResponse> connectionToRelease,
            boolean success) {
        // If we've been destroyed, do nothing.
        if(connections == null)
            return;

        // LOG.log(Level.WARNING, "Packet completed (" + (success? "success":"fail") + ")");

        if(success && connectionToRelease == null)
            throw new IllegalStateException("Connection ended successfully, but without a connection");

        // If the connection doesn't support keepalive, shut down the connection, if any.
        if(connectionToRelease != null && (supportsKeepAlive == null || !supportsKeepAlive)) {
            // LOG.log(Level.WARNING, "Connection closed on server not supporting keepalive; shutting down connection");
            connectionToRelease.abort();
            connections.remove(connectionToRelease);
            connectionToRelease = null;
        }
    }

    synchronized InternalHTTPConnection<InternalHTTPResponse> getFirstConnection() {
        if(connections.size() == 0)
            return null;
        return connections.get(0);
    }

    final class InternalHTTPResponse implements HTTPResponse, InternalHTTPRequestBase {
        /** The request to be sent. */
        byte[] requestData;

        /* The connection this response was sent over, or null if this request has not yet been
         * sent. */
        InternalHTTPConnection<InternalHTTPResponse> connection;

        /** Exception to throw when the response data is attempted to be accessed,
         * or {@code null} if no exception should be thrown. */
        private BOSHException toThrow;

        /** The response body which was received from the server or {@code null}
         * if that has not yet happened. */
        private AbstractBody body;

        /** The HTTP response status code. */
        private int statusCode;

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
        InternalHTTPResponse(byte[] requestData)
        {
            super();
            this.requestData = requestData;

            sendRequest();
        }

        /**
         * Send the request over an existing connection or create a new connection.
         */
        void sendRequest() {
            synchronized(HTTPSenderInternal.this) {
                if(connection != null)
                    throw new IllegalStateException("Request already sent");

                // If keepalive is supported, send the request immediately on the first (and
                // normally only) connection.
                //
                // Note that we place no restrictions on the number of requests that can be queued in a
                // single pipelined request.  Rate-limiting requests is the job of the caller, since only
                // new requests are rate-limited and not retransmissions (see XEP-0124 11 Overactivity).
                if(supportsKeepAlive != null && supportsKeepAlive) {
                    connection = getFirstConnection();
                    if(connection != null) {
                        // LOG.log(Level.WARNING, "Sending packet over keepalive");
                        connection.sendRequest(requestData, this);
                        return;
                    }
                    // LOG.log(Level.WARNING, "No connection took our packet");
                }

                SSLConnector sslConnector = cfg.getSSLConnector();
                if(sslConnector == null) {
                    final SSLContext sslContext = cfg.getSSLContext();
                    if(sslContext != null) {
                        sslConnector = new SSLConnector() {
                            public SSLSocket attachSSLConnection(Socket socket, String host, int port) throws IOException {
                                return (SSLSocket) sslContext.getSocketFactory().createSocket(socket, host, port, true);
                            }
                        };
                    } else {
                        sslConnector = SSLConnector.getDefault();
                    }
                }

                // Creating the InternalHTTPConnection will never block, so this is safe to call
                // while synchronized.
                connection = new InternalHTTPConnection<InternalHTTPResponse>(cfg.getURI(), cfg.getSocketFactory(), sslConnector);
                connections.add(connection);

                // Send the request over the connection we just created.
                connection.sendRequest(requestData, this);

                // Notify any blocking awaitResponse call that the connection is available.
                HTTPSenderInternal.this.notifyAll();
            }
        }

        BOSHException abortWithError(BOSHException e) {
            if(e == null)
                throw new IllegalArgumentException("e must not be null");

            // Cancel the request.
            InternalHTTPConnection<InternalHTTPResponse> connectionToCancel = null;
            synchronized(HTTPSenderInternal.this) {
                // Stop if we're already cancelled.
                if(toThrow != null)
                    return toThrow;
                toThrow = e;

                // LOG.log(Level.WARNING, "HTTPSender abortWithError " + (connection != null? "set":"null"));
                connectionToCancel = connection;
                connection = null;
            }

            // Shut down the connection.  This will send requestAborted to any
            // other pipelined requests on this connection.  XXX: test
            if(connectionToCancel != null)
                connectionToCancel.abort();

            requestCompleted(connectionToCancel, false);
            // LOG.log(Level.WARNING, "HTTPSender abortWithError done");
            return toThrow;
        }

        /** Abort the client transmission and response processing. */
        public void abort() {
            // LOG.log(Level.WARNING, "HTTPSender abort()");
            abortWithError(new BOSHException("HTTP request aborted"));
        }

        /**
         * When abort() aborts a connection, this method is called on all requests
         * using the connection, including the request initially aborted.
         */
        public void requestAborted() {
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
            // Synchronize to take a reference to the connection.  If another
            // thread calls abort() it may abort the connection and set
            // connection to null.
            InternalHTTPConnection<InternalHTTPResponse> connection;
            synchronized(HTTPSenderInternal.this) {
                if(toThrow != null)
                    throw toThrow;
                // If we already have a response, stop.

                if(body != null)
                    return;

                while(this.connection == null) {
                    try {
                        HTTPSenderInternal.this.wait();
                    } catch(InterruptedException e) {
                        throw new BOSHException("Interrupted");
                    }
                }
                connection = this.connection;
            }

            // At this point, we can safely access connection's methods, which are threadsafe,
            // but we can't access conn.
            InternalHTTPConnection.ResponseData response;
            try {
                response = connection.waitForNextResponse();
                if(response.request != this)
                    throw new RuntimeException("Received a response that wasn't for us");

                byte[] data = response.data;

                String encoding = response.getResponseHeader("Content-Encoding");
                if (ZLIBCodec.getID().equalsIgnoreCase(encoding))
                    data = ZLIBCodec.decode(data);
                else if (GZIPCodec.getID().equalsIgnoreCase(encoding))
                    data = GZIPCodec.decode(data);

                String bodyData = new String(data, "UTF-8");

                body = StaticBody.fromString(bodyData);
                statusCode = response.statusCode;
            } catch (IOException e) {
                throw abortWithError(new BOSHException("Could not obtain response", e));
            }

            // After a response, detect whether keepalives are supported.  Don't support keepalives
            // for HTTP/1.0 servers; there shouldn't be any, and we'd have to handle max keepalives
            // to handle it.
            if(response.majorVersion == 1 && response.minorVersion == 0)
                supportsKeepAlive = false;
            else if(response.getResponseHeader("Connection").equalsIgnoreCase("close"))
                supportsKeepAlive = false;
            else
                supportsKeepAlive = true;
            supportsKeepAlive = false;

            // Tell HTTPSenderInternal that we're done.  Don't call this with this object locked.
            InternalHTTPConnection<InternalHTTPResponse> finishedConnection = null;
            synchronized(HTTPSenderInternal.this) {
                finishedConnection = connection;
                connection = null;
            }
            requestCompleted(finishedConnection, true);
        }
    }
}
