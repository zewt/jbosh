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

class Helpers {
    /** Join the thread, deferring interrupts until it completes. */
    static void joinThreadUninterruptible(Thread thread) {
        boolean interrupted = false;
        while(true) {
            try {
                thread.join();
            } catch(InterruptedException e) {
                interrupted = true;
                continue;
            }
            break;
        }

        if(interrupted)
            Thread.currentThread().interrupt();
    }
}
