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
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.sireum.hooks.PackageUtils.proceed;
import static org.sireum.hooks.TimeBarriers.noInstrumentation;

/**
 * Contains {@link Aspect}s with advice wrapping time-sensitive operations. Neither this class nor its inner
 * {@link Aspect}s should be used directly.
 */
public class ReactorAdvice {

    /**
     * This is a utility class and cannot be instantiated.
     */
    private ReactorAdvice() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Contains hooks for instrumenting time-sensitive operations -- this class should not be used directly.
     * <br>
     * An {@link Aspect} with advice wrapping all {@link Flux} methods with a default
     * {@link reactor.core.scheduler.Scheduler}. With this advice, methods check if they are within a virtual
     * section before automatically swapping schedulers. If within a virtual section, the scheduler will not change.
     *
     * @see MonoHooks
     * @see ConnectableFluxHooks
     */
    @Aspect
    public static final class FluxHooks {

        /**
         * Users should not use this class.
         */
        private FluxHooks() {
            // AspectJ will make instances of this class available through an injected factory method.
            // Users should never interact with this class directly.
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#interval(Duration)} into {@link Flux#interval(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param period    user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public static * reactor.core.publisher.Flux.interval(..)) && args(period)", argNames = "joinPoint,period")
        public final Object interval1(ProceedingJoinPoint joinPoint, Duration period) {
            // static methods do not have "noInstrumentation" check (because they have no target to scan)
            // instead, the check occurs at runtime
            return PackageUtils.virtualFlux(joinPoint, scheduler -> Flux.interval(period, scheduler));
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#interval(Duration)} into {@link Flux#interval(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param delay    user arg
         * @param period    user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public static * reactor.core.publisher.Flux.interval(..)) && args(delay, period)", argNames = "joinPoint,delay,period")
        public final Object interval2(ProceedingJoinPoint joinPoint, Duration delay, Duration period) {
            // static methods do not have "noInstrumentation" check (because they have no target to scan)
            // instead, the check occurs at runtime
            return PackageUtils.virtualFlux(joinPoint, scheduler -> Flux.interval(delay, period, scheduler));
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#buffer(Duration)} into {@link Flux#buffer(Duration, Scheduler)}.
         *
         * @param joinPoint         @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param bufferingTimespan user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.buffer(..)) && args(bufferingTimespan)", argNames = "joinPoint,bufferingTimespan")
        public final Object buffer1(ProceedingJoinPoint joinPoint, Duration bufferingTimespan) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, scheduler -> target.buffer(bufferingTimespan, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#buffer(Duration)} into {@link Flux#buffer(Duration, Scheduler)}.
         *
         * @param joinPoint       @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param bufferingTimespan user arg
         * @param openBufferEvery user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.buffer(..)) && args(bufferingTimespan, openBufferEvery)", argNames = "joinPoint,bufferingTimespan,openBufferEvery")
        public final Object buffer2(ProceedingJoinPoint joinPoint, Duration bufferingTimespan, Duration openBufferEvery) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, scheduler -> target.buffer(bufferingTimespan, openBufferEvery, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#bufferTimeout(int, Duration)} into {@link Flux#bufferTimeout(int, Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param maxSize   user arg
         * @param maxTime   user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.bufferTimeout(..)) && args(maxSize, maxTime)", argNames = "joinPoint,maxSize,maxTime")
        public final Object bufferTimeout1(ProceedingJoinPoint joinPoint, int maxSize, Duration maxTime) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, scheduler -> target.bufferTimeout(maxSize, maxTime, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#bufferTimeout(int, Duration, Supplier)} into {@link Flux#bufferTimeout(int, Duration, Scheduler, Supplier)}.
         *
         * @param joinPoint      @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param maxSize user arg
         * @param maxTime user arg
         * @param bufferSupplier user arg
         * @return a new {@link Flux}
         */
        @SuppressWarnings({"rawtypes", "unchecked"})
        @Around(value = "execution(public final * reactor.core.publisher.Flux.bufferTimeout(..)) && args(maxSize, maxTime, bufferSupplier)", argNames = "joinPoint,maxSize,maxTime,bufferSupplier")
        public final Object bufferTimeout2(ProceedingJoinPoint joinPoint, int maxSize, Duration maxTime, Supplier bufferSupplier) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, scheduler -> target.bufferTimeout(maxSize, maxTime, scheduler, bufferSupplier));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#cache(Duration)} into {@link Flux#cache(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param ttl       user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.cache(..)) && args(ttl)", argNames = "joinPoint,ttl")
        public final Object cache1(ProceedingJoinPoint joinPoint, Duration ttl) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                return PackageUtils.virtualFlux(joinPoint, scheduler -> {
                    final UnsupportedOperationException cause = new UnsupportedOperationException("cache is not supported in virtual time");
                    return Flux.error(new AssemblyInstrumentationException(joinPoint, cause));
                });
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#cache(Duration)} into {@link Flux#cache(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param history       user arg
         * @param ttl       user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.cache(..)) && args(history, ttl)", argNames = "joinPoint,history,ttl")
        public final Object cache2(ProceedingJoinPoint joinPoint, int history, Duration ttl) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                return PackageUtils.virtualFlux(joinPoint, scheduler -> {
                    final UnsupportedOperationException cause = new UnsupportedOperationException("cache is not supported in virtual time");
                    return Flux.error(new AssemblyInstrumentationException(joinPoint, cause));
                });
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#delayElements(Duration)} into {@link Flux#delayElements(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param delay     user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.delayElements(..)) && args(delay)", argNames = "joinPoint,delay")
        public final Object delayElements(ProceedingJoinPoint joinPoint, Duration delay) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, scheduler -> target.delayElements(delay, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#delaySequence(Duration)} into {@link Flux#delaySequence(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param delay     user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.delaySequence(..)) && args(delay)", argNames = "joinPoint,delay")
        public final Object delaySequence(ProceedingJoinPoint joinPoint, Duration delay) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, scheduler -> target.delaySequence(delay, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, then this hook completely IGNORES the delaySubscription call. This is
         * because incoming elements are what dictate the current time, so the behavior of this operator is ambiguous
         * at best. Another issue is that delaySubscription will freeze the {@link Flux} indefinitely if the
         * EXIT_VIRTUAL_TIME operator is not provided with a startTime greater than or equal to the delaySubscription
         * delay.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param delay     user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.delaySubscription(..)) && args(delay)", argNames = "joinPoint,delay")
        public final Object delaySubscription(ProceedingJoinPoint joinPoint, Duration delay) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                return joinPoint.getTarget();
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#elapsed()} into {@link Flux#elapsed(, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.elapsed(..)) && args()", argNames = "joinPoint")
        public final Object elapsed(ProceedingJoinPoint joinPoint) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, target::elapsed);
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#onBackpressureBuffer(Duration, int, Consumer)} into {@link Flux#onBackpressureBuffer(Duration, int, Consumer, Scheduler)}.
         *
         * @param joinPoint        @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param ttl user arg
         * @param maxSize user arg
         * @param onBufferEviction user arg
         * @return a new {@link Flux}
         */
        @SuppressWarnings("rawtypes")
        @Around(value = "execution(public final * reactor.core.publisher.Flux.onBackpressureBuffer(..)) && args(ttl, maxSize, onBufferEviction)", argNames = "joinPoint,ttl,maxSize,onBufferEviction")
        public final Object onBackpressureBuffer(ProceedingJoinPoint joinPoint, Duration ttl, int maxSize, Consumer onBufferEviction) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                return PackageUtils.virtualFlux(joinPoint, scheduler -> {
                    final UnsupportedOperationException cause = new UnsupportedOperationException("onBackpressureBuffer is not supported in virtual time");
                    return Flux.error(new AssemblyInstrumentationException(joinPoint, cause));
                });
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#replay(Duration)} into {@link Flux#replay(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param ttl       user arg
         * @return a new {@link Flux}
         */
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
                throw new AssemblyInstrumentationException(joinPoint, cause);
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#replay(Duration)} into {@link Flux#replay(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param history       user arg
         * @param ttl       user arg
         * @return a new {@link Flux}
         */
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
                throw new AssemblyInstrumentationException(joinPoint, cause);
            }
        }

        /**
         * If a virtual scheduler is available, return a {@link Flux} throwing an {@link AssemblyInstrumentationException}.
         * because this operator is not supported in virtual time
         * <p>
         * todo: change this behavior? The only problematic case is when the {@link Retry} has a {@link Scheduler}
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param retrySpec user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.retryWhen(..)) && args(retrySpec)", argNames = "joinPoint,retrySpec")
        public final Object retryWhen(ProceedingJoinPoint joinPoint, Retry retrySpec) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                return PackageUtils.virtualFlux(joinPoint, scheduler -> {
                    final UnsupportedOperationException cause = new UnsupportedOperationException("retryWhen is not supported in virtual time");
                    return Flux.error(new AssemblyInstrumentationException(joinPoint, cause));
                });
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#skip(Duration)} into {@link Flux#skip(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param timespan  user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.skip(..)) && args(timespan)", argNames = "joinPoint,timespan")
        public final Object skip(ProceedingJoinPoint joinPoint, Duration timespan) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, scheduler -> target.skip(timespan, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#take(Duration)} into {@link Flux#take(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param timespan  user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.take(..)) && args(timespan)", argNames = "joinPoint,timespan")
        public final Object take(ProceedingJoinPoint joinPoint, Duration timespan) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, scheduler -> target.take(timespan, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#timeout(Duration)} into {@link Flux#timeout(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param timeout   user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.timeout(..)) && args(timeout)", argNames = "joinPoint,timeout")
        public final Object timeout1(ProceedingJoinPoint joinPoint, Duration timeout) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, scheduler -> target.timeout(timeout, null, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#timeout(Duration, Publisher)} into {@link Flux#timeout(Duration, Publisher, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param timeout user arg
         * @param publisher user arg
         * @return a new {@link Flux}
         */
        @SuppressWarnings({"rawtypes", "unchecked"})
        @Around(value = "execution(public final * reactor.core.publisher.Flux.timeout(..)) && args(timeout, publisher)", argNames = "joinPoint,timeout,publisher")
        public final Object timeout2(ProceedingJoinPoint joinPoint, Duration timeout, Publisher publisher) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, scheduler -> target.timeout(timeout, publisher, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#timestamp()} into {@link Flux#timestamp(, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.timestamp(..)) && args()", argNames = "joinPoint")
        public final Object timestamp(ProceedingJoinPoint joinPoint) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, target::timestamp);
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#window(Duration)} into {@link Flux#window(Duration, Scheduler)}.
         *
         * @param joinPoint         @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param windowingTimespan user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.window(..)) && args(windowingTimespan)", argNames = "joinPoint,windowingTimespan")
        public final Object window1(ProceedingJoinPoint joinPoint, Duration windowingTimespan) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, scheduler -> target.window(windowingTimespan, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#window(Duration)} into {@link Flux#window(Duration, Scheduler)}.
         *
         * @param joinPoint       @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param windowingTimespan user arg
         * @param openWindowEvery user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.window(..)) && args(windowingTimespan, openWindowEvery)", argNames = "joinPoint,windowingTimespan,openWindowEvery")
        public final Object window2(ProceedingJoinPoint joinPoint, Duration windowingTimespan, Duration openWindowEvery) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, scheduler -> target.window(windowingTimespan, openWindowEvery, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Flux#windowTimeout(int, Duration)} into {@link Flux#windowTimeout(int, Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param maxSize   user arg
         * @param maxTime   user arg
         * @return a new {@link Flux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Flux.windowTimeout(..)) && args(maxSize, maxTime)", argNames = "joinPoint,maxSize,maxTime")
        public final Object windowTimeout(ProceedingJoinPoint joinPoint, int maxSize, Duration maxTime) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Flux<?> target = (Flux<?>) joinPoint.getTarget();
                return PackageUtils.virtualFlux(joinPoint, scheduler -> target.windowTimeout(maxSize, maxTime, scheduler));
            }
        }

    }

    /**
     * Contains hooks for instrumenting time-sensitive operations -- this class should not be used directly.
     * <br>
     * An {@link Aspect} with advice wrapping all {@link Mono} methods with a default
     * {@link reactor.core.scheduler.Scheduler}. With this advice, methods check if they are within a virtual
     * section before automatically swapping schedulers. If within a virtual section, the scheduler will not change.
     *
     * @see FluxHooks
     * @see ConnectableFluxHooks
     */
    @Aspect
    public static final class MonoHooks {

        /**
         * Users should not use this class.
         */
        private MonoHooks() {
            // AspectJ will make instances of this class available through an injected factory method.
            // Users should never interact with this class directly.
        }

        /**
         * If a virtual scheduler is available, hook {@link Mono#delay(Duration)} into {@link Mono#delay(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param duration  user arg
         * @return a new {@link Mono}
         */
        @Around(value = "execution(public static * reactor.core.publisher.Mono.delay(..)) && args(duration)", argNames = "joinPoint,duration")
        public final Object delay(ProceedingJoinPoint joinPoint, Duration duration) {
            // static methods have no "noInstrumentation" check (because they have no target to scan)
            return PackageUtils.virtualMono(joinPoint, scheduler -> Mono.delay(duration, scheduler));
        }

        /**
         * If a virtual scheduler is available, hook {@link Mono#cache(Duration)} into {@link Mono#cache(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param ttl       user arg
         * @return a new {@link Mono}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Mono.cache(..)) && args(ttl)", argNames = "joinPoint,ttl")
        public final Object cache(ProceedingJoinPoint joinPoint, Duration ttl) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                return PackageUtils.virtualMono(joinPoint, scheduler -> {
                    final UnsupportedOperationException cause = new UnsupportedOperationException("cache is not supported in virtual time");
                    return Mono.error(new AssemblyInstrumentationException(joinPoint, cause));
                });
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Mono#delayElement(Duration)} into {@link Mono#delayElement(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param delay     user arg
         * @return a new {@link Mono}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Mono.delayElement(..)) && args(delay)", argNames = "joinPoint,delay")
        public final Object delayElement(ProceedingJoinPoint joinPoint, Duration delay) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Mono<?> target = (Mono<?>) joinPoint.getTarget();
                return PackageUtils.virtualMono(joinPoint, scheduler -> target.delayElement(delay, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, then this hook completely IGNORES the delaySubscription call. This is
         * because incoming elements are what dictate the current time, so the behavior of this operator is ambiguous
         * at best. Another issue is that delaySubscription will freeze the {@link Mono} indefinitely if the
         * EXIT_VIRTUAL_TIME operator is not provided with a startTime greater than or equal to the delaySubscription
         * delay.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param delay     user arg
         * @return a new {@link Mono}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Mono.delaySubscription(..)) && args(delay)", argNames = "joinPoint,delay")
        public final Object delaySubscription(ProceedingJoinPoint joinPoint, Duration delay) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                return joinPoint.getTarget();
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Mono#elapsed()} into {@link Mono#elapsed(, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @return a new {@link Mono}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Mono.elapsed(..)) && args()", argNames = "joinPoint")
        public final Object elapsed(ProceedingJoinPoint joinPoint) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Mono<?> target = (Mono<?>) joinPoint.getTarget();
                return PackageUtils.virtualMono(joinPoint, target::elapsed);
            }
        }

        /**
         * If a virtual scheduler is available, return a {@link Mono} throwing an {@link AssemblyInstrumentationException}.
         * because this operator is not supported in virtual time.
         * <p>
         * todo: change this behavior? The only problematic case is when the {@link Retry} has a {@link Scheduler}
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param retrySpec user arg
         * @return a new {@link Mono}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Mono.retryWhen(..)) && args(retrySpec)", argNames = "joinPoint,retrySpec")
        public final Object retryWhen(ProceedingJoinPoint joinPoint, Retry retrySpec) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                return PackageUtils.virtualMono(joinPoint, scheduler -> {
                    final UnsupportedOperationException cause = new UnsupportedOperationException("retryWhen is not supported in virtual time");
                    return Mono.error(new AssemblyInstrumentationException(joinPoint, cause));
                });
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Mono#take(Duration)} into {@link Mono#take(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param duration  user arg
         * @return a new {@link Mono}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Mono.take(..)) && args(duration)", argNames = "joinPoint,duration")
        public final Object take(ProceedingJoinPoint joinPoint, Duration duration) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Mono<?> target = (Mono<?>) joinPoint.getTarget();
                return PackageUtils.virtualMono(joinPoint, scheduler -> target.take(duration, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Mono#timeout(Duration)} into {@link Mono#timeout(Duration, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param timeout   user arg
         * @return a new {@link Mono}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Mono.timeout(..)) && args(timeout)", argNames = "joinPoint,timeout")
        public final Object timeout1(ProceedingJoinPoint joinPoint, Duration timeout) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Mono<?> target = (Mono<?>) joinPoint.getTarget();
                return PackageUtils.virtualMono(joinPoint, scheduler -> target.timeout(timeout, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Mono#timeout(Duration, Mono)} into {@link Mono#timeout(Duration, Mono, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param fallback  user arg
         * @return a new {@link Mono}
         */
        @SuppressWarnings({"rawtypes", "unchecked"})
        @Around(value = "execution(public final * reactor.core.publisher.Mono.timeout(..)) && args(timeout, fallback)", argNames = "joinPoint,timeout,fallback")
        public final Object timeout2(ProceedingJoinPoint joinPoint, Duration timeout, Mono fallback) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Mono<?> target = (Mono<?>) joinPoint.getTarget();
                return PackageUtils.virtualMono(joinPoint, scheduler -> target.timeout(timeout, fallback, scheduler));
            }
        }

        /**
         * If a virtual scheduler is available, hook {@link Mono#timestamp()} into {@link Mono#timestamp(, Scheduler)}.
         *
         * @param joinPoint @link org.aspectj.lang.JoinPoint} wrapping the call
         * @return a new {@link Mono}
         */
        @Around(value = "execution(public final * reactor.core.publisher.Mono.timestamp(..)) && args()", argNames = "joinPoint")
        public final Object timestamp(ProceedingJoinPoint joinPoint) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                final Mono<?> target = (Mono<?>) joinPoint.getTarget();
                return PackageUtils.virtualMono(joinPoint, target::timestamp);
            }
        }

    }

    /**
     * Contains hooks for instrumenting time-sensitive operations -- this class should not be used directly.
     * <br>
     * An {@link Aspect} with advice wrapping all {@link reactor.core.publisher.ConnectableFlux} methods with a default
     * {@link reactor.core.scheduler.Scheduler}. With this advice, methods check if they are within a
     * virtual section before automatically swapping schedulers. If within a virtual section, the scheduler will not change.
     *
     * @see FluxHooks
     * @see MonoHooks
     */
    @Aspect
    public static final class ConnectableFluxHooks {

        /**
         * Users should not use this class.
         */
        private ConnectableFluxHooks() {
            // AspectJ will make instances of this class available through an injected factory method.
            // Users should never interact with this class directly.
        }

        /**
         * If a virtual scheduler is available, hook {@link ConnectableFlux#refCount(int, Duration)} into {@link ConnectableFlux#refCount(int, Duration, Scheduler)}.
         *
         * @param joinPoint   @link org.aspectj.lang.JoinPoint} wrapping the call
         * @param minSubscribers user arg
         * @param gracePeriod user arg
         * @return a new {@link ConnectableFlux}
         */
        @Around(value = "execution(public final * reactor.core.publisher.ConnectableFlux.refCount(..)) && args(minSubscribers, gracePeriod)", argNames = "joinPoint,minSubscribers,gracePeriod")
        public final Object refCount(ProceedingJoinPoint joinPoint, int minSubscribers, Duration gracePeriod) {
            if (noInstrumentation(joinPoint)) {
                return proceed(joinPoint);
            } else {
                return PackageUtils.virtualFlux(joinPoint, scheduler -> {
                    final UnsupportedOperationException cause = new UnsupportedOperationException("refCount is not supported in virtual time");
                    return Flux.error(new AssemblyInstrumentationException(joinPoint, cause));
                });
            }
        }

    }
}
