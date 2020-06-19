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
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.function.Supplier;

import static org.sireum.hooks.TimeBarriers.noInstrumentation;
import static org.sireum.hooks.Utils.proceed;

@Aspect
public final class FluxHooks {

    @Around("execution(public static * reactor.core.publisher.Flux.interval(..)) && args(java.time.Duration)")
    public Object interval1(ProceedingJoinPoint joinPoint) {
        // static methods have no "noInstrumentation" check (because they have no target to scan)
        final Object[] originalArgs = joinPoint.getArgs();
        final Duration period = (Duration) originalArgs[0];
        return Utils.virtualFlux(joinPoint, scheduler -> Flux.interval(period, scheduler));
    }

    @Around("execution(public static * reactor.core.publisher.Flux.interval(..)) && args(java.time.Duration, java.time.Duration)")
    public Object interval2(ProceedingJoinPoint joinPoint) {
        // static methods have no "noInstrumentation" check (because they have no target to scan)
        final Object[] originalArgs = joinPoint.getArgs();
        final Duration delay = (Duration) originalArgs[0];
        final Duration period = (Duration) originalArgs[1];
        return Utils.virtualFlux(joinPoint, scheduler -> Flux.interval(delay, period, scheduler));
    }

    @Around("execution(public final * reactor.core.publisher.Flux.buffer(..)) && args(java.time.Duration)")
    public final Object buffer1(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration bufferingTimespan = (Duration) originalArgs[0];
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.buffer(bufferingTimespan, scheduler));
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.buffer(..)) && args(java.time.Duration, java.time.Duration)")
    public final Object buffer2(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration bufferingTimespan = (Duration) originalArgs[0];
            final Duration openBufferEvery = (Duration) originalArgs[1];
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.buffer(bufferingTimespan, openBufferEvery, scheduler));
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.bufferTimeout(..)) && args(int, java.time.Duration)")
    public final Object bufferTimeout1(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final int maxSize = (int) originalArgs[0];
            final Duration maxTime = (Duration) originalArgs[1];
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.bufferTimeout(maxSize, maxTime, scheduler));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Around("execution(public final * reactor.core.publisher.Flux.bufferTimeout(..)) && args(int, java.time.Duration, java.util.function.Supplier)")
    public final Object bufferTimeout2(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final int maxSize = (int) originalArgs[0];
            final Duration maxTime = (Duration) originalArgs[1];
            final Supplier bufferSupplier = (Supplier) originalArgs[2];
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.bufferTimeout(maxSize, maxTime, scheduler, bufferSupplier));
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.cache(..)) && args(java.time.Duration)")
    public final Object cache1(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return Utils.virtualFlux(joinPoint, scheduler -> {
                final UnsupportedOperationException cause = new UnsupportedOperationException("cache is not supported in virtual time");
                return Flux.error(new InstrumentationAssemblyException(joinPoint, cause));
            });
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.cache(..)) && args(int, java.time.Duration)")
    public final Object cache2(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return Utils.virtualFlux(joinPoint, scheduler -> {
                final UnsupportedOperationException cause = new UnsupportedOperationException("cache is not supported in virtual time");
                return Flux.error(new InstrumentationAssemblyException(joinPoint, cause));
            });
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.delayElements(..)) && args(java.time.Duration)")
    public final Object delayElements(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration delay = (Duration) originalArgs[0];
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.delayElements(delay, scheduler));
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.delaySequence(..)) && args(java.time.Duration)")
    public final Object delaySequence(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration delay = (Duration) originalArgs[0];
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.delaySequence(delay, scheduler));
        }
    }

    /**
     * The problem with delay subscription is that the virtual timer only advances when elements are emitted.
     * As a result, delaySubscription(anyDuration, virtualScheduler) will freeze forever.
     * <p>
     * Since everything is in virtual time based on arrival of elements, delaying subscription has
     * NO EFFECTS in virtual time. This is because virtual time is always 0 until the first element arrives.
     */
    @Around("execution(public final * reactor.core.publisher.Flux.delaySubscription(..)) && args(java.time.Duration)")
    public final Object delaySubscription(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return joinPoint.getTarget();
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.elapsed(..)) && args()")
    public final Object elapsed(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, target::elapsed);
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.onBackpressureBuffer(..)) && args(java.time.Duration, int, java.util.function.Consumer)")
    public final Object onBackpressureBuffer(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return Utils.virtualFlux(joinPoint, scheduler -> {
                final UnsupportedOperationException cause = new UnsupportedOperationException("onBackpressureBuffer is not supported in virtual time");
                return Flux.error(new InstrumentationAssemblyException(joinPoint, cause));
            });
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.replay(..)) && args(java.time.Duration)")
    public final Object replay1(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final UnsupportedOperationException cause = new UnsupportedOperationException(
                    "UNABLE TO DETERMINE IF REPLAY IS CALLED IN VIRTUAL TIME, " +
                    "to fix, please add the code: \".transform(TimeBarriers::DO_NOT_INSTRUMENT_HINT)\" " +
                    "upstream to the replay() call. " +
                    "Note: this workaround is ONLY needed for replay() because this exception cannot be " +
                    "propagated as an error signal due to limitations with instrumenting ConnectableFlux."

            );
            throw new InstrumentationAssemblyException(joinPoint, cause);
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.replay(..)) && args(int, java.time.Duration)")
    public final Object replay2(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final UnsupportedOperationException cause = new UnsupportedOperationException(
                    "UNABLE TO DETERMINE IF REPLAY IS CALLED IN VIRTUAL TIME, " +
                            "to fix, please add the code: \".transform(TimeBarriers::DO_NOT_INSTRUMENT_HINT)\" " +
                            "upstream to the replay() call. " +
                            "Note: this workaround is ONLY needed for replay() because this exception cannot be " +
                            "propagated as an error signal due to limitations with instrumenting ConnectableFlux."

            );
            throw new InstrumentationAssemblyException(joinPoint, cause);
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.retryWhen(..)) && args(reactor.util.retry.Retry)")
    public final Object retryWhen(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return Utils.virtualFlux(joinPoint, scheduler -> {
                final UnsupportedOperationException cause = new UnsupportedOperationException("retryWhen is not supported in virtual time");
                return Flux.error(new InstrumentationAssemblyException(joinPoint, cause));
            });
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.skip(..)) && args(java.time.Duration)")
    public final Object skip(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration timespan = (Duration) originalArgs[0];
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.skip(timespan, scheduler));
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.take(..)) && args(java.time.Duration)")
    public final Object take(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration timespan = (Duration) originalArgs[0];
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.take(timespan, scheduler));
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.timeout(..)) && args(java.time.Duration)")
    public final Object timeout1(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration timeout = (Duration) originalArgs[0];
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.timeout(timeout, null, scheduler));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Around("execution(public final * reactor.core.publisher.Flux.timeout(..)) && args(java.time.Duration, org.reactivestreams.Publisher)")
    public final Object timeout2(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration timeout = (Duration) originalArgs[0];
            final Publisher publisher = (Publisher) originalArgs[1];
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.timeout(timeout, publisher, scheduler));
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.timestamp(..)) && args()")
    public final Object timestamp(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, target::timestamp);
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.window(..)) && args(java.time.Duration)")
    public final Object window1(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration windowingTimespan = (Duration) originalArgs[0];
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.window(windowingTimespan, scheduler));
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.window(..)) && args(java.time.Duration, java.time.Duration)")
    public final Object window2(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration windowingTimespan = (Duration) originalArgs[0];
            final Duration openWindowEvery = (Duration) originalArgs[1];
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.window(windowingTimespan, openWindowEvery, scheduler));
        }
    }

    @Around("execution(public final * reactor.core.publisher.Flux.windowTimeout(..)) && args(int, java.time.Duration)")
    public final Object windowTimeout(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final int maxSize = (int) originalArgs[0];
            final Duration maxTime = (Duration) originalArgs[1];
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.windowTimeout(maxSize, maxTime, scheduler));
        }
    }

}