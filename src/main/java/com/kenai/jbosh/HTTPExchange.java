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
 * A request and response pair representing a single exchange with a remote
 * content manager.
 */
final class HTTPExchange {
    /**
     * Request body.
     */
    private final AbstractBody request;

    /**
     * HTTPResponse instance.
     */
    private HTTPResponse response;

    ///////////////////////////////////////////////////////////////////////////
    // Constructor:

    /**
     * Create a new request/response pair object.
     *
     * @param req request message body
     */
    HTTPExchange(final AbstractBody req, HTTPResponse resp) {
        if (req == null) {
            throw(new IllegalArgumentException("Request body cannot be null"));
        }
        if (resp == null) {
            throw(new IllegalArgumentException("Response object cannot be null"));
        }
        request = req;
        response = resp;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Package-private methods:

    /**
     * Get the original request message.
     *
     * @return request message body.
     */
    AbstractBody getRequest() {
        return request;
    }

    /**
     * Get the HTTPResponse instance.
     *
     * @return HTTPResponse instance associated with the request.
     */
    HTTPResponse getHTTPResponse() {
        return response;
    }

}
