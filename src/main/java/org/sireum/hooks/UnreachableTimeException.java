/*
 * Copyright 2020 Matthew Weis, Kansas State University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sireum.hooks;

/**
 * Thrown when a virtual-time clock is instructed to go backwards in time. This can happen two ways:
 *
 *   (1) ENTER_VIRTUAL_TIME receives an element whose timestamp predates the virtual clock's current time.
 *   (2) ENTER_VIRTUAL_TIME is given a stopTime that predates the virtual clock's current time.
 *
 * This exception can only occur within the virtual-time segment of an instrumented Flux/Mono and will be encapsulated
 * in an error {@link reactor.core.publisher.Signal} that is passed downstream to be handled (or ignored).
 */
public class UnreachableTimeException extends RuntimeException {

    /**
     * For BarrierBeginInnerOperator to throw when asked to handle a time in the past during onNext or onComplete.
     *
     * @param message for {@link Runtime} super class
     */
    UnreachableTimeException(String message) {
        super(message);
    }

}
