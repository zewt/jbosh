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

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.kenai.jbosh.HttpServer.HttpRequest;

/**
 * Request received by the stub connection manager.  Used to examine the
 * request for expected conditions during testing.
 */
public class StubRequest {

    private final String method;
    private final Map<String,String> headers;
    private final AbstractBody body;
    private final long requestTime = System.currentTimeMillis();

    ///////////////////////////////////////////////////////////////////////////
    // Constructor:

    StubRequest(final HttpRequest request) {
        method = request.method;

        // Create a map of the request headers
        headers = new HashMap<String,String>();

        for(String headerName: request.getHeaderNames()) {
            headers.put(headerName, request.getHeader(headerName));
        }
        
        // Read in the message body
        try {
            byte[] data = request.body;
            String encoding = request.getHeader("Content-Encoding");
            if (ZLIBCodec.getID().equalsIgnoreCase(encoding)) {
                data = ZLIBCodec.decode(data);
            } else if (GZIPCodec.getID().equalsIgnoreCase(encoding)) {
                data = GZIPCodec.decode(data);
            }
            String bodyStr = new String(data);
            body = StaticBody.fromString(bodyStr);
        } catch (IOException iox) {
            throw(new IllegalStateException("Couldn't load request body", iox));
        } catch (BOSHException boshx) {
            throw(new IllegalStateException("Couldn't load request body", boshx));
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Public methods:

    public AbstractBody getBody() {
        return body;
    }

    public String getMethod() {
        return method;
    }

    public Map<String,String> getHeaders() {
        return headers;
    }

    public long getRequestTime() {
        return requestTime;
    }

    /**
     * Case-insensitive header retrieval.
     *
     * @param name header name
     * @return value, or {@code null}
     */
    public String getHeader(final String name) {
        for (Map.Entry<String,String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

}
