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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

import javax.net.ServerSocketFactory;

public class HttpServer {
    interface IHttpRequestHandler {
        void onRequest(HttpExchange exchange);
    }
    
    static class HttpExchange {
        final HttpServer.HttpRequest request;
        final EstablishedConnection conn;

        HttpExchange(EstablishedConnection conn, HttpRequest request) {
            this.conn = conn;
            this.request = request;
        }

        HttpRequest getRequest() { return request; }
        void send(HttpResponse response) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append(response.headers.protocol);
            sb.append(" ");
            sb.append(Integer.toString(response.headers.status));
            sb.append("\r\n");

            for(String headerName: response.headers.headerData.keySet()) {
                String data = response.headers.getHeader(headerName);
                sb.append(headerName);
                sb.append(": ");
                sb.append(data);
                sb.append("\r\n");
            }
            if(response.headers.getHeader("Content-Length") == null) {
                sb.append("Content-Length");
                sb.append(": ");
                sb.append(response.data.length);
                sb.append("\r\n");
            }

            sb.append("\r\n");
            OutputStream output = conn.socket.getOutputStream();
            output.write(sb.toString().getBytes());
            output.write(response.data);
            output.flush();
        }

        void destroy() {
            conn.close();
        }
    }

    static class HttpRequest {
        String method;
        HashMap<String, String> headers = new HashMap<String, String>();
        byte[] body;

        Collection<String> getHeaderNames() {
            return headers.keySet();
        }
        
        String getHeader(String name) {
            return headers.get(name.toLowerCase());
        } 
    };
    
    static class HttpResponse {
        final HttpResponseHeader headers;
        final byte[] data;

        HttpResponse(HttpResponseHeader headers, byte[] data) {
            this.headers = headers;
            this.data = data;
        }
    }

    static class HttpResponseHeader {
        int status;
        int contentLength = 0;
        String protocol = "HTTP/1.1";
        HashMap<String, String> headerData = new HashMap<String, String>();
        
        HttpResponseHeader(int status) {
            this.status = status;
        }
        int getStatus() { return status; }

        void setProtocol(String protocol) { this.protocol = protocol; }
        void setContentLength(int length) { contentLength = length; }
        void setHeader(String name, String data) {
            headerData.put(name.toLowerCase(), data);
        }

        String getHeader(String name) {
            name = name.toLowerCase();
            return headerData.get(name);
        } 
    }

    private final ServerSocket serverSocket;
    final IHttpRequestHandler requestHandler;
    private Thread listenThread;
    private boolean closing = false;
    private HashSet<EstablishedConnection> connections = new HashSet<EstablishedConnection>();

    HttpServer(IHttpRequestHandler handler) {
        this.requestHandler = handler;
        try {
            serverSocket = ServerSocketFactory.getDefault().createServerSocket();
            serverSocket.bind(null);
        } catch(IOException e) {
            throw new RuntimeException(e);
        }
    }

    int getLocalPort() { return serverSocket.getLocalPort(); }

    void connectionClosed(EstablishedConnection conn) {
        synchronized(this) {
            connections.remove(conn);
        }
    }

    void start() {
        if(listenThread != null)
            throw new IllegalStateException("Already started");

        listenThread = new Thread() {
            public void run() {
                while(!closing) {
                    try {
                        Socket socket = serverSocket.accept();
                        EstablishedConnection conn = new EstablishedConnection(HttpServer.this, socket);
                        synchronized(HttpServer.this) {
                            connections.add(conn);
                        }
                        conn.start();
                    } catch(IOException e) {
                        if(!closing)
                            throw new RuntimeException(e);
                    }
                }
            }
        };

        listenThread.setName("Test HTTP server (port " + getLocalPort() + ")");
        listenThread.start();
    }
    
    void close() {
        if(listenThread == null)
            throw new IllegalStateException("Not started");

        closing = true;
        try {
            serverSocket.close();
        } catch(IOException e) {
            throw new RuntimeException(e);
        }

        // Shut down the listen thread.
        try {
            listenThread.join();
        } catch(InterruptedException e) {
            throw new RuntimeException(e);
        }

        // Shut down any connections.
        HashSet<EstablishedConnection> connectionsRef;
        synchronized(this) {
            connectionsRef = connections;
            connections = new HashSet<EstablishedConnection>();
        }

        for(EstablishedConnection conn: connectionsRef)
            conn.close();
    }
}

class EstablishedConnection {
    final HttpServer server;
    final Socket socket;
    boolean closing = false;
    Thread connectionThread;
    
    EstablishedConnection(HttpServer server, Socket socket) {
        this.server = server;
        this.socket = socket;
    }

    void start() {
        connectionThread = new Thread() {
            public void run() {
                while(true) {
                    HttpServer.HttpRequest req;
                    try {
                        req = readRequest();
                    } catch(IOException e) {
                        if(closing)
                            return;
                        throw new RuntimeException(e);
                    }
                    if(req == null)
                        return;
                    HttpServer.HttpExchange exchange = new HttpServer.HttpExchange(EstablishedConnection.this, req);
                    server.requestHandler.onRequest(exchange);
                }
            }
        };
        
        connectionThread.setName("Server connection " + socket.getPort() + "->" + server.getLocalPort());
        connectionThread.start();
    }
    
    void close() {
        closing = true;

        try {
            socket.close();
        } catch(IOException e) {
            throw new RuntimeException(e);
        }

        server.connectionClosed(this);
    }
    
    private HttpServer.HttpRequest readRequest() throws IOException {
        StringBuilder headerBuilder = new StringBuilder();
        InputStream stream = socket.getInputStream();
        HttpServer.HttpRequest request = new HttpServer.HttpRequest();
        boolean firstLine = true;
        while(true) {
            int b = stream.read();
            if(b == -1) {
                if(headerBuilder.length() == 0 && firstLine)
                    return null;
                throw new RuntimeException("Unexpected EOF");
            }
            if(b == '\r')
                continue;

            if(b == '\n') {
                String headerLine = headerBuilder.toString();
                headerBuilder = new StringBuilder();

                
                if(firstLine) {
                    // Parse "POST / HTTP/1.0".
                    int separatorAt = headerLine.indexOf(" ");
                    if(separatorAt == -1)
                        throw new IOException("Illegal request method: " + headerLine);
                    request.method = headerLine.substring(0, separatorAt).toUpperCase();
                    firstLine = false;
                    continue;
                }

                if(headerLine.equals(""))
                    break;
                
                int separatorAt = headerLine.indexOf(':');
                if(separatorAt == -1)
                    throw new IOException("Unparsable header line: \"" + headerLine + "\"");
                
                String name = headerLine.substring(0, separatorAt);
                ++separatorAt;

                while(separatorAt < headerLine.length() && headerLine.charAt(separatorAt) == ' ')
                    ++separatorAt;
                
                String data = headerLine.substring(separatorAt, headerLine.length());
                request.headers.put(name.toLowerCase(), data);
                continue;
            }
            
            headerBuilder.append((char) b);
        }
        
        String contentLengthString = request.headers.get("content-length");
        if(contentLengthString == null)
            throw new IOException("Missing Content-Length header");

        int contentLength = Integer.valueOf(contentLengthString);
        request.body = new byte[contentLength];
        int bytesRead = 0;
        while(bytesRead < contentLength) {
            int gotBytes = stream.read(request.body, bytesRead, contentLength);
            if(gotBytes == -1)
                throw new IOException("Unexpected EOF");
            bytesRead += gotBytes;
        }

        return request;
    }
};
