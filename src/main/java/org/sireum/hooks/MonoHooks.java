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
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

import static org.sireum.hooks.TimeBarriers.noInstrumentation;
import static org.sireum.hooks.Utils.proceed;

/**
 * An AspectJ {@link Aspect} with advice wrapping all {@link Mono} methods that use a default
 * {@link reactor.core.scheduler.Scheduler} with checks to instead remain on the current {@link VirtualTimeScheduler}
 * if inside a virtual section.
 *
 * @see FluxHooks
 * @see ConnectableFluxHooks
 */
@Aspect
public final class MonoHooks {

    @Around(value = "execution(public static * reactor.core.publisher.Mono.delay(..)) && args(duration)", argNames = "joinPoint,duration")
    public final Object delay(ProceedingJoinPoint joinPoint, Duration duration) {
        // static methods have no "noInstrumentation" check (because they have no target to scan)
        return Utils.virtualMono(joinPoint, scheduler -> Mono.delay(duration, scheduler));
    }

    @Around(value = "execution(public final * reactor.core.publisher.Mono.cache(..)) && args(ttl)", argNames = "joinPoint,ttl")
    public final Object cache(ProceedingJoinPoint joinPoint, Duration ttl) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return Utils.virtualMono(joinPoint, scheduler -> {
                final UnsupportedOperationException cause = new UnsupportedOperationException("cache is not supported in virtual time");
                return Mono.error(new InstrumentationAssemblyException(joinPoint, cause));
            });
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Mono.delayElement(..)) && args(delay)", argNames = "joinPoint,delay")
    public final Object delayElement(ProceedingJoinPoint joinPoint, Duration delay) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Mono<?> target = (Mono<?>) joinPoint.getTarget();
            return Utils.virtualMono(joinPoint, scheduler -> target.delayElement(delay, scheduler));
        }
    }

    /**
     * The problem with delay subscription is that the virtual timer only advances when elements are emitted.
     * As a result, delaySubscription(anyDuration, virtualScheduler) will freeze forever.
     * <p>
     * Since everything is in virtual time based on arrival of elements, delaying subscription has
     * NO EFFECTS in virtual time. This is because virtual time is always 0 until the first element arrives.
     */
    @Around(value = "execution(public final * reactor.core.publisher.Mono.delaySubscription(..)) && args(delay)", argNames = "joinPoint,delay")
    public final Object delaySubscription(ProceedingJoinPoint joinPoint, Duration delay) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return joinPoint.getTarget();
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Mono.elapsed(..)) && args()", argNames = "joinPoint")
    public final Object elapsed(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Mono<?> target = (Mono<?>) joinPoint.getTarget();
            return Utils.virtualMono(joinPoint, target::elapsed);
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Mono.retryWhen(..)) && args(retrySpec)", argNames = "joinPoint,retrySpec")
    public final Object retryWhen(ProceedingJoinPoint joinPoint, Retry retrySpec) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return Utils.virtualMono(joinPoint, scheduler -> {
                final UnsupportedOperationException cause = new UnsupportedOperationException("retryWhen is not supported in virtual time");
                return Mono.error(new InstrumentationAssemblyException(joinPoint, cause));
            });
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Mono.take(..)) && args(duration)", argNames = "joinPoint,duration")
    public final Object take(ProceedingJoinPoint joinPoint, Duration duration) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Mono<?> target = (Mono<?>) joinPoint.getTarget();
            return Utils.virtualMono(joinPoint, scheduler -> target.take(duration, scheduler));
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Mono.timeout(..)) && args(timeout)", argNames = "joinPoint,timeout")
    public final Object timeout1(ProceedingJoinPoint joinPoint, Duration timeout) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Mono<?> target = (Mono<?>) joinPoint.getTarget();
            return Utils.virtualMono(joinPoint, scheduler -> target.timeout(timeout, scheduler));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Around(value = "execution(public final * reactor.core.publisher.Mono.timeout(..)) && args(timeout, fallback)", argNames = "joinPoint,timeout,fallback")
    public final Object timeout2(ProceedingJoinPoint joinPoint, Duration timeout, Mono fallback) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Mono<?> target = (Mono<?>) joinPoint.getTarget();
            return Utils.virtualMono(joinPoint, scheduler -> target.timeout(timeout, fallback, scheduler));
        }
    }

    @Around(value = "execution(public final * reactor.core.publisher.Mono.timestamp(..)) && args()", argNames = "joinPoint")
    public final Object timestamp(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Mono<?> target = (Mono<?>) joinPoint.getTarget();
            return Utils.virtualMono(joinPoint, target::timestamp);
        }
    }
}
