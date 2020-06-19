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
/*
 * Modifications copyright (C) 2020 Matthew Weis, Kansas State University
 *
 * With the exception of this modification notice, this file is an exact copy of the original source file.
 * However, this source file is NOT attached to the original compiled source, but instead to a version of the source
 * that has been modified for the "virtual-reactor-hooks" project using aspectj's compile-time weaving.
 */

/*\n* Modifications copyright (C) 2020 Matthew Weis, Kansas State University\n*\n* With the exception of this modification notice, this file is an exact copy of the original source file.\n* However, this source file is NOT attached to the original compiled source, but instead to a version of the source\n* that has been modified for the "virtual-reactor-hooks" project using aspectj's compile-time weaving.\n*/

package org.sireum.hooks;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import reactor.core.publisher.Flux;

import static org.sireum.hooks.TimeBarriers.noInstrumentation;
import static org.sireum.hooks.Utils.proceed;

@Aspect
public final class ConnectableFluxHooks {

    @Around("execution(public final * reactor.core.publisher.ConnectableFlux.refCount(..)) && args(int, java.time.Duration)")
    public final Object refCount(ProceedingJoinPoint joinPoint) {
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