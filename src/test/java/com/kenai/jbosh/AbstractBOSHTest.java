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

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import static org.junit.Assert.*;

/**
 * BOSH XEP-0124 specification test base class.
 */
public abstract class AbstractBOSHTest {
    private static final Logger LOG =
            Logger.getLogger(AbstractBOSHTest.class.getName());
    protected static final String NS_URI = BodyQName.BOSH_NS_URI;
    protected final RequestValidator reqValidator =
            new RequestValidator();
    protected ConnectionValidator connValidator;
    protected StubCM cm;
    protected BOSHClient session;
    private final AtomicBoolean cleaningUp = new AtomicBoolean();

    @BeforeClass
    public static void hr() {
        LOG.info("\n\n\n");
    }

    @Before
    public void setup() throws Exception {
        enableAssertions();
        cleaningUp.set(false);
        connValidator = new ConnectionValidator();
        cm = new StubCM();
        cm.addStubCMListener(connValidator);
        LOG.info("========================================");
        LOG.info("Stub CM started at: " + cm.getURI().toString());
        BOSHClientConfig cfg = BOSHClientConfig.Builder
                .create(cm.getURI(), "test@domain")
                .build();
        session = createSession(cfg);
    }

    @After
    public void tearDown() throws Exception {
        cleaningUp.set(true);
        cm.dispose();
        connValidator = null;
        LOG.info("Stub CM disposed of");
        session.close();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Protected methods:

    /**
     * Shorthand to create and attach to a session.
     *
     * @param cfg configuration to use to create the session with
     * @return new session instance
     */
    protected BOSHClient createSession(final BOSHClientConfig cfg) {
        BOSHClient result = BOSHClient.create(cfg);
        result.addBOSHClientRequestListener(new BOSHClientRequestListener() {
            public void requestSent(final BOSHMessageEvent event) {
                LOG.fine("Sending request: " + event.getBody().toXML());

                // A bug somewhere deep in xsocket library causes its HTTP server to drop
                // requests if they're sent more quickly than they're accepted.  That code
                // is unmaintainable and unmaintained; it should be replaced.  For now,
                // slow down to mask the bug; it's not used by anything but the tests.
                try {
                    Thread.sleep(50);
                } catch(InterruptedException e) { /* ignore */ }
            }
        });
        result.addBOSHClientRequestListener(reqValidator);
        result.addBOSHClientResponseListener(reqValidator);
        result.addBOSHClientResponseListener(new BOSHClientResponseListener() {
            public void responseReceived(final BOSHMessageEvent event) {
                LOG.fine("Received response: " + event.getBody().toXML());
            }
        });
        result.addBOSHClientConnListener(new BOSHClientConnListener() {
            public void connectionEvent(
                    final BOSHClientConnEvent connEvent) {
                if (connEvent.isConnected()) {
                    LOG.info("Connection established");
                } else {
                    Throwable cause = connEvent.getCause();
                    if (cause == null) {
                        LOG.info("Connection closed");
                    } else {
                        if (cleaningUp.get()) {
                            LOG.fine("Connection closed on error: "
                                    + cause.getClass().getName()
                                    + " - " + cause.getMessage());
                        } else {
                            LOG.log(Level.INFO,
                                    "Connection closed on error", cause);
                        }
                    }
                }
            }
        });
        connValidator.setBOSHClient(result);
        return result;
    }

    /**
     * Given a session creation request, return the default session creation response.
     */
    ComposableBody.Builder getSessionCreationResponse(AbstractBody creationRequest) {
        // Enable DISABLE_EMPTY_MESSAGES, to prevent empty requests from being
        // sent nondeterministically during tests.  Empty request testing will
        // explicitly clear this attribute.
        String waitStr = creationRequest.getAttribute(Attributes.WAIT);
        String verStr = creationRequest.getAttribute(Attributes.VER);
        String holdStr = creationRequest.getAttribute(Attributes.HOLD);

        // BOSHClient will typically request a higher hold size than 1.  For most tests,
        // only use hold='1'.  Tests that want to allow more packets to hold will set
        // this to the desired value manually.
        return ComposableBody.builder()
            .setAttribute(Attributes.SID, "123XYZ")
            .setAttribute(Attributes.WAIT, waitStr)
            .setAttribute(Attributes.VER, verStr)
            .setAttribute(Attributes.HOLD, "1")
            .setAttribute(Attributes.INACTIVITY, "3")
            .setNamespaceDefinition(Attributes.DISABLE_EMPTY_MESSAGES.getPrefix(),
                    Attributes.DISABLE_EMPTY_MESSAGES.getNamespaceURI())
            .setAttribute(Attributes.DISABLE_EMPTY_MESSAGES, "1");
    }

    protected void enableAssertions() {
        final String prop = BOSHClient.class.getSimpleName() + ".assertionsEnabled";
        System.setProperty(prop, "true");
    }

    /**
     * Log the test name.
     */
    protected void logTestStart() {
        Thread thr = Thread.currentThread();
        StackTraceElement stack[] = thr.getStackTrace();
        String testName;
        if (stack == null || stack.length < 3) {
            testName = null;
        } else {
            StackTraceElement test = stack[2];
            testName = test.getMethodName();
        }
        LOG.info("==== TEST: " + getClass().getName() + "." + testName);
    }

    /**
     * Make sure the validators are happy.
     *
     * @param scr session creation response message to
     *  use in validation, or {@code null} to skip those tests
     */
    protected void assertValidators(final AbstractBody scr) {
        connValidator.done();
        reqValidator.checkAssertions(scr);
        connValidator.checkAssertions();
    }

    /**
     * Make sure the specified method exists, documenting the dependency.
     *
     * @param clazz class that contains the method
     * @param methodName name of the method
     */
    protected void assertMethodExists(
            final Class clazz,
            final String methodName) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return;
            }
        }
        fail("Method not defined: " + clazz.getSimpleName() + "." + methodName
                + "()");
    }

    /**
     * Alias for {@code assertMethodExists()}, making the intent more clear.
     * This adds a minor bit of protection against inadvertent removal of
     * test coverage, even though it doesn't guarantee execution in-and-of
     * itself.
     *
     * @param clazz class that tests the implied condition
     * @param methodName name of the method that performs the test
     */
    protected void testedBy(final Class clazz, final String methodName) {
        assertMethodExists(clazz, methodName);
    }


}
