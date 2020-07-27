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

import java.time.Instant;

/**
 * Thrown when a virtual-time clock is advanced to an {@link Instant} outside of the supported range.
 * The supported range is: {@code Instant.ofEpochMilli(0L)} to {@code Instant.ofEpochMilli(Long.MAX_VALUE)} inclusive.
 *
 * This exception can only occur within the virtual-time segment of an instrumented Flux/Mono and will be encapsulated
 * in an error {@link reactor.core.publisher.Signal} that is passed downstream to be handled (or ignored).
 */
public class UnsupportedTimeException extends UnsupportedOperationException {

    /**
     * (1) For BarrierBeginInnerOperator to throw when asked to handle an unsupported time during onComplete.
     * (2) For BarrierEndInnerOperator to throw when asked to handle an unsupported time during onSubscribe.
     *
     * @param illegalTime the unsupported time given the the operator
     * @param additionalContext additional info to help user's track down where things went wrong
     */
    UnsupportedTimeException(Instant illegalTime, String additionalContext) {
        super("Virtual time must be between Instant.ofEpochMilli(0L) and Instant.ofEpochMilli(Long.MAX_VALUE) but " +
                illegalTime + " occurred " + additionalContext);
    }

}
