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

import org.aspectj.lang.ProceedingJoinPoint;

import java.util.Arrays;

/**
 * A wrapper for exceptions that occur when instrumenting a {@link reactor.core.publisher.Flux},
 * {@link reactor.core.publisher.Mono}, {@link reactor.core.publisher.ConnectableFlux}, or any other
 * {@link reactor.core.CorePublisher} at assembly time.
 * <br>
 * Warning: this class uses reflection to create its detailed error message.
 */
public class AssemblyInstrumentationException extends RuntimeException {

    /**
     * Indicates an exception occurred when assembling an instrumented operator.
     *
     * @param joinPoint whose {@link ProceedingJoinPoint#proceed()} call threw the error
     * @param cause the error to wrap alongside the additional information
     */
    AssemblyInstrumentationException(ProceedingJoinPoint joinPoint, Throwable cause) {
        super(createErrorMessage(joinPoint), cause);
    }

    /**
     * Uses reflection to detailed error message from the {@link ProceedingJoinPoint}.
     *
     * @param joinPoint to evaluate
     * @return a detailed error message about the joinPoint
     */
    private static String createErrorMessage(ProceedingJoinPoint joinPoint) {
        return "A fatal exception occurred around an instrumentation join point. " +
                "\n(Note: the exception's stack trace will be printed below.)" +
                "\n===== Begin join point info dump =====" +
                "\n  target: " + joinPoint.getTarget() +
                "\n  signature: " + joinPoint.getSignature() +
                "\n  args: " + Arrays.toString(joinPoint.getArgs()) +
                "\n  this: " + joinPoint.getThis() +
                "\n  shortString: " + joinPoint.toShortString() +
                "\n  longString: " + joinPoint.toLongString() +
                "\n  kind: " + joinPoint.getKind() +
                "\n  sourceLocation: " + joinPoint.getSourceLocation() +
                "\n  staticPart: " + joinPoint.getStaticPart() +
                "\n===== End join point info dump =====";
    }
}
