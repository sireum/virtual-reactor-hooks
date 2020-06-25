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
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.sireum.hooks.TimeBarriers.noInstrumentation;
import static org.sireum.hooks.Utils.proceed;

/**
 * An AspectJ {@link Aspect} with advice wrapping all {@link Flux} methods that use a default
 * {@link reactor.core.scheduler.Scheduler} with checks to instead remain on the current {@link VirtualTimeScheduler}
 * if inside a virtual section.
 *
 * @see MonoHooks
 * @see ConnectableFluxHooks
 */
@Aspect
public final class FluxHooks {

    @Around(value = "execution(public static * reactor.core.publisher.Flux.interval(..)) && args(period)", argNames = "joinPoint,period")
    public Object interval1(ProceedingJoinPoint joinPoint, Duration period) {
        // static methods have no "noInstrumentation" check (because they have no target to scan)
        return Utils.virtualFlux(joinPoint, scheduler -> Flux.interval(period, scheduler));
    }

    @Around(value = "execution(public static * reactor.core.publisher.Flux.interval(..)) && args(delay, period)", argNames = "joinPoint,delay,period")
    public Object interval2(ProceedingJoinPoint joinPoint, Duration delay, Duration period) {
        // static methods have no "noInstrumentation" check (because they have no target to scan)
        return Utils.virtualFlux(joinPoint, scheduler -> Flux.interval(delay, period, scheduler));
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.buffer(..)) && args(bufferingTimespan)", argNames = "joinPoint,bufferingTimespan")
    public final Object buffer1(ProceedingJoinPoint joinPoint, Duration bufferingTimespan) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.buffer(bufferingTimespan, scheduler));
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.buffer(..)) && args(bufferingTimespan, openBufferEvery)", argNames = "joinPoint,bufferingTimespan,openBufferEvery")
    public final Object buffer2(ProceedingJoinPoint joinPoint, Duration bufferingTimespan, Duration openBufferEvery) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.buffer(bufferingTimespan, openBufferEvery, scheduler));
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.bufferTimeout(..)) && args(maxSize, maxTime)", argNames = "joinPoint,maxSize,maxTime")
    public final Object bufferTimeout1(ProceedingJoinPoint joinPoint, int maxSize, Duration maxTime) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.bufferTimeout(maxSize, maxTime, scheduler));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Around(value = "execution(public final * reactor.core.publisher.Flux.bufferTimeout(..)) && args(maxSize, maxTime, bufferSupplier)", argNames = "joinPoint,maxSize,maxTime,bufferSupplier")
    public final Object bufferTimeout2(ProceedingJoinPoint joinPoint, int maxSize, Duration maxTime, Supplier bufferSupplier) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.bufferTimeout(maxSize, maxTime, scheduler, bufferSupplier));
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.cache(..)) && args(ttl)", argNames = "joinPoint,ttl")
    public final Object cache1(ProceedingJoinPoint joinPoint, Duration ttl) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return Utils.virtualFlux(joinPoint, scheduler -> {
                final UnsupportedOperationException cause = new UnsupportedOperationException("cache is not supported in virtual time");
                return Flux.error(new InstrumentationAssemblyException(joinPoint, cause));
            });
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.cache(..)) && args(history, ttl)", argNames = "joinPoint,history,ttl")
    public final Object cache2(ProceedingJoinPoint joinPoint, int history, Duration ttl) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return Utils.virtualFlux(joinPoint, scheduler -> {
                final UnsupportedOperationException cause = new UnsupportedOperationException("cache is not supported in virtual time");
                return Flux.error(new InstrumentationAssemblyException(joinPoint, cause));
            });
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.delayElements(..)) && args(delay)", argNames = "joinPoint,delay")
    public final Object delayElements(ProceedingJoinPoint joinPoint, Duration delay) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.delayElements(delay, scheduler));
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.delaySequence(..)) && args(delay)", argNames = "joinPoint,delay")
    public final Object delaySequence(ProceedingJoinPoint joinPoint, Duration delay) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
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
    @Around(value = "execution(public final * reactor.core.publisher.Flux.delaySubscription(..)) && args(delay)", argNames = "joinPoint,delay")
    public final Object delaySubscription(ProceedingJoinPoint joinPoint, Duration delay) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return joinPoint.getTarget();
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.elapsed(..)) && args()", argNames = "joinPoint")
    public final Object elapsed(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, target::elapsed);
        }
    }

    @SuppressWarnings("rawtypes")
    @Around(value = "execution(public final * reactor.core.publisher.Flux.onBackpressureBuffer(..)) && args(ttl, maxSize, onBufferEviction)", argNames = "joinPoint,ttl,maxSize,onBufferEviction")
    public final Object onBackpressureBuffer(ProceedingJoinPoint joinPoint, Duration ttl, int maxSize, Consumer onBufferEviction) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return Utils.virtualFlux(joinPoint, scheduler -> {
                final UnsupportedOperationException cause = new UnsupportedOperationException("onBackpressureBuffer is not supported in virtual time");
                return Flux.error(new InstrumentationAssemblyException(joinPoint, cause));
            });
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.replay(..)) && args(ttl)", argNames = "joinPoint,ttl")
    public final Object replay1(ProceedingJoinPoint joinPoint, Duration ttl) {
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

    @Around(value = "execution(public final * reactor.core.publisher.Flux.replay(..)) && args(history, ttl)", argNames = "joinPoint,history,ttl")
    public final Object replay2(ProceedingJoinPoint joinPoint, int history, Duration ttl) {
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

    @Around(value = "execution(public final * reactor.core.publisher.Flux.retryWhen(..)) && args(retrySpec)", argNames = "joinPoint,retrySpec")
    public final Object retryWhen(ProceedingJoinPoint joinPoint, Retry retrySpec) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return Utils.virtualFlux(joinPoint, scheduler -> {
                final UnsupportedOperationException cause = new UnsupportedOperationException("retryWhen is not supported in virtual time");
                return Flux.error(new InstrumentationAssemblyException(joinPoint, cause));
            });
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.skip(..)) && args(timespan)", argNames = "joinPoint,timespan")
    public final Object skip(ProceedingJoinPoint joinPoint, Duration timespan) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.skip(timespan, scheduler));
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.take(..)) && args(timespan)", argNames = "joinPoint,timespan")
    public final Object take(ProceedingJoinPoint joinPoint, Duration timespan) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.take(timespan, scheduler));
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.timeout(..)) && args(timeout)", argNames = "joinPoint,timeout")
    public final Object timeout1(ProceedingJoinPoint joinPoint, Duration timeout) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.timeout(timeout, null, scheduler));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Around(value = "execution(public final * reactor.core.publisher.Flux.timeout(..)) && args(timeout, publisher)", argNames = "joinPoint,timeout,publisher")
    public final Object timeout2(ProceedingJoinPoint joinPoint, Duration timeout, Publisher publisher) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.timeout(timeout, publisher, scheduler));
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.timestamp(..)) && args()", argNames = "joinPoint")
    public final Object timestamp(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, target::timestamp);
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.window(..)) && args(windowingTimespan)", argNames = "joinPoint,windowingTimespan")
    public final Object window1(ProceedingJoinPoint joinPoint, Duration windowingTimespan) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.window(windowingTimespan, scheduler));
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.window(..)) && args(windowingTimespan, openWindowEvery)", argNames = "joinPoint,windowingTimespan,openWindowEvery")
    public final Object window2(ProceedingJoinPoint joinPoint,Duration windowingTimespan, Duration openWindowEvery) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.window(windowingTimespan, openWindowEvery, scheduler));
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Flux.windowTimeout(..)) && args(maxSize, maxTime)", argNames = "joinPoint,maxSize,maxTime")
    public final Object windowTimeout(ProceedingJoinPoint joinPoint, int maxSize, Duration maxTime) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Flux<?> target = (Flux<?>) joinPoint.getTarget();
            return Utils.virtualFlux(joinPoint, scheduler -> target.windowTimeout(maxSize, maxTime, scheduler));
        }
    }

}