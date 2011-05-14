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

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * BOSH body/wrapper element tests.
 */
public class HelpersTest {
    /** If the interrupted flag is set before joinThreadUninterruptible is
     *  called, it must be set when it returns. */
    @Test
    public void testUninterruptibleJoin1() {
        Thread thread = new Thread() {
            public void run() {
                try {
                    Thread.sleep(100);
                } catch(InterruptedException e) {
                    throw new AssertionError(); /* unexpected */
                }
            }
        };

        thread.setName("UninterruptibleJoinThread");
        thread.start();
        Thread.currentThread().interrupt();
        Helpers.joinThreadUninterruptible(thread);

        assertTrue("Interrupt flag should be set", Thread.interrupted());
    }

    /**
     * uninterruptibleJoin must join the given thread, deferring interrupts until
     * the join completes.
     */
    @Test
    public void testUninterruptibleJoin2() {
        class InterruptingThread extends Thread {
            Thread threadToInterrupt;
            InterruptingThread(Thread threadToInterrupt) { this.threadToInterrupt = threadToInterrupt; }

            public void run() {
                try {
                    Thread.sleep(100);
                    threadToInterrupt.interrupt();
                } catch(InterruptedException e) {
                    throw new AssertionError(); /* unexpected */
                }
            }
        };

        Thread interruptingThread = new InterruptingThread(Thread.currentThread());
        interruptingThread.start();

        Thread thread = new Thread() {
            public void run() {
                try {
                    Thread.sleep(250);
                } catch(InterruptedException e) {
                    throw new AssertionError(); /* unexpected */
                }
            }
        };

        /* If the interrupted flag is set before joinThreadUninterruptible is
         * called, it must be set when it returns. */
        thread.setName("UninterruptibleJoinThread");
        thread.start();

        assertFalse("Interrupt flag should not be set yet", Thread.interrupted());

        Helpers.joinThreadUninterruptible(thread);

        assertTrue("Interrupt flag should be set", Thread.interrupted());

        // Shut down the helper thread, too.
        Helpers.joinThreadUninterruptible(interruptingThread);
    }
}
