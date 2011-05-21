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

/**
 * Interface used to represent code which can send a BOSH XML body over
 * HTTP to a connection manager.
 */
interface HTTPSender {

    /**
     * Initialize the HTTP sender instance for use with the session provided.
     * This method will be called once before use of the service instance.
     *
     * @param sessionCfg session configuration
     */
    void init(BOSHClientConfig sessionCfg);

    /**
     * Dispose of all resources used to provide the required services.  This
     * method will be called once when the service instance is no longer
     * required.
     */
    void destroy();

    /**
     * Asynchronously send the specified body.  Return a {@link HTTPResponse}
     * which may be used to await and read the result.
     *
     * @param params CM session creation response params
     * @param body request body to send
     * @return {@link HTTPResponse} used to access the response
     */
    HTTPResponse send(CMSessionParams params, AbstractBody body);
    
}
