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
 * A wrapper for exceptions that occur when a previously instrumented {@link reactor.core.publisher.Flux},
 * {@link reactor.core.publisher.Mono}, {@link reactor.core.publisher.ConnectableFlux}, or any other
 * {@link reactor.core.CorePublisher} encounters an exception inside instrumentation-specific code during
 * subscription time.
 *
 * This exception can only occur within an instrumented Flux/Mono and will be encapsulated in a
 * {@link reactor.core.publisher.SignalType#ON_ERROR} {@link reactor.core.publisher.Signal} that is passed
 * downstream to be handled (or ignored).
 */
public class InstrumentationSubscriptionException extends RuntimeException {
    public InstrumentationSubscriptionException(String message) {
        super(message);
    }
}
