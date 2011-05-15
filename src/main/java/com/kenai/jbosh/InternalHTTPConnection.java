package com.kenai.jbosh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.SocketFactory;

import com.kenai.jbosh.HTTPSenderInternal.InternalHTTPResponse;


/**
 * A simple HTTP implementation.
 * <p>
 * Supports sending nonblocking requests, blocking responses, and asynchronous
 * socket opening.
 * <p>
 * XXX: We don't suppory proxies.  That can be implemented with a SocketFactory.
 * There are Apache-licensed implementations for SOCKS4, SOCKS5 and HTTP proxies here:
 * http://svn.igniterealtime.org/svn/repos/smack/trunk/source/org/jivesoftware/smack/proxy/
 * I'd recommend leaving that to the caller and removing proxy support at this level;
 * if the caller supports direct XMPP he's likely to have his own proxy-handling code anyway,
 * so it would result in duplicate code.  
 */
class InternalHTTPConnection<T extends InternalHTTPRequestBase> {
   
    private static final Logger LOG =
        Logger.getLogger(InternalHTTPConnection.class.getName());

    NonBlockingSocket socket;

    private Queue<T> outstandingRequests = new LinkedList<T>();

    /** The maximum size of header data. This should be large enough to hold any
     * reasonable HTTP response header. */
    final int maxHeaderSize = 1024*16;

    /* inputBuffer[0,inputBufferAvail) contains buffered data read from the socket. */
    private byte[] inputBuffer = new byte[maxHeaderSize];
    private int inputBufferAvail = 0;
    private int inputBufferPosition = 0;

    /* All response* fields are only set when a response is received. */ 
    private HashMap<String, String> responseHeaders = null;
    private byte[] responseData = null;
    private Integer responseStatusCode = null;
    private int responseMajorVersion;
    private int responseMinorVersion; 

    /** Prepare to connect to the scheme, host and port specified in uri.  The
     * other fields of uri are unused. */
    InternalHTTPConnection(URI uri, SocketFactory factory) {
        if(factory == null)
            factory = SocketFactory.getDefault();
        socket = new NonBlockingSocket(uri, factory);
    }

    Thread thread = null;
    LinkedBlockingQueue<byte[]> queuedPackets = new LinkedBlockingQueue<byte[]>();

    /** Send request data over the connection.  This call will never block for I/O.  If an
     * error occurs, an exception will be thrown on the next call to waitForNextResponse(). */
    public void sendRequest(byte[] data, T response) {
        socket.write(data);
        synchronized(this) {
            outstandingRequests.add(response);
        }
    }

    /** Return the number of requests which have been sent with sendRequest which have
     * not yet been received by a call to waitForNextResponse. */
    public int getRequestsOutstanding() { return outstandingRequests.size(); }

    /** Permanently close the connection.  All requests are cancelled, the connection
     * is closed and all further requests will throw ChannelClosedException. */
    public synchronized void abort() {
        LOG.log(Level.WARNING, "abort()");
        socket.close();

        // If the request failed, all other requests on the same connection have failed as well.
        Queue<T> requestsFailed = outstandingRequests;
        outstandingRequests = new LinkedList<T>();
        for(T req: requestsFailed) {
            req.requestAborted();
        }

        responseHeaders = null;
        responseData = null;
        responseStatusCode = null;
    }

    /** Wait until the next response is received. */
    public T waitForNextResponse() throws IOException {
        T response;
        synchronized(this) {
            response = outstandingRequests.poll();
            if(response == null)
                throw new RuntimeException("No requests are outstanding");
        }

        readRequest();
        return response;
    }

    
    /** Return the value of a response header from the last completed request.  If the
     * header wasn't received, return "". */
    public String getResponseHeader(String key) {
        assertResponseReceived();
        key = key.toLowerCase();
        String value = responseHeaders.get(key);
        return value != null? value:"";
    }

    /** Return the body from the last completed request. */
    public byte[] getData() { assertResponseReceived(); return responseData;}
    /** Return the status code from the last completed request. */
    public int getStatusCode() { assertResponseReceived(); return responseStatusCode; }
    /** Return the major HTTP version from the last completed request. */ 
    public int getResponseMajorVersion() { assertResponseReceived(); return responseMajorVersion; }
    /** Return the minor HTTP version from the last completed request. */ 
    public int getResponseMinorVersion() { assertResponseReceived(); return responseMinorVersion; }

    /** getResponse* calls can only be made after a response has been received. */
    private void assertResponseReceived() { 
        if(responseStatusCode == null)
            throw new RuntimeException("getStatusCode called before a successful call to waitForNextResponse");
    }
    
    // This is silly: there seems to be no standard method to do direct,
    // untranslated conversions between byte[] arrays and Strings; Java defines
    // no standard charset for this, and the methods not taking a charset are
    // "unspecified"--so we have to do this manually.
    static private String makeString(byte[] array, int startPos, int length) {
        char[] chars = new char[length];
        for(int i = 0; i < length; ++i)
            chars[i] = (char) array[startPos+i];
        return new String(chars);
    }

    private boolean readDataIntoBuffer() throws IOException {
        int bytesToRead = inputBuffer.length - inputBufferAvail;
        if(bytesToRead == 0)
            return false;

        int bytesRead = socket.read(inputBuffer, inputBufferAvail, bytesToRead);
        if(bytesRead == -1)
            throw new IOException("Connection closed");

        inputBufferAvail += bytesRead;
        return true;
    }
    
    private void readRequest() throws IOException {
        int lastSearchPos = 0;
        String headers = null;
        while(true) {
            // We have a whole response header if the inputBuffer contains two consecutive
            // CRLFs.  For compatibility and ease of testing, also accept LFLF.
            for(int i = lastSearchPos; i < inputBufferAvail-3; ++i) {
                if(inputBuffer[i+0] == '\r' && inputBuffer[i+1] == '\n' &&
                   inputBuffer[i+2] == '\r' && inputBuffer[i+3] == '\n')
                {
                    // The headers ends at i, and the response body begins at i+4.
                    headers = makeString(inputBuffer, 0, i);
                    inputBufferPosition = i+4;
                    break;
                }

                if(inputBuffer[i+0] == '\n' && inputBuffer[i+1] == '\n')
                {
                    // The headers ends at i, and the response body begins at i+2.
                    headers = makeString(inputBuffer, 0, i);
                    inputBufferPosition = i+2;
                    break;
                }
            }
            if(inputBufferPosition != 0)
                break;

            // Next time we search, start from where we left off, so searching isn't O(n^2).
            lastSearchPos = inputBufferAvail > 3? inputBufferAvail-3: 0;
            
            // We need more data.  If the buffer is already full, then we've received an
            // unreasonably large HTTP response header.
            if(!readDataIntoBuffer())
                throw new IOException("Received " + inputBuffer.length + " bytes of data without finding HTTP response body");
        }

        // The HTTP body starts at bodyStartPos; we may not have the entire response
        // body.  Parse HTTP headers.
        parseResponseHeaders(headers);
                
        // See if we have a Content-Length header.
        int contentLength = -1; 
        try {
            String value = getResponseHeader("Content-Length");
            if(value.length() > 0)
                contentLength = Integer.parseInt(value);
        } catch(NumberFormatException e) {
            throw new IOException("Content-Length header received but could not be parsed");
        }
        
        // We know the amount of data in the response body; read it.
        if(contentLength != -1) {
            responseData = new byte[contentLength];
            readDataBlocking(responseData, contentLength, false);
        } else if(getResponseHeader("Transfer-Encoding").equals("chunked")) {
            responseData = readChunkedBlocking();
        } else {
            // If we don't get a length and we're not chunked, then read data until the
            // stream closes.  This is a degenerate fallback and should only happen for
            // badly broken proxies; in this mode we can't tell if a complete file is
            // received.  If this happens, force the protocol version to 1.0; this ensures
            // keepalives aren't used.
            responseMajorVersion = 1;
            responseMinorVersion = 0; 

            responseData = readUntilEOF();
        }
    }

    /** Read an entire chunked response. */
    private byte[] readChunkedBlocking() throws IOException {
        Vector<byte[]> chunks = new Vector<byte[]>();
        while(true) {
            Integer chunkStartPos = null;

            // Read data until we have a complete chunk header.
            while(true) {
                int lastSearchPos = 0;
                
                // See if we have a complete chunk-header; search for the terminating CRLF.
                for(int i = lastSearchPos; i < inputBufferAvail-2; ++i) {
                    if(inputBuffer[i+0] == '\r' && inputBuffer[i+1] == '\n')
                    {
                        // The headers ends at i, and the response body begins at i+4.
                        chunkStartPos = i+2;
                        break;
                    }
                }
    
                if(chunkStartPos != null)
                    break;
    
                // Next time we search, start from where we left off, so searching isn't O(n^2).
                lastSearchPos = inputBufferAvail > 3? inputBufferAvail-3: 0;
    
                if(!readDataIntoBuffer())
                    throw new IOException("Couldn't find chunk header");
            }
            
            inputBufferPosition = chunkStartPos;
            String chunkHeader = makeString(inputBuffer, 0, chunkStartPos-2);

            // If there's a chunk-extension, ignore it.
            int spacePos = chunkHeader.indexOf(" ");
            if(spacePos != -1)
                chunkHeader = chunkHeader.substring(0, spacePos-1);

            // Parse the chunk header.
            int chunkSize = Integer.parseInt(chunkHeader, 16);
            try {
                chunkSize = Integer.parseInt(chunkHeader, 16);
            } catch(NumberFormatException e) {
                throw new IOException("Error parsing chunk header");
            }
            
            // If the chunk size is 0, read the empty blank line following it and we're done.
            if(chunkSize == 0) {
                byte[] crlf = new byte[2];
                readDataBlocking(crlf, 2, false);
                if(crlf[0] != '\r' || crlf[1] != '\n')
                    throw new IOException("Error parsing chunk trailer");
                break;
            }
            
            // Sanity check the chunk size.
            if(chunkSize > 1024*1024)
                throw new IOException("Received an excessively large HTTP chunk: " + chunkSize + " bytes");

            // Read the chunk.
            byte[] chunk = new byte[chunkSize];
            readDataBlocking(chunk, chunkSize, false);
        }
        
        return combineChunks(chunks);
    }

    /** Read from the input stream until EOF is reached, and return the data read. */
    private byte[] readUntilEOF() throws IOException {
        Vector<byte[]> chunks = new Vector<byte[]>();
        while(true) {
            // Read the chunk.
            byte[] chunk = new byte[1024*16];
            int bytesRead = readDataBlocking(chunk, chunk.length, true);
            if(bytesRead < chunk.length) {
                byte[] partialChunk = new byte[bytesRead];
                System.arraycopy(chunk, 0, partialChunk, 0, bytesRead);
                chunks.add(partialChunk);
                break;
            }
            chunks.add(chunk);
        }
        
        return combineChunks(chunks);
    }

    /** Given a list of byte[] arrays, return their concatenation. */
    private static byte[] combineChunks(Vector<byte[]> chunks) {
        int totalSize = 0;
        for(byte[] chunk: chunks)
            totalSize += chunk.length;

        byte[] result = new byte[totalSize];
        int pos = 0;
        for(byte[] chunk: chunks) {
            System.arraycopy(chunk, 0, result, pos, chunk.length);
            pos += chunk.length;
        }
        return result;
    }
    
    /** Read up to length bytes from the read buffer into output at the specified
     *  offset.  Returns the number of bytes read. */
    private int readDataFromBuffer(byte[] output, int offset, int length) {
        if(inputBufferPosition == inputBufferAvail)
            return 0;

        int toCopy = length;
        if(toCopy > inputBufferAvail - inputBufferPosition)
            toCopy = inputBufferAvail - inputBufferPosition;

        System.arraycopy(inputBuffer, inputBufferPosition, output, offset, toCopy);
        inputBufferPosition += toCopy;
        
        // If we just emptied the buffer, reset it.
        if(inputBufferPosition == inputBufferAvail) {
            inputBufferPosition = 0;
            inputBufferAvail = 0;
        }
        
        return toCopy;
    }

    /** Read the specified amount of data from the buffer and the socket, blocking until
     * the requested number of bytes is read of EOF is reached.  If EOF is reached before
     * reading the full length, return the length read if partialAllowed is true; otherwise
     * throw an exception. */
    private int readDataBlocking(byte[] buffer, int length, boolean partialAllowed) throws IOException {
        int offset = 0;
        
        int totalBytesRead = 0;
        
        // If we have data buffered, copy it.
        int bytesRead = readDataFromBuffer(buffer, 0, length);
        offset += bytesRead;
        length -= bytesRead;
        totalBytesRead += bytesRead;
        
        // Read the remainder, if any, from the socket.
        while(length > 0) {
            bytesRead = socket.read(buffer, offset, length);
            if(bytesRead == -1)
                return totalBytesRead;

            length -= bytesRead;
            offset += bytesRead;
            totalBytesRead += bytesRead;
        }
        return totalBytesRead;
    }
    
    private void parseResponseHeaders(String headers) throws IOException {
        responseHeaders = new HashMap<String, String>();
        
        String[] lines = headers.split("\n");
        
        // We must receive the status line. 
        if(lines.length < 1)
            throw new IOException("Invalid response from server");
        
        String currentHeader = null;
        boolean firstLine = true;
        for(String line: lines) {
            // We split on \n, to tolerate both LF and CRLF inputs.  Strip off any
            // trailing CR.
            if(line.endsWith("\r"))
                line = line.substring(0, line.length()-1);
            if(firstLine) {
                // Parse the status line.  Sanity check:
                if(!line.matches("HTTP/[0-9]+\\.[0-9]+ [0-9]{3}( .*)?"))
                    throw new IOException("Received a non-HTTP response: " + line);

                int decimalPos = line.indexOf(".");
                assert(decimalPos != -1); // verified above

                int statusCodePos = line.indexOf(" ", decimalPos);
                assert(statusCodePos != -1); // verified above
                ++statusCodePos; // skip the space
                
                responseMajorVersion = Integer.parseInt(line.substring(5, decimalPos));
                responseMinorVersion = Integer.parseInt(line.substring(decimalPos+1, statusCodePos-1));
                responseStatusCode = Integer.parseInt(line.substring(statusCodePos, statusCodePos+3));

                firstLine = false;
                continue;
            }
            
            // If the line begins with whitespace, this is a continuation.
            if(line.startsWith(" ") || line.startsWith("\t")) {
                if(currentHeader == null)
                    throw new IOException("Invalid response header (first line is a continuation)");

                String existingHeader = responseHeaders.get(currentHeader);
                assert(existingHeader != null);

                responseHeaders.put(currentHeader, existingHeader + existingHeader);
                continue;
            }
            

            int separator = line.indexOf(":");
            if(separator == -1)
                throw new IOException("Invalid response header (no separator)");
            
            
            // Skip leading whitespace after the colon. 
            int dataPos = separator + 1;
            while(dataPos < line.length() && (line.charAt(dataPos) == ' ' || line.charAt(dataPos) == '\t'))
                ++dataPos;

            // Header values are case-insensitive; convert them to lowercase to normalize them.
            String key = line.substring(0, separator).toLowerCase();
            String value = line.substring(dataPos, line.length());

            // Remember the header we're parsing, so we can append continuation lines to it.
            currentHeader = key;
            
            // If we receive the same header twice, concatenate them as a comma-
            // separated string (RFC2616 sec4.2).
            String existingHeader = responseHeaders.get(key);
            if(existingHeader != null)
                responseHeaders.put(key, existingHeader + "," + value);
            else
                responseHeaders.put(key, value);
        }
    }
};


/* This class implements non-blocking opening and writing sockets, and blocking
 * socket reads. */
class NonBlockingSocket {
    private static final Logger LOG =
        Logger.getLogger(NonBlockingSocket.class.getName());

    private URI uri;
    private Thread thread;
    private IOException error;
    private Socket socket;
    private InputStream inputStream;
    private LinkedBlockingQueue<byte[]> queuedPackets = new LinkedBlockingQueue<byte[]>();
    boolean closed = false;
    
    /** Open a socket using the given factory to the specified URI.  Returns
     * immediately.  If a connection error occurs, it will be reported on the first
     * call to read(). */
    public NonBlockingSocket(URI uri, SocketFactory factory) {
        this.uri = uri;

        try {
            LOG.log(Level.WARNING, "creating socket");
            socket = factory.createSocket();
        } catch(IOException e) {
            // In the unlikely case that creating the socket itself fails, stash
            // the error and it'll be returned from the first close().
            error = e;
            closed = true;
        }
        
        Runnable r = new Runnable() {
            public void run() {
                threadMain();
            }
        };

        thread = new Thread(r);
        thread.setName("BOSH thread: " + uri.getHost() + ":" + uri.getPort());
        thread.start();
    }

    /** Write the given data to the socket.  Returns immediately.  If an error occurs,
     * it will be reported on the next call to read(). */ 
    public void write(byte[] data) {
        try {
            queuedPackets.put(data);
        } catch (InterruptedException e) {
            // Our queue isn't bounded, so put() can never block to be interrupted.
            throw new RuntimeException("Unexpected interrupt", e);
        }
    }

    /** Reads data from the socket.  Blocks until data is available.  Throws IOException
     * if an error has occurred on any previous operation. */
    public int read(byte[] inputBuffer, int inputBufferAvail, int bytesToRead) throws IOException {
        synchronized(this) {
            // If the thread hasn't finished opening the socket yet, wait for it.
            while(inputStream == null && error == null && !closed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    throw new ClosedByInterruptException();
                }
            }

            // If we're closed, error may contain a "socket closed" exception from when
            // we cancelled the connection, which should be ignored.
            if(closed)
                throw new IOException("Read from a closed connection");

            // If a previous request failed, fail all future ones as well.
            if(error != null) {
                // We don't want to lose the stack trace here, but we also want to
                // retain the stack trace where the original error happened, so nest
                // this exception even though it's the same exception type.
                IOException e = new IOException();
                e.initCause(error);
                throw error;
            }
        }

        return inputStream.read(inputBuffer, inputBufferAvail, bytesToRead);
    }

    /** Close the connection, discarding any data not yet delivered. */
    public void close() {
        LOG.log(Level.WARNING, "close()");
        synchronized(this) {
            if(closed)
                return;
            
            // Mark the stream closed immediately.  We're going to release the
            // lock below; this ensures that no new callers to read() or write()
            // will do anything.
            closed = true;

            // Wake any threads waiting in read().
            this.notifyAll();
        }

        // Closing the socket will cancel the thread if it's connecting or writing to
        // the socket.  Interrupt will which will stop the thread if it's waiting
        // on queuedPackets.take.
        LOG.log(Level.WARNING, "interrupting()");
        thread.interrupt();
        try {
            socket.close();
        } catch(IOException e) {
            // When can close() fail?  This doesn't seem to be documented.  We must be
            // able to close the socket in order to shut down the thread.
            throw new RuntimeException("Error closing thread", e); 
        }

        // Join the thread, deferring interrupts until it completes. 
        boolean interrupted = false;
        while(true) {
            try {
                LOG.log(Level.WARNING, "joining");
                thread.join();
            } catch(InterruptedException e) {
                interrupted = true;
                continue;
            }
            break;
        }

        LOG.log(Level.WARNING, "closed");
        if(interrupted)
            Thread.currentThread().interrupt();

        // The thread is closed.  If the socket is open, close it. 
        if(inputStream != null) {
            try { inputStream.close(); } catch(IOException e) { }
        }
        inputStream = null;

        // socket is already closed.
        socket = null;
        
        // Mark the thread closed.
        thread = null;
    }
    
    private void threadMain() {
        InputStream newInputStream = null;
        OutputStream outputStream = null;
        
        try {
            // Open the connection.
            LOG.log(Level.WARNING, "connecting socket");
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));
            LOG.log(Level.WARNING, "created socket");
            newInputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
        } catch(IOException e) {
            e.printStackTrace();
            LOG.log(Level.WARNING, "exception creating socket");
            if(newInputStream != null) {
                try { newInputStream.close(); } catch(IOException e2) { }
            }
            // outputStream is never opened if we get here

            synchronized(this) {
                error = e;
                this.notifyAll();
            }

            return;
        }

        synchronized(this) {
            // We've successfully opened the socket.  Synchronously update the
            // inputStream to indicate that we're ready, and wake up anyone waiting.
            inputStream = newInputStream;
            this.notifyAll();
        }
        
        try {
            while(true) {
                outputStream.write(queuedPackets.take());

                // Flush the stream if no more data is immediately available.
                if(queuedPackets.peek() == null)
                    outputStream.flush();
            }
        } catch(AsynchronousCloseException e) {
            // The thread is being closed.
        } catch(InterruptedException e) {
            // The thread is being closed.
        } catch(IOException e) {
            synchronized(this) {
                error = e;
                this.notifyAll();
            }
        } finally {
            if(outputStream != null) {
                try {
                    outputStream.close();
                } catch(IOException e) { /* ignore */ }
            }
        }
    }
}

