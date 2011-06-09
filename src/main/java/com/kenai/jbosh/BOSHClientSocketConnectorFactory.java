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
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A factory for instances of {@link BOSHClientSocketConnectorFactory.SocketConnector} attached to sockets. 
 */ 
public abstract class BOSHClientSocketConnectorFactory {
    /**
     * A SocketConnector connects a socket to a host.
     */
    public static abstract class SocketConnector {
        /** Connect the attached socket to the specified host and port. */
        abstract public void connectSocket(String host, int port) throws IOException;

        /**
         * Cancel the connection.  Any ongoing or future call to connectSocket should
         * throw IOException.
         */
        abstract public void cancel();
    }

    /** Create a {@link SocketConnector} for the given {@link Socket}. */
    abstract public SocketConnector createConnector(Socket socket);

    private static final BOSHClientSocketConnectorFactory defaultConnectorFactory = new BOSHClientSocketConnectorFactory() {
        public SocketConnector createConnector(final Socket socket) {
            return new SocketConnector() {
                public void connectSocket(String host, int port) throws IOException {
                    InetSocketAddress socketAddress = new InetSocketAddress(host, port);
                    socket.connect(socketAddress);
                }

                public void cancel() {
                    try {
                        // This will stop any blocking connect() calls.  Note that this will not
                        // cancel a DNS lookup performed by new InetSocketAddress().  If that's wanted,
                        // the user must provide an implementation.
                        socket.close();
                    } catch(IOException e) {
                    }
                }
            };
        }
    };

    /** Return a default implementation, which implements socket cancellation but not DNS
     *  cancellation. */
    public static BOSHClientSocketConnectorFactory getDefault() {
        return defaultConnectorFactory;
    }
};
