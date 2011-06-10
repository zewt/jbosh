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

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;

/**
 * BOSH client configuration information.  Instances of this class contain
 * all information necessary to establish connectivity with a remote
 * connection manager.
 * <p/>
 * Instances of this class are immutable, thread-safe,
 * and can be re-used to configure multiple client session instances.
 */
public final class BOSHClientConfig {

    /**
     * Connection manager URI.
     */
    private URI uri;

    /**
     * Target domain.
     */
    private String to;

    /**
     * Client ID of this station.
     */
    private String from;

    /**
     * Default XML language.
     */
    private String lang = "en";

    /**
     * Routing information for messages sent to CM.
     */
    private String route;

    /**
     * Proxy host.
     */
    private String proxyHost;

    /**
     * Proxy port.
     */
    private int proxyPort;

    /**
     * SSL context.
     */
    private SSLContext sslContext;

    /**
     * Supplied SocketFactory for creating sockets.
     */
    private SocketFactory socketFactory;

    /**
     * Supplied SocketConnector for connecting sockets.
     */
    private BOSHClientSocketConnectorFactory socketConnectorFactory;

    /**
     * Supplied SocketFactory for creating SSL sockets.
     */
    private SSLConnector sslConnector;

    /**
     * Flag indicating that compression should be attempted, if possible.
     */
    private boolean compressionEnabled;

    /**
     * Supplied ScheduledExecutorService to use to schedule tasks.
     */
    private ScheduledExecutorService executorService;
    
    ///////////////////////////////////////////////////////////////////////////
    // Classes:

    /**
     * Class instance builder, after the builder pattern.  This allows each
     * {@code BOSHClientConfig} instance to be immutable while providing
     * flexibility when building new {@code BOSHClientConfig} instances.
     * <p/>
     * Instances of this class are <b>not</b> thread-safe.  If template-style
     * use is desired, see the {@code create(BOSHClientConfig)} method.
     */
    public static final class Builder {
        private final BOSHClientConfig config;

        /**
         * Creates a new builder instance, used to create instances of the
         * {@code BOSHClientConfig} class.
         *
         * @param cmURI URI to use to contact the connection manager
         * @param domain target domain to communicate with
         */
        private Builder(final URI cmURI, final String domain) {
            config = new BOSHClientConfig();

            config.uri = cmURI;
            config.to = domain;
        }

        /**
         * Creates a new builder instance, used to create instances of the
         * {@code BOSHClientConfig} class.
         *
         * @param cmURI URI to use to contact the connection manager
         * @param domain target domain to communicate with
         * @return builder instance
         */
        public static Builder create(final URI cmURI, final String domain) {
            if (cmURI == null) {
                throw(new IllegalArgumentException(
                        "Connection manager URI must not be null"));
            }
            if (domain == null) {
                throw(new IllegalArgumentException(
                        "Target domain must not be null"));
            }
            String scheme = cmURI.getScheme();
            if (!("http".equals(scheme) || "https".equals(scheme))) {
                throw(new IllegalArgumentException(
                        "Only 'http' and 'https' URI are allowed"));
            }
            return new Builder(cmURI, domain);
        }

        /**
         * Creates a new builder instance using the existing configuration
         * provided as a starting point.
         *
         * @param cfg configuration to copy
         * @return builder instance
         */
        private Builder(final BOSHClientConfig cfg) {
            config = new BOSHClientConfig(cfg);
        }

        public static Builder create(final BOSHClientConfig cfg) {
            return new Builder(cfg);
        }

        /**
         * Set the ID of the client station, to be forwarded to the connection
         * manager when new sessions are created.
         *
         * @param id client ID
         * @return builder instance
         */
        public Builder setFrom(final String id) {
            if (id == null) {
                throw(new IllegalArgumentException(
                        "Client ID must not be null"));
            }
            config.from = id;
            return this;
        }
        
        /**
         * Set the default language of any human-readable content within the
         * XML.
         *
         * @param lang XML language ID
         * @return builder instance
         */
        public Builder setXMLLang(final String lang) {
            if (lang == null) {
                throw(new IllegalArgumentException(
                        "Default language ID must not be null"));
            }
            config.lang = lang;
            return this;
        }

        /**
         * Sets the destination server/domain that the client should connect to.
         * Connection managers may be configured to enable sessions with more
         * that one server in different domains.  When requesting a session with
         * such a "proxy" connection manager, a client should use this method to
         * specify the server with which it wants to communicate.
         *
         * @param protocol connection protocol (e.g, "xmpp")
         * @param host host or domain to be served by the remote server.  Note
         *  that this is not necessarily the host name or domain name of the
         *  remote server.
         * @param port port number of the remote server
         * @return builder instance
         */
        public Builder setRoute(
                final String protocol,
                final String host,
                final int port) {
            if (protocol == null) {
                throw(new IllegalArgumentException("Protocol cannot be null"));
            }
            if (protocol.contains(":")) {
                throw(new IllegalArgumentException(
                        "Protocol cannot contain the ':' character"));
            }
            if (host == null) {
                throw(new IllegalArgumentException("Host cannot be null"));
            }
            if (host.contains(":")) {
                throw(new IllegalArgumentException(
                        "Host cannot contain the ':' character"));
            }
            if (port <= 0) {
                throw(new IllegalArgumentException("Port number must be > 0"));
            }
            config.route = protocol + ":" + host + ":" + port;
            return this;
        }

        /**
         * Specify the hostname and port of an HTTP proxy to connect through.
         *
         * @param hostName proxy hostname
         * @param port proxy port number
         * @return builder instance
         */
        public Builder setProxy(final String hostName, final int port) {
            if (hostName == null || hostName.length() == 0) {
                throw(new IllegalArgumentException(
                        "Proxy host name cannot be null or empty"));
            }
            if (port <= 0) {
                throw(new IllegalArgumentException(
                        "Proxy port must be > 0"));
            }
            config.proxyHost = hostName;
            config.proxyPort = port;
            return this;
        }

        /**
         * Set the SSL context to use for this session.  This can be used
         * to configure certificate-based authentication, etc..
         *
         * @deprecated Use {@link #setHTTPSSocketFactory(SocketFactory)}.
         * @param ctx SSL context
         * @return builder instance
         */
        public Builder setSSLContext(final SSLContext ctx) {
            if (ctx == null) {
                throw(new IllegalArgumentException(
                        "SSL context cannot be null"));
            }
            if (config.sslConnector != null) {
                throw(new IllegalArgumentException("SSLConnector and SSLContext can not both be set"));
            }
            config.sslContext = ctx;
            return this;
        }

        /**
         * Set the {@link SocketFactory} for HTTP connections.
         * @param factory socket factory
         * @return builder instance
         */
        public Builder setSocketFactory(final SocketFactory factory) {
            if (factory == null) {
                throw(new IllegalArgumentException("SocketFactory cannot be null"));
            }

            config.socketFactory = factory;
            return this;
        }

        /**
         * Set the {@link BOSHClientSocketConnectorFactory}.
         * @param factory socket factory
         * @return builder instance
         */
        public Builder setSocketConnectorFactory(final BOSHClientSocketConnectorFactory factory) {
            if (factory == null) {
                throw(new IllegalArgumentException("SocketFactory cannot be null"));
            }

            config.socketConnectorFactory = factory;
            return this;
        }
        
        /**
         * Set the {@link SSLConnector} for establishing HTTPS connections.
         * @param connector SSLConnector to use
         * @return builder instance
         */
        public Builder setSSLConnector(final SSLConnector connector) {
            if (connector == null) {
                throw(new IllegalArgumentException("SSLConnector cannot be null"));
            }
            if (config.sslContext != null) {
                throw(new IllegalArgumentException("SSLConnector and SSLContext can not both be set"));
            }

            config.sslConnector = connector;
            return this;
        }

        /**
         * Set whether or not compression of the underlying data stream
         * should be attempted.  By default, compression is disabled.
         *
         * @param enabled set to {@code true} if compression should be
         *  attempted when possible, {@code false} to disable compression
         * @return builder instance
         */
        public Builder setCompressionEnabled(final boolean enabled) {
            config.compressionEnabled = Boolean.valueOf(enabled);
            return this;
        }

        /**
         * Provide a custom {@link ScheduledExecutorService} to schedule threaded tasks.
         * This must have the semantics of an executor created with
         * {@link Executors#newSingleThreadScheduledExecutor}.
         */
        public Builder setExecutorService(final ScheduledExecutorService executorService) {
            config.executorService = executorService;
            return this;
        }
        
        
        /**
         * Build the immutable object instance with the current configuration.
         *
         * @return BOSHClientConfig instance
         */
        public BOSHClientConfig build() {
            return new BOSHClientConfig(config);
        }

    }

    ///////////////////////////////////////////////////////////////////////////
    // Constructor:

    private BOSHClientConfig() { }

    /**
     * Create a copy of another BOSHClientConfig.
     */
    private BOSHClientConfig(BOSHClientConfig copy) {
        uri = copy.uri;
        to = copy.to;
        from = copy.from;
        lang = copy.lang;
        route = copy.route;
        proxyHost = copy.proxyHost;
        proxyPort = copy.proxyPort;
        sslContext = copy.sslContext;
        socketFactory = copy.socketFactory;
        socketConnectorFactory = copy.socketConnectorFactory;
        sslConnector = copy.sslConnector;
        compressionEnabled = copy.compressionEnabled;
        executorService = copy.executorService;
    }

    /**
     * Get the URI to use to contact the connection manager.
     *
     * @return connection manager URI.
     */
    public URI getURI() {
        return uri;
    }

    /**
     * Get the ID of the target domain.
     *
     * @return domain id
     */
    public String getTo() {
        return to;
    }

    /**
     * Get the ID of the local client.
     *
     * @return client id, or {@code null}
     */
    public String getFrom() {
        return from;
    }

    /**
     * Get the default language of any human-readable content within the
     * XML.  Defaults to "en".
     *
     * @return XML language ID
     */
    public String getLang() {
        return lang;
    }

    /**
     * Get the routing information for messages sent to the CM.
     *
     * @return route attribute string, or {@code null} if no routing
     *  info was provided.
     */
    public String getRoute() {
        return route;
    }

    /**
     * Get the HTTP proxy host to use.
     *
     * @return proxy host, or {@code null} if no proxy information was specified
     */
    public String getProxyHost() {
        return proxyHost;
    }

    /**
     * Get the HTTP proxy port to use.
     *
     * @return proxy port, or 0 if no proxy information was specified
     */
    public int getProxyPort() {
        return proxyPort;
    }

    /**
     * Get the SSL context to use for this session.
     *
     * @return SSL context instance to use, or {@code null} if no
     *  context instance was provided.
     */
    public SSLContext getSSLContext() {
        return sslContext;
    }

    /**
     * Get the {@link SocketFactory} to use for HTTP connections.
     *
     * @return {@link SocketFactory} to use, or {@code null} if none
     *  was provided.
     */
    public SocketFactory getSocketFactory() {
        return socketFactory;
    }

    /**
     * Get the {@link BOSHClientSocketConnectorFactory} for this session.
     * 
     * @return {@link BOSHClientSocketConnectorFactory} to use, or {@code null}
     *  if none was provided.
     */
    public BOSHClientSocketConnectorFactory getSocketConnectorFactory() {
        return socketConnectorFactory;
    }
    
    /**
     * Get the {@link SSLConnector} for establishing HTTPS connections.
     *
     * @return {@link SSLConnector} to use, or {@code null} if none
     *  was provided.
     */
    public SSLConnector getSSLConnector() {
        return sslConnector;
    }

    /**
     * Determines whether or not compression of the underlying data stream
     * should be attempted/allowed.  Defaults to {@code false}.
     *
     * @return {@code true} if compression should be attempted, {@code false}
     *  if compression is disabled or was not specified
     */
    boolean isCompressionEnabled() {
        return compressionEnabled;
    }

    /**
     * @return the provided {@link ScheduledExecutorService}, or {@code null} if
     * none was provided. 
     */
    public ScheduledExecutorService getExecutorService() {
        return executorService;
    }
}
