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

import reactor.core.publisher.Flux;

/**
 * A wrapper for exceptions that occur when assembling a {@link TimeBarriers} operator. This will typically occur
 * when {@link TimeBarriers#EXIT_VIRTUAL_TIME(Flux)} is called but detects an illegal "not virtual time" hint was
 * attached somewhere in the virtual section.
 *
 * @see TimeBarriers#EXIT_VIRTUAL_TIME(Flux) and variants
 * @see TimeBarriers#ATTACH_NOT_VIRTUAL_HINT(Flux) and variants
 */
public class BarrierAssemblyException extends RuntimeException {

    /**
     * For exceptions that occur while assembling a {@link TimeBarriers} operator.
     *
     * @param message message for {@link Runtime} super class
     */
    BarrierAssemblyException(String message) {
        super(message);
    }
}
