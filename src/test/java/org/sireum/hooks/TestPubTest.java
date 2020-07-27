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

import org.testng.annotations.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.sireum.hooks.TestConstants.*;

public class TestPubTest {

    @Test
    public void hotProducerParallelSchedulerTest() {

        Schedulers.resetFactory(); // opt-in to allow parallel scheduler (blocked by default in most tests)

        final TestPublisher<Tuple2<Long,String>> testPub = TestPublisher.create();

        final Flux<Tuple2<Long, List<String>>> flux = testPub.flux()
                .publishOn(Schedulers.parallel())
                .onBackpressureBuffer() // <-- out of order emission is fine with a backpressure strategy
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .delayElements(Duration.ofSeconds(3))
                .buffer(Duration.ofSeconds(6))
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        final ExecutorService executorService = Executors.newFixedThreadPool(1);

        executorService.submit(() -> {
            try {
                Thread.sleep(62); // sleep for an arbitrary amount of time between each emission
                testPub.next(a);
                Thread.sleep(17);
                testPub.next(b);
                Thread.sleep(12);
                testPub.next(c);
                Thread.sleep(81);
                testPub.next(d);
                Thread.sleep(14);
                testPub.next(e);
                Thread.sleep(52);
                testPub.next(f);
                Thread.sleep(29);
                testPub.complete();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(6000L, list("a")))
                .expectNext(tuple(12000L, list("b", "c")))
                .expectNext(tuple(18000L, list("d", "e")))
                .expectNext(tuple(20000L, list("f")))
                .verifyComplete();
    }

    @Test
    public void hotProducerParallelSchedulerTest2() {

        Schedulers.resetFactory(); // opt-in to allow parallel scheduler (blocked by default in most tests)

        final TestPublisher<Tuple2<Long,String>> testPub = TestPublisher.create();

        final AtomicInteger count = new AtomicInteger(6);

        final Flux<Tuple2<Long, String>> flux = testPub.flux()
                .publishOn(Schedulers.parallel())
                .onBackpressureBuffer() // <-- out of order emission is fine with a backpressure strategy
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMap(s -> Mono.just(s).delayElement(Duration.ofSeconds(count.getAndDecrement())))
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        final ExecutorService executorService = Executors.newFixedThreadPool(1);

        executorService.submit(() -> {
            try {
                Thread.sleep(62); // sleep for an arbitrary amount of time between each emission
                testPub.next(a);
                Thread.sleep(24);
                testPub.next(b);
                Thread.sleep(18);
                testPub.next(c);
                Thread.sleep(50);
                testPub.next(d);
                Thread.sleep(75);
                testPub.next(e);
                Thread.sleep(16);
                testPub.next(f);
                Thread.sleep(80);
                testPub.complete();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(8000L, "a"))  // 2s + 6s delay = 8s
                .expectNext(tuple(9000L, "b"))  // 4s + 5s delay = 9s
                .expectNext(tuple(10000L, "c")) // 6s + 4s delay = 10s
                .expectNext(tuple(11000L, "d")) // 8s + 3s delay = 11s
                .expectNext(tuple(12000L, "e")) //10s + 2s delay = 12s
                .expectNext(tuple(13000L, "f")) //12s + 1s delay = 13s
                .verifyComplete();
    }

}
