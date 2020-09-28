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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.util.annotation.NonNull;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * This utility class exclusively contains static methods and fields for internal use.
 */
final class PackageUtils {

    /**
     * The smallest {@link Instant} supported by the {@link VirtualTimeScheduler}.
     */
    static final Instant MIN_EPOCH = Instant.ofEpochMilli(0L);

    /**
     * The largest {@link Instant} supported by the {@link VirtualTimeScheduler}.
     */
    static final Instant MAX_EPOCH = Instant.ofEpochMilli(Long.MAX_VALUE);

    /**
     * When in virtual time, this key will point to the {@link VirtualTimeScheduler} stored in subscriberContext.
     * <br>
     * Recall that a virtual section's scheduler is unique for each subscriber, and that shared access to this scheduler
     * is the unifying property that enables an upstream ENTER_VIRTUAL_TIME barrier to collaborate with its
     * downstream EXIT_VIRTUAL_TIME barrier (and vice-versa).
     */
    static final String SCHEDULER_CONTEXT_KEY = "org.sireum.hooks.scheduler";

    /**
     * This is a utility class and cannot be instantiated.
     */
    private PackageUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Attempts to return the result of calling {@link ProceedingJoinPoint#proceed()} on the joinPoint. If an
     * exception occurs, it is wrapped and rethrown as an {@link AssemblyInstrumentationException}. This method is
     * used as a standard for evaluating a joinPoint known to be non-virtual at assembly time (indicated by a hint from
     * {@link TimeBarriers#ATTACH_NOT_VIRTUAL_HINT(Flux)} / {@link TimeBarriers#ATTACH_NOT_VIRTUAL_HINT(Mono)}).
     * <br>
     * (Note that this method is only called for joinPoints that return time-sensitive
     * {@link org.reactivestreams.Publisher}s)
     * <br>
     * @param joinPoint the joinPoint for evaluating {@link ProceedingJoinPoint#proceed()} with error handling
     * @return the result of evaluating {@link ProceedingJoinPoint#proceed()} on the given joinPoint
     * @throws AssemblyInstrumentationException wrapping any thrown exceptions
     */
    @NonNull
    static Object proceed(@NonNull ProceedingJoinPoint joinPoint) {
        try {
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            throw new AssemblyInstrumentationException(joinPoint, throwable);
        }
    }

    /**
     * When no "non-virtual-time" hint is available at assembly time, a definitive subscription-time check must be
     * performed to determine if a {@link Mono} is within a virtual section. If it turns out the {@link Mono} is NOT
     * in a virtual-section, we want to return the result of user's initial method call as if nothing ever happened.
     * If however it turns out the {@link Mono} IS in a virtual-section, we want to instead adjust the user's method
     * call so that the resulting {@link Mono} remains on the virtual section's {@link VirtualTimeScheduler}.
     * <br>
     * This method performs the logic described above. Its first argument is capable of returning the user's initial,
     * unmodified result. Its second argument can return the user's adjusted {@link VirtualTimeScheduler}-bound result.
     * With these blueprints in hand, this method returns a {@link Mono} that defers the decision of which blueprint
     * to use until subscription time. At subscription time, the "is this virtual time" question is determined by
     * checking if the {@link PackageUtils#SCHEDULER_CONTEXT_KEY} maps to a {@link VirtualTimeScheduler} in the
     * {@link Mono}'s {@link reactor.util.context.Context}, and the appropriate result is returned by invoking its
     * corresponding blueprint.
     * <br>
     * A consequence of this strategy is that it may limit assembly-time optimization for non-virtual time-based
     * operators because their implementation is now decided at subscription time. This is because without a
     * "not-in-virtual-time" hint, the time-based operators are chosen subscription time (even if it is non-virtual).
     * This overhead can be completely avoided by calling {@link Mono#transform(Function)} with
     * {@link TimeBarriers#ATTACH_NOT_VIRTUAL_HINT(Mono)} upstream of any number of time-based operators. This will
     * ensure the operators undergo normal assembly.
     * <br>
     * Note that this method is only called for joinPoints that return time-sensitive
     * {@link org.reactivestreams.Publisher}s.
     *
     * @param joinPoint the joinPoint of the user's original, time-sensitive {@link Mono}-creating, method call
     * @param builder a function mapping the virtual-section's {@link VirtualTimeScheduler} to its adjusted {@link Mono}
     * @param <T> the type of the {@link Mono}'s element value
     * @return a {@link Mono} which supplies either the user's initial or adjusted {@link Mono} at subscription-time
     */
    @NonNull
    @SuppressWarnings("unchecked")
    static <T> Mono<T> virtualMono(@NonNull ProceedingJoinPoint joinPoint,
                                   @NonNull Function<? super Scheduler, ? extends Mono<T>> builder) {
        return Mono.deferContextual(contextView -> {
            final Optional<VirtualTimeScheduler> maybeScheduler = contextView.getOrEmpty(SCHEDULER_CONTEXT_KEY);
            if (maybeScheduler.isPresent()) {
                final VirtualTimeScheduler scheduler = maybeScheduler.get();
                return builder.apply(scheduler);
            } else {
                try {
                    return (Mono<T>) joinPoint.proceed();
                } catch (Throwable throwable) {
                    return Mono.error(throwable);
                }
            }
        });
    }

    /**
     * When no "non-virtual-time" hint is available at assembly time, a definitive subscription-time check must be
     * performed to determine if a {@link Flux} is within a virtual section. If it turns out the {@link Flux} is NOT
     * in a virtual-section, we want to return the result of user's initial method call as if nothing ever happened.
     * If however it turns out the {@link Flux} IS in a virtual-section, we want to instead adjust the user's method
     * call so that the resulting {@link Flux} remains on the virtual section's {@link VirtualTimeScheduler}.
     * <br>
     * This method performs the logic described above. Its first argument is capable of returning the user's initial,
     * unmodified result. Its second argument can return the user's adjusted {@link VirtualTimeScheduler}-bound result.
     * With these blueprints in hand, this method returns a {@link Flux} that defers the decision of which blueprint
     * to use until subscription time. At subscription time, the "is this virtual time" question is determined by
     * checking if the {@link PackageUtils#SCHEDULER_CONTEXT_KEY} maps to a {@link VirtualTimeScheduler} in the
     * {@link Flux}'s {@link reactor.util.context.Context}, and the appropriate result is returned by invoking its
     * corresponding blueprint.
     * <br>
     * A consequence of this strategy is that it may limit assembly-time optimization for non-virtual time-based
     * operators because their implementation is now decided at subscription time. This is because without a
     * "not-in-virtual-time" hint, the time-based operators are chosen subscription time (even if it is non-virtual).
     * This overhead can be completely avoided by calling {@link Flux#transform(Function)} with
     * {@link TimeBarriers#ATTACH_NOT_VIRTUAL_HINT(Flux)} upstream of any number of time-based operators. This will
     * ensure the operators undergo normal assembly.
     * <br>
     * Note that this method is only called for joinPoints that return time-sensitive
     * {@link org.reactivestreams.Publisher}s.
     *
     * @param joinPoint the joinPoint of the user's original, time-sensitive {@link Flux}-creating, method call
     * @param builder a function mapping the virtual-section's {@link VirtualTimeScheduler} to its adjusted {@link Flux}
     * @param <T> the type of the {@link Flux}'s element value
     * @return a {@link Flux} which supplies either the user's initial or adjusted {@link Flux} at subscription-time
     */
    @NonNull
    @SuppressWarnings("unchecked")
    static <T> Flux<T> virtualFlux(ProceedingJoinPoint joinPoint, Function<? super Scheduler, ? extends Flux<T>> builder) {
        return Flux.deferContextual(contextView -> {
            final Optional<VirtualTimeScheduler> maybeScheduler = contextView.getOrEmpty(SCHEDULER_CONTEXT_KEY);
            if (maybeScheduler.isPresent()) {
                final VirtualTimeScheduler scheduler = maybeScheduler.get();
                return builder.apply(scheduler);
            } else {
                try {
                    return (Flux<T>) joinPoint.proceed();
                } catch (Throwable throwable) {
                    return Flux.error(throwable);
                }
            }
        });
    }

    /**
     * Returns whether or not the given instant can be handled by the virtual time scheduler.
     *
     * @param instant the {@link Instant} to be validated
     * @return true iff the given {@link Instant} is supported in virtual time
     */
    static boolean  validate(@NonNull Instant instant) {
        return !instant.isBefore(MIN_EPOCH) && !instant.isAfter(MAX_EPOCH);
    }
}
