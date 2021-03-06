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

import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.function.Tuple2;

import java.time.Duration;

public class TckAdvancedVerificationTest extends PublisherVerification<Long> {

    public TckAdvancedVerificationTest() {
        super(new TestEnvironment());
    }

    @Override
    public Publisher<Long> createPublisher(long l) {
        return TimeUtils.intervalTuples(Duration.ofMinutes(5))
                .take(l)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .map(n -> Operators.addCap(n, n))
                .flatMapSequential(n -> Flux.interval(Duration.ofMinutes(10)).take(1))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);
    }

    @Override
    public Publisher<Long> createFailedPublisher() {
        return Mono.<Tuple2<Long,Long>>error(new RuntimeException("intentional mono error (tck verification)"))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .retry(3)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);
    }

}
