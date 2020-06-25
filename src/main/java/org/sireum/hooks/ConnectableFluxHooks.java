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
import reactor.core.publisher.Flux;

import java.time.Duration;

import static org.sireum.hooks.TimeBarriers.noInstrumentation;
import static org.sireum.hooks.Utils.proceed;

/**
 * An AspectJ {@link Aspect} with advice wrapping all {@link reactor.core.publisher.ConnectableFlux} methods that use a
 * default {@link reactor.core.scheduler.Scheduler} with checks to instead remain on the current
 * {@link VirtualTimeScheduler} if inside a virtual section.
 *
 * @see FluxHooks
 * @see MonoHooks
 */
@Aspect
public final class ConnectableFluxHooks {

    @Around(value = "execution(public final * reactor.core.publisher.ConnectableFlux.refCount(..)) && args(minSubscribers, gracePeriod)", argNames = "joinPoint,minSubscribers,gracePeriod")
    public final Object refCount(ProceedingJoinPoint joinPoint, int minSubscribers, Duration gracePeriod) {
        if (noInstrumentation(joinPoint)) {
            return proceed(joinPoint);
        } else {
            return Utils.virtualFlux(joinPoint, scheduler -> {
                final UnsupportedOperationException cause = new UnsupportedOperationException("refCount is not supported in virtual time");
                return Flux.error(new InstrumentationAssemblyException(joinPoint, cause));
            });
        }
    }

}