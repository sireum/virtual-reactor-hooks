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
 * A wrapper for exceptions that occur within a {@link TimeBarriers} operator at subscription time. This will typically
 * occur when a BEGIN_VIRTUAL_TIME operator cannot detect a downstream END_VIRTUAL_TIME operator.
 *
 * @see TimeBarriers#ENTER_VIRTUAL_TIME(Flux) and variants
 * @see TimeBarriers#EXIT_VIRTUAL_TIME(Flux) and variants
 */
public class BarrierSubscriptionException extends RuntimeException {

    /**
     * For exceptions that occur during subscription time of a {@link TimeBarriers} operator.
     *
     * @param message message for {@link Runtime} super class
     */
    BarrierSubscriptionException(String message) {
        super(message);
    }
}
