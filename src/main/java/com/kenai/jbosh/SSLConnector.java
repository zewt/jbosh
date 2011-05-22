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
import java.net.Socket;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * This interface provides one method, {@link SSLConnector#attachSSLConnection}.
 */
public abstract class SSLConnector
{
    /**
     * Return an SSLSocket connected to the given socket.  This is equivalent
     * to {@link SSLSocketFactory#createSocket}(socket, host, port, true).
     */
    public abstract SSLSocket attachSSLConnection(Socket socket, String host, int port) throws IOException;

    final static SSLConnector defaultConnector = new SSLConnector() {
        public SSLSocket attachSSLConnection(Socket socket, String host, int port) throws IOException {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            return (SSLSocket) factory.createSocket(socket, host, port, true);
        }
    };

    /** Return the default SSLConnector, using SSLSocketFactory.getDefault. */
    static public SSLConnector getDefault() {
        return defaultConnector;
    }
};
