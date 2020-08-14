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

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Ignore;
import org.testng.annotations.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.util.function.Tuple2;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

import static java.util.Collections.nCopies;
import static org.sireum.hooks.TestUtils.*;

public class MonoHooksTest {

    @BeforeMethod
    public void beforeEach() {
        StepVerifier.setDefaultTimeout(Duration.ofSeconds(5));
        VirtualTimeScheduler.reset();
        ErrorSchedulerFactory.install();
        verifySchedulerInstallations();
    }

    @AfterMethod
    public static void afterEach() {
        VirtualTimeScheduler.reset();
        Schedulers.resetFactory();
    }

    @Test
    void takeTest() {
        final Mono<Tuple2<Long, String>> flux = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .take(Duration.ofSeconds(2001))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(2000L, "a"))
                // ignoring "c" is expected and also the behavior in a virtual time test
                .expectComplete()
                .verify();
    }

    @Ignore
    @Test
    void timeoutTest1() {
        final Mono<String> flux = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timeout(Duration.ofSeconds(2))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectError(TimeoutException.class)
                .verify();
    }

    @Ignore
    @Test
    void timeoutTest2() {
        final Mono<String> flux = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timeout(Duration.ofSeconds(1), Mono.just("fallback"))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext("fallback")
                .expectComplete()
                .verify();
    }

    @Test
    void elapsedTest() {
        final Mono<Tuple2<Long, String>> flux = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .elapsed()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(2000L, "a"))
                .expectComplete()
                .verify();
    }

    @Test
    void timestampTest() {
        final Mono<Tuple2<Long, String>> flux = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(2000L, "a"))
                .expectComplete()
                .verify();
    }

    @Test
    void doNotInstrumentHintTest() {
        final Mono<Tuple2<Long, String>> flux = Mono.just(a)
                .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(2000L, "a"))
                .expectComplete()
                .verify();
    }

    @Test
    void delayTupleTest() {
        final Mono<Tuple2<Long,String>> flux = TimeUtils.delayTuple(Duration.ofSeconds(3))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .map(n -> "a")
                .elapsed()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(3000L, "a"))
                .expectComplete()
                .verify();
    }

    @Test
    void delayElementTest() {
        final Mono<Tuple2<Long, String>> flux = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .delayElement(Duration.ofSeconds(3))
                .elapsed()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(5000L, "a"))
                .expectComplete()
                .verify();
    }

    @Test
    void delaySubscriptionDoesNothingTest() {
        final Mono<Tuple2<Long, String>> flux = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .delaySubscription(Duration.ofSeconds(3)) // no effects on virtual time which starts after subscription
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(2000L, "a"))
                .expectComplete()
                .verify();
    }

    @Test
    void multipleTimeShiftsTest1() {
        final Mono<Tuple2<Long, String>> flux = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .delayElement(Duration.ofSeconds(2))
                .delayElement(Duration.ofSeconds(2))
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(6000L, "a"))  //  2000ms -->  6000ms due to delays totaling 4000ms
                .expectComplete()
                .verify();
    }

    @Test
    void multipleTimeShiftsTest2() {
        final Mono<Tuple2<Long, String>> flux = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .delayElement(Duration.ofSeconds(2))
                .delayElement(Duration.ofSeconds(3))
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(7000L, "a"))  //  2000ms -->  7000ms due to delays totaling 5000ms
                .expectComplete()
                .verify();
    }

    @Test
    void innerMonoTest1() {
        final Mono<Tuple2<Long, String>> flux = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMap(letter -> Mono.delay(Duration.ofSeconds(1)).thenReturn(letter))
                .delayElement(Duration.ofSeconds(3))
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(6000L, "a"))  //  2000ms -->  7000ms due to delays totaling 5000ms
                .expectComplete()
                .verify();
    }

    @Test
    void innerMonoTest2() {
        final Mono<Tuple2<Long, String>> flux = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMap(letter -> Mono.just(letter).delayElement(Duration.ofSeconds(2)))
                .delayElement(Duration.ofSeconds(3))
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(7000L, "a"))  //  2000ms -->  7000ms due to delays totaling 5000ms
                .expectComplete()
                .verify();
    }

    @Test
    void innerMonoTest3() {
        final Mono<Tuple2<Long, String>> flux = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMap(letter -> Mono.just(letter).delayElement(Duration.ofSeconds(2)))
                .delayElement(Duration.ofSeconds(3))
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(7000L, "a"))  //  2000ms -->  7000ms due to delays totaling 5000ms
                .expectComplete()
                .verify();
    }

    @Test
    void multiSubscriberTest() throws InterruptedException, TimeoutException, ExecutionException {
        final PublisherProbe<Tuple2<Long, String>> probe = PublisherProbe.of(Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME) // doesn't have to be deferred
                .delayElement(Duration.ofSeconds(3))
                .elapsed()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME)); // must be deferred

        final int numSubscribers = 6;
        final int poolSize = Math.min(numSubscribers, Schedulers.DEFAULT_POOL_SIZE);
        final ExecutorService executorService = Executors.newFixedThreadPool(poolSize);

        final Callable<?> verifier = () -> {
            StepVerifier.create(probe.flux())
                    .expectSubscription()
                    .expectNext(tuple(5000L, "a"))
                    .expectComplete()
                    .verify();
            return null;
        };

        final List<? extends Future<?>> tasks = executorService.invokeAll(nCopies(numSubscribers, verifier));

        for (Future<?> task : tasks) {
            // will throw any exceptions that occurred in the Callable
            task.get(5, TimeUnit.SECONDS);
        }

        probe.assertWasRequested();
        probe.assertWasSubscribed();
        probe.assertWasNotCancelled();
        Assert.assertEquals(numSubscribers, probe.subscribeCount());
    }

    @Test
    void reentrantVirtualTimeTest() {
        final Mono<Tuple2<Long, String>> reentrantRestartsTime = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(reentrantRestartsTime)
                .expectSubscription()
                .expectNext(tuple(2000L, "a"))
                .verifyComplete();
    }

    @Test(invocationCount = 2)
    void verifySchedulerErrorFactoryInstallation() {
        // verifySchedulerInstallations();

        // install a VirtualTimeScheduler
        // since this is a repeated test, this will cause an exception if the @BeforeEach is not resetting properly
        VirtualTimeScheduler.getOrSet();
    }

    @Test
    void cacheTest() {
        final Mono<String> mono = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .cache(Duration.ofSeconds(2))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(mono)
                .expectSubscription()
                .expectError(AssemblyInstrumentationException.class)
                .verify();
    }

    @Test
    void retryWhenTest() {
        final Mono<String> mono = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .retryWhen(Retry.indefinitely())
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(mono)
                .expectSubscription()
                .expectError(AssemblyInstrumentationException.class)
                .verify();
    }

}