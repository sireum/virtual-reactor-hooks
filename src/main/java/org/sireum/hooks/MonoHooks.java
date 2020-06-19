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

import java.time.Duration;

import static org.sireum.hooks.TimeBarriers.noInstrumentation;
import static org.sireum.hooks.Utils.proceed;

@Aspect
public final class MonoHooks {

    @Around("execution(public static * reactor.core.publisher.Mono.delay(..)) && args(java.time.Duration)")
    public final Object delay(ProceedingJoinPoint joinPoint) {
        // static methods have no "noInstrumentation" check (because they have no target to scan)
        final Object[] originalArgs = joinPoint.getArgs();
        final Duration duration = (Duration) originalArgs[0];
        return Utils.virtualMono(joinPoint, scheduler -> Mono.delay(duration, scheduler));
//        return Utils.virtualMono(joinPoint, scheduler -> Mono.just(0L).delayElement(duration, scheduler));
//        return Utils.virtualMono(joinPoint, scheduler -> TimeBarriers.virtualMonoDelay(duration));
    }

    @Around("execution(public final * reactor.core.publisher.Mono.cache(..)) && args(java.time.Duration)")
    public final Object cache(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return Utils.virtualMono(joinPoint, scheduler -> {
                final UnsupportedOperationException cause = new UnsupportedOperationException("cache is not supported in virtual time");
                return Mono.error(new InstrumentationAssemblyException(joinPoint, cause));
            });
        }
    }

    @Around("execution(public final * reactor.core.publisher.Mono.delayElement(..)) && args(java.time.Duration)")
    public final Object delayElement(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration delay = (Duration) originalArgs[0];
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
    @Around("execution(public final * reactor.core.publisher.Mono.delaySubscription(..)) && args(java.time.Duration)")
    public final Object delaySubscription(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return joinPoint.getTarget();
        }
    }

    @Around("execution(public final * reactor.core.publisher.Mono.elapsed(..)) && args()")
    public final Object elapsed(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Mono<?> target = (Mono<?>) joinPoint.getTarget();
            return Utils.virtualMono(joinPoint, target::elapsed);
        }
    }

    @Deprecated
    @Around("execution(public final * reactor.core.publisher.Mono.retryWhen(..)) && args(reactor.util.retry.Retry)")
    public final Object retryWhen(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return Utils.virtualMono(joinPoint, scheduler -> {
                final UnsupportedOperationException cause = new UnsupportedOperationException("retryWhen is not supported in virtual time");
                return Mono.error(new InstrumentationAssemblyException(joinPoint, cause));
            });
        }
    }

    @Around("execution(public final * reactor.core.publisher.Mono.take(..)) && args(java.time.Duration)")
    public final Object take(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration duration = (Duration) originalArgs[0];
            final Mono<?> target = (Mono<?>) joinPoint.getTarget();
            return Utils.virtualMono(joinPoint, scheduler -> target.take(duration, scheduler));
        }
    }

    @Around("execution(public final * reactor.core.publisher.Mono.timeout(..)) && args(java.time.Duration)")
    public final Object timeout1(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration timeout = (Duration) originalArgs[0];
            final Mono<?> target = (Mono<?>) joinPoint.getTarget();
            return Utils.virtualMono(joinPoint, scheduler -> target.timeout(timeout, scheduler));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Around("execution(public final * reactor.core.publisher.Mono.timeout(..)) && args(java.time.Duration, reactor.core.publisher.Mono)")
    public final Object timeout2(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Object[] originalArgs = joinPoint.getArgs();
            final Duration timeout = (Duration) originalArgs[0];
            final Mono fallback = (Mono) originalArgs[1];
            final Mono<?> target = (Mono<?>) joinPoint.getTarget();
            return Utils.virtualMono(joinPoint, scheduler -> target.timeout(timeout, fallback, scheduler));
        }
    }

    @Around("execution(public final * reactor.core.publisher.Mono.timestamp(..)) && args()")
    public final Object timestamp(ProceedingJoinPoint joinPoint) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            final Mono<?> target = (Mono<?>) joinPoint.getTarget();
            return Utils.virtualMono(joinPoint, target::timestamp);
        }
    }
}
