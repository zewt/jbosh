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
import static junit.framework.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.AsynchronousCloseException;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class InternalHTTPConnectionTest {
    public InternalHTTPConnectionTest() {} 
    
    static class Request implements InternalHTTPRequestBase {
        public void requestAborted() {
        }
    };

    
    ServerSocket serverSocket;
    InputStream serverInput;
    OutputStream serverOutput;
    URI serverURI;
    
    @Before
    public void setup() throws IOException {
        // Set up a socket listening on an arbitrary port. 
        serverSocket = ServerSocketFactory.getDefault().createServerSocket();
        serverSocket.bind(new InetSocketAddress(0));

        // Point serverURI at the socket.
        try {
            int port = serverSocket.getLocalPort();
            serverURI = new URI("http", null, "localhost", port, "/", null, null);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @After
    public void shutdown() throws IOException {
        serverSocket.close();
    }
    
    Socket serverConnection;
    private void acceptConnection() throws IOException {
        serverConnection = serverSocket.accept();
        serverInput = serverConnection.getInputStream();
        serverOutput = serverConnection.getOutputStream();
    }

    String readRequestFromClient() throws IOException {
        byte[] input = new byte[1024*16];
        int bytesRead = serverInput.read(input);
        return new String(input, 0, bytesRead, "UTF-8");
    }
    
    /**
     * Check basic InternalHTTPConnection connection and reading responses.
     */
    @Test(timeout=5000)
    public void testBasic() throws IOException {
        InternalHTTPConnection<Request> conn = new InternalHTTPConnection<Request>(serverURI, null, null, null);
        
        // Creating InternalHTTPConnection will connect asynchronously.
        acceptConnection();
        
        // Send a request.  Request data is given to InternalHTTPConnection literally.
        byte[] data = "request data".getBytes("UTF-8");
        conn.sendRequest(data, new Request());

        // Read and verify the request we just sent.
        String receivedData = readRequestFromClient();
        assertEquals(receivedData, "request data");
        
        // Send a response.
        String response =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Length: 13\r\n" + 
            "\r\n" +
            "response data";
        serverOutput.write(response.getBytes("UTF-8"));

        // Wait for the complete response.
        InternalHTTPConnection<Request>.ResponseData responseData = conn.waitForNextResponse();
        
        // Verify the response.
        String responseDataString = new String(responseData.data, "UTF-8");
        assertEquals(responseDataString, "response data");
    }

    /**
     * All errors are reported by waitForNextResponse. Verify that ConnectException
     * is thrown when a connection is refused.
     */
    @Test(timeout=5000, expected=ConnectException.class)
    public void testConnectionError() throws IOException {
        // Close the socket, since we want the request to fail.
        serverSocket.close();
        
        InternalHTTPConnection<Request> conn = new InternalHTTPConnection<Request>(serverURI, null, null, null);

        // Send a request.  The request should have failed, so this is a no-op.
        byte[] data = "request data".getBytes("UTF-8");
        conn.sendRequest(data, new Request());

        // Wait for the response.  The connection failed, so ConnectException will be
        // thrown here.
        conn.waitForNextResponse();
    }

    /**
     * Verify that AsynchronousCloseException is thrown when a connection is aborted.
     */
    @Test(timeout=5000, expected=java.net.SocketException.class)
    public void testConnectionAbortError() throws IOException {
        InternalHTTPConnection<Request> conn = new InternalHTTPConnection<Request>(serverURI, null, null, null);

        // Send a request.
        byte[] data = "request data".getBytes("UTF-8");
        conn.sendRequest(data, new Request());

        // Abort the connection.
        conn.abort();

        // Wait for the response.  The connection failed, so ConnectException will be
        // thrown here.
        conn.waitForNextResponse();
    }

    /**
     * Verify that InternalHTTPConnection deterministically joins threads when aborted.
     */
    @Test(timeout=5000)
    public void testThreadShutdown() throws IOException {
        int threadsBefore = Thread.activeCount();
        for(int i = 0; i < 50; ++i) {
            InternalHTTPConnection<Request> conn = new InternalHTTPConnection<Request>(serverURI, null, null, null);
            conn.abort();
        }
        int threadsAfter = Thread.activeCount();

        assertEquals(threadsBefore, threadsAfter);
    }

    
    /**
     * Test header parsing, including headers spanning multiple lines and repeated
     * header names.
     */
    @Test(timeout=5000)
    public void testHeaderParsing() throws IOException {
        InternalHTTPConnection<Request> conn = new InternalHTTPConnection<Request>(serverURI, null, null, null);
        
        // Creating InternalHTTPConnection will connect asynchronously.
        acceptConnection();
        
        // Send a request.  Request data is given to InternalHTTPConnection literally.
        byte[] data = "request data".getBytes("UTF-8");
        conn.sendRequest(data, new Request());

        // Read the request.
        readRequestFromClient();

        // Send a response.
        String response =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Length: 0\r\n" +
            "Test-Header: header\r\n" +
            "Test-Continued-Header: data1\r\n" +
            "    data2\r\n" +
            "Test-Combined-Header: data3\r\n" +
            "Test-Combined-Header: data4\r\n" +
            "\r\n";
        serverOutput.write(response.getBytes("UTF-8"));
        InternalHTTPConnection<Request>.ResponseData responseData = conn.waitForNextResponse();

        // Verify the parsed headers.
        assertEquals(responseData.getResponseHeader("Test-Header"), "header");
        assertEquals(responseData.getResponseHeader("Test-Continued-Header"), "data1 data2");
        assertEquals(responseData.getResponseHeader("Test-Combined-Header"), "data3,data4");
    }

    /**
     * Check reading responses with no Content-Length.
     */
    @Test(timeout=5000)
    public void testNoContentLength() throws IOException {
        InternalHTTPConnection<Request> conn = new InternalHTTPConnection<Request>(serverURI, null, null, null);
        
        // Creating InternalHTTPConnection will connect asynchronously.
        acceptConnection();
        
        // Send a request.  Request data is given to InternalHTTPConnection literally.
        byte[] data = "request data".getBytes("UTF-8");
        conn.sendRequest(data, new Request());

        // Read the request.
        readRequestFromClient();
        
        // Send a response.
        String response =
            "HTTP/1.1 200 OK\r\n" +
            "\r\n" +
            "response data";
        serverOutput.write(response.getBytes("UTF-8"));
        serverOutput.flush();
        serverOutput.close();

        // Wait for the complete response.
        InternalHTTPConnection<Request>.ResponseData responseData = conn.waitForNextResponse();
        
        // Verify the response.
        String responseDataString = new String(responseData.data, "UTF-8");
        assertEquals(responseDataString, "response data");
    }

    /**
     * Verify that a response containing a Content-Length which is closed before the
     * complete response is read throws IOException.
     */
    @Test(expected=IOException.class)
    public void testIncompleteResponse() throws IOException {
        InternalHTTPConnection<Request> conn = new InternalHTTPConnection<Request>(serverURI, null, null, null);
        
        // Creating InternalHTTPConnection will connect asynchronously.
        acceptConnection();
        
        // Send a request.  Request data is given to InternalHTTPConnection literally.
        byte[] data = "request data".getBytes("UTF-8");
        conn.sendRequest(data, new Request());

        // Read the request.
        readRequestFromClient();
        
        // Send a response.
        String response =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Length: 9999" +
            "\r\n" +
            "response data";
        serverOutput.write(response.getBytes("UTF-8"));
        serverOutput.flush();
        serverOutput.close();

        // Wait for the complete response.
        conn.waitForNextResponse();
    }

    class TestIOException extends IOException { };
    /* This SocketFactory throws an error on all methods. */
    SocketFactory errorFactory = new SocketFactory() {
        public Socket createSocket() throws IOException { throw new TestIOException(); }
        public Socket createSocket(String host, int port) throws IOException {
            throw new TestIOException();
        }
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            throw new TestIOException();
        }
        public Socket createSocket(InetAddress host, int port) throws IOException {
            throw new TestIOException();
        }
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException
        {
            throw new TestIOException();
        }
    };

    /**
     * Test error handling when socket creation fails.
     */
    @Test(timeout=5000, expected=TestIOException.class)
    public void testSocketCreationErrorHandling() throws IOException {
        InternalHTTPConnection<Request> conn = new InternalHTTPConnection<Request>(serverURI, null, errorFactory, null);
        byte[] data = "request data".getBytes("UTF-8");
        conn.sendRequest(data, new Request());

        // waitForNextResponse throws the TestIOException that was thrown by errorFactory.
        conn.waitForNextResponse();
    }

    /**
     * Test reading chunked responses.
     */
    @Test(timeout=5000)
    public void testChunked() throws IOException {
        InternalHTTPConnection<Request> conn = new InternalHTTPConnection<Request>(serverURI, null, null, null);
        
        // Creating InternalHTTPConnection will connect asynchronously.
        acceptConnection();
        
        // Send a request.  Request data is given to InternalHTTPConnection literally.
        byte[] data = "request data".getBytes("UTF-8");
        conn.sendRequest(data, new Request());

        // Read the request.
        readRequestFromClient();
        
        // Send a response.
        String response =
            "HTTP/1.1 200 OK\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "D ignored chunk extension\r\n" +
            "response data" +
            "0\r\n" +
            "\r\n";
        serverOutput.write(response.getBytes("UTF-8"));
        serverOutput.flush();
        serverOutput.close();

        // Wait for the complete response.
        InternalHTTPConnection<Request>.ResponseData responseData = conn.waitForNextResponse();
        
        // Verify the response.
        String responseDataString = new String(responseData.data, "UTF-8");
        assertEquals(responseDataString, "response data");
    }

    /**
     * Verify receiving multiple responses at once.
     */
    @Test
    public void testPipelinedReceiving() throws IOException {
        InternalHTTPConnection<Request> conn = new InternalHTTPConnection<Request>(serverURI, null, null, null);
        
        // Creating InternalHTTPConnection will connect asynchronously.
        acceptConnection();

        // Send and receive two pipelined requests.
        byte[] data = "request data".getBytes("UTF-8");
        conn.sendRequest(data, new Request());
        readRequestFromClient();
        conn.sendRequest(data, new Request());
        readRequestFromClient();
        
        // Send both responses together.
        String response1 =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Length: 10\r\n" +
            "\r\n" +
            "response 1";
        String response2 =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Length: 10\r\n" +
            "\r\n" +
            "response 2";
        String response = response1 + response2;
        serverOutput.write(response.getBytes("UTF-8"));
        serverOutput.flush();

        // Wait for each response.
        InternalHTTPConnection<Request>.ResponseData responseData1 = conn.waitForNextResponse();
        InternalHTTPConnection<Request>.ResponseData responseData2 = conn.waitForNextResponse();
        
        // Verify the responses.
        String responseDataString1 = new String(responseData1.data, "UTF-8");
        assertEquals(responseDataString1, "response 1");
        String responseDataString2 = new String(responseData2.data, "UTF-8");
        assertEquals(responseDataString2, "response 2");

        serverOutput.close();
    }
};
