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
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.time.Instant;

import static org.sireum.hooks.TestConstants.*;

public class TimeBarriersTest {

    @BeforeTest
    public static void beforeAll() {
        Hooks.onOperatorDebug();
    }

    @BeforeMethod
    public void beforeEach() {
        StepVerifier.setDefaultTimeout(DEFAULT_VERIFY_TIMEOUT);
        VirtualTimeScheduler.reset();
//        ErrorSchedulerFactory.install();
//        verifySchedulerInstallations();
    }

    // checks for varying start time values and strategies

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void constantStartTime() {
        final Flux<String> flux = Flux.just(a, b, c, d, e, f)
                .map(timestampedLetter -> timestampedLetter.mapT1(zeroBasedTime -> zeroBasedTime + 10_000L))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .skip(Duration.ofSeconds(6)) // skip drops first two since its [inclusive, exclusive)
                .transform(it -> TimeBarriers.EXIT_VIRTUAL_TIME(it, Instant.ofEpochMilli(10_000L)));

        StepVerifier.create(flux)
                .expectNext("c")
                .expectNext("d")
                .expectNext("e")
                .expectNext("f")
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void constantEndTime() {
        final Flux<String> flux = Flux.just(a, b, c, d, e, f)
                .transform(it -> TimeBarriers.ENTER_VIRTUAL_TIME(it, Instant.ofEpochMilli(22_000L)))
                .skip(Duration.ofSeconds(6)) // skip drops first two since its [inclusive, exclusive)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectNext("c")
                .expectNext("d")
                .expectNext("e")
                .expectNext("f")
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void constantStartAndEndTime() {
        final Flux<String> flux = Flux.just(a, b, c, d, e, f)
                .map(timestampedLetter -> timestampedLetter.mapT1(zeroBasedTime -> zeroBasedTime + 10_000L))
                .transform(it -> TimeBarriers.ENTER_VIRTUAL_TIME(it, Instant.ofEpochMilli(22_000L)))
                .skip(Duration.ofSeconds(6)) // skip drops first two since its [inclusive, exclusive)
                .transform(it -> TimeBarriers.EXIT_VIRTUAL_TIME(it, Instant.ofEpochMilli(10_000L)));

        StepVerifier.create(flux)
                .expectNext("c")
                .expectNext("d")
                .expectNext("e")
                .expectNext("f")
                .verifyComplete();
    }

    // checks for incorrect use of barriers

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void fluxMissingTimeBarrierBeginTest1() {
        final Flux<Tuple2<Long, String>> flux = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::NOT_VIRTUAL_HINT)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectError(InstrumentationAssemblyException.class)
                .verify();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void fluxMissingTimeBarrierBeginTest2() {
        final Flux<Tuple2<Long, String>> flux = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::NOT_VIRTUAL_HINT)
                .delayElements(Duration.ofSeconds(4))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectError(InstrumentationAssemblyException.class)
                .verify();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void fluxMissingTimeBarrierBeginIsAHeuristicTest() {

        // test behavior where no instrumentation hint makes catching malformed Flux/Mono's impossible
        // luckily the change in types makes this hard to accidentally do

        // should ideally fail at assembly time...
        final Flux<String> flux = Flux.just("a", "b", "c")
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        // ...but wont because no INSTRUMENTATION HINT is found in assembly tags
        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext("a", "b", "c")
                .verifyComplete();

        // instead a InstrumentationSubscriptionException will occur at subscription time
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void fluxMissingTimeBarrierBeginNestedTest() {
        final Flux<String> flux = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .transform(TimeBarriers::NOT_VIRTUAL_HINT)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectError(InstrumentationAssemblyException.class)
                .verify();

        final Flux<String> unmatchedNested = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMapSequential(letter -> Flux.just(letter, letter)
                        .transform(TimeBarriers::NOT_VIRTUAL_HINT)
                        .transform(TimeBarriers::EXIT_VIRTUAL_TIME))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        final Flux<String> nestedHint = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMapSequential(letter -> Flux.just(letter, letter)
                        .transform(TimeBarriers::NOT_VIRTUAL_HINT))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(unmatchedNested, opts().scenarioName("Unmatched inner should fail at runtime"))
                .expectSubscription()
                .expectError(InstrumentationAssemblyException.class)
                .verify();

        StepVerifier.create(nestedHint, opts().scenarioName("Non-virtual inner hint should pass"))
                .expectSubscription()
                .expectNext("a", "a", "b", "b", "c", "c", "d", "d", "e", "e", "f", "f")
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void fluxMissingTimeBarrierEndTest() {
        final Flux<String> matchedToInner = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME);

        StepVerifier.create(matchedToInner)
                .expectSubscription()
                .expectError(InstrumentationSubscriptionException.class)
                .verify();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void fluxMissingTimeBarrierEndNestedTest() {
        // inner end should not count
        final Flux<String> matchedToInner = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMap(letter -> Flux.just(letter, letter)
                        .transform(TimeBarriers::EXIT_VIRTUAL_TIME));

        StepVerifier.create(matchedToInner)
                .expectSubscription()
                .expectError(InstrumentationSubscriptionException.class)
                .verify();
    }

    @Test
    void monoMissingTimeBarrierBeginTest1() {
        final Mono<Tuple2<Long, String>> mono = Mono.just(a)
                .transform(TimeBarriers::NOT_VIRTUAL_HINT)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(mono)
                .expectSubscription()
                .expectError(InstrumentationAssemblyException.class)
                .verify();
    }

    @Test
    void monoMissingTimeBarrierBeginTest2() {
        final Mono<Tuple2<Long, String>> mono = Mono.just(a)
                .transform(TimeBarriers::NOT_VIRTUAL_HINT)
                .delayElement(Duration.ofSeconds(4))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(mono)
                .expectSubscription()
                .expectError(InstrumentationAssemblyException.class)
                .verify();
    }

    @Test
    void monoMissingTimeBarrierBeginIsAHeuristicTest() {

        // test behavior where no instrumentation hint makes catching malformed Flux/Mono's impossible
        // luckily the change in types makes this hard to accidently do

        // should fail...
        final Mono<String> mono = Mono.just("a").transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        // ...but wont because no INSTRUMENTATION HINT is found in assembly tags
        StepVerifier.create(mono)
                .expectSubscription()
                .expectNext("a")
                .verifyComplete();
    }

    @Test
    void monoMissingTimeBarrierBeginNestedTest() {
        final Mono<String> intermediateHint = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .transform(TimeBarriers::NOT_VIRTUAL_HINT)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(intermediateHint)
                .expectSubscription()
                .expectError(InstrumentationAssemblyException.class)
                .verify();

        final Mono<String> unmatchedNested = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMap(letter -> Mono.just(letter)
                        .transform(TimeBarriers::NOT_VIRTUAL_HINT)
                        .transform(TimeBarriers::EXIT_VIRTUAL_TIME))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        final Mono<String> nestedHint = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMap(letter -> Mono.just(letter)
                        .transform(TimeBarriers::NOT_VIRTUAL_HINT))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(unmatchedNested, opts().scenarioName("Unmatched inner should fail at runtime"))
                .expectSubscription()
                .expectError(InstrumentationAssemblyException.class)
                .verify();

        StepVerifier.create(nestedHint, opts().scenarioName("Non-virtual inner hint should pass"))
                .expectSubscription()
                .expectNext("a")
                .verifyComplete();
    }

    @Test
    void monoMissingTimeBarrierEndTest() {
        final Mono<String> matchedToInner = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME);

        StepVerifier.create(matchedToInner)
                .expectSubscription()
                .expectError(InstrumentationSubscriptionException.class)
                .verify();
    }

    @Test
    void monoMissingTimeBarrierEndNestedTest() {
        // inner end should not count
        final Mono<String> matchedToInner = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMap(letter -> Mono.just(letter)
                        .transform(TimeBarriers::EXIT_VIRTUAL_TIME));

        StepVerifier.create(matchedToInner)
                .expectSubscription()
                .expectError(InstrumentationSubscriptionException.class)
                .verify();
    }

    // threading

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void publishOnBeforeVirtualSectionTest() {
        Schedulers.resetFactory();

        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(1))
                .publishOn(Schedulers.parallel())
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(1000L, 0L))
                .expectNext(tuple(2000L, 1L))
                .expectNext(tuple(3000L, 2L))
                .expectNext(tuple(4000L, 3L))
                .expectNext(tuple(5000L, 4L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void subscribeOnBeforeVirtualSectionTest() {
        Schedulers.resetFactory();

        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(1))
                .subscribeOn(Schedulers.parallel())
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(1000L, 0L))
                .expectNext(tuple(2000L, 1L))
                .expectNext(tuple(3000L, 2L))
                .expectNext(tuple(4000L, 3L))
                .expectNext(tuple(5000L, 4L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void publishOnAfterVirtualSectionTest() {
        Schedulers.resetFactory();

        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(1))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME)
                .publishOn(Schedulers.parallel());

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(1000L, 0L))
                .expectNext(tuple(2000L, 1L))
                .expectNext(tuple(3000L, 2L))
                .expectNext(tuple(4000L, 3L))
                .expectNext(tuple(5000L, 4L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void subscribeOnAfterVirtualSectionTest() {
        Schedulers.resetFactory();

        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(1))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME)
                .subscribeOn(Schedulers.parallel());

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(1000L, 0L))
                .expectNext(tuple(2000L, 1L))
                .expectNext(tuple(3000L, 2L))
                .expectNext(tuple(4000L, 3L))
                .expectNext(tuple(5000L, 4L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void cancelOnBeforeVirtualSectionTest() {
        Schedulers.resetFactory();

        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(1))
                .cancelOn(Schedulers.parallel())
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(1000L, 0L))
                .expectNext(tuple(2000L, 1L))
                .expectNext(tuple(3000L, 2L))
                .expectNext(tuple(4000L, 3L))
                .expectNext(tuple(5000L, 4L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void cancelOnAfterVirtualSectionTest() {
        Schedulers.resetFactory();

        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(1))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME)
                .cancelOn(Schedulers.parallel());

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(1000L, 0L))
                .expectNext(tuple(2000L, 1L))
                .expectNext(tuple(3000L, 2L))
                .expectNext(tuple(4000L, 3L))
                .expectNext(tuple(5000L, 4L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void cancelPublishAndSubscribeVirtualSectionTest() {
        Schedulers.resetFactory();

        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(1))
                .publishOn(Schedulers.parallel())
                .subscribeOn(Schedulers.elastic())
                .cancelOn(Schedulers.single())
                .publishOn(Schedulers.newElastic("another elastic"))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME)
                .publishOn(Schedulers.elastic())
                .subscribeOn(Schedulers.single())
                .cancelOn(Schedulers.parallel());

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(1000L, 0L))
                .expectNext(tuple(2000L, 1L))
                .expectNext(tuple(3000L, 2L))
                .expectNext(tuple(4000L, 3L))
                .expectNext(tuple(5000L, 4L))
                .verifyComplete();
    }

    // interval

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void intervalTest1() {

        // this test confirms the behaviors of:
        // Flux.interval in a virtual setting
        // TimeBarrier's behavior where it sets time to max once onComplete has occurred
        // Sampling in virtual time

        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(1))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(1000L, 0L))
                .expectNext(tuple(2000L, 1L))
                .expectNext(tuple(3000L, 2L))
                .expectNext(tuple(4000L, 3L))
                .expectNext(tuple(5000L, 4L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void intervalTest1Delay() {

        // this test confirms the behaviors of:
        // Flux.interval in a virtual setting
        // TimeBarrier's behavior where it sets time to max once onComplete has occurred
        // Sampling in virtual time

        final Flux<Tuple2<Long, Long>> flux =
                TimeUtils.intervalTuples(Duration.ofSeconds(3), Duration.ofSeconds(1))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                        .timestamp()
                        .take(5)
                        .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(3000L, 0L))
                .expectNext(tuple(4000L, 1L))
                .expectNext(tuple(5000L, 2L))
                .expectNext(tuple(6000L, 3L))
                .expectNext(tuple(7000L, 4L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void intervalTest2a() {

        // this test confirms the behaviors of:
        // Flux.interval in a virtual setting
        // TimeBarrier's behavior where it sets time to max once onComplete has occurred
        // Sampling in virtual time

        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(1))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .sample(Duration.ofSeconds(2).plusMillis(1))
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple( 2000L, 1L))
                .expectNext(tuple( 4000L, 3L))
                .expectNext(tuple( 6000L, 5L))
                .expectNext(tuple( 8000L, 7L))
                .expectNext(tuple(10000L, 9L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void intervalTest2aDelay() {

        // this test confirms the behaviors of:
        // Flux.interval in a virtual setting
        // TimeBarrier's behavior where it sets time to max once onComplete has occurred
        // Sampling in virtual time

        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(3), Duration.ofSeconds(1))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .sample(Duration.ofSeconds(2).plusMillis(1))
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple( 4000L, 1L))
                .expectNext(tuple( 6000L, 3L))
                .expectNext(tuple( 8000L, 5L))
                .expectNext(tuple(10000L, 7L))
                .expectNext(tuple(12000L, 9L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void intervalTest2aDelayVariant1() {
        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(2), Duration.ofSeconds(1))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .sample(Duration.ofSeconds(2).plusMillis(1)) // plus 1ms to barely hold window open
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple( 2000L, 0L))
                .expectNext(tuple( 4000L, 2L))
                .expectNext(tuple( 6000L, 4L))
                .expectNext(tuple( 8000L, 6L))
                .expectNext(tuple(10000L, 8L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void intervalTest2aDelayVariant2() {
        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(2), Duration.ofSeconds(1))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .sample(Duration.ofSeconds(2))
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple( 3000L, 1L))
                .expectNext(tuple( 5000L, 3L))
                .expectNext(tuple( 7000L, 5L))
                .expectNext(tuple( 9000L, 7L))
                .expectNext(tuple(11000L, 9L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void intervalTest2b() {

        // this test shows what happens to intervalTest2a if the timestamp() is applied after sample()
        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(1))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .sample(Duration.ofSeconds(2).plusMillis(1))
                .timestamp()
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple( 2001L, 1L))
                .expectNext(tuple( 4002L, 3L))
                .expectNext(tuple( 6003L, 5L))
                .expectNext(tuple( 8004L, 7L))
                .expectNext(tuple(10005L, 9L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void intervalTest2bDelay() {

        // this test shows what happens to intervalTest2a if the timestamp() is applied after sample()
        final Flux<Tuple2<Long, Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(3), Duration.ofSeconds(1))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .sample(Duration.ofSeconds(2).plusMillis(1))
                .timestamp()
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple( 4002L, 1L))
                .expectNext(tuple( 6003L, 3L))
                .expectNext(tuple( 8004L, 5L))
                .expectNext(tuple(10005L, 7L))
                .expectNext(tuple(12006L, 9L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void intervalTest3() {
        final Flux<Long> flux1 = TimeUtils.intervalTuples(Duration.ofMinutes(1))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .skip(Duration.ofMinutes(2)) // notice that 2-minute skip...
                .sample(Duration.ofMinutes(2))
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        // recall it takes 1 min for the initial "0" to be emitted
        final Flux<Long> flux2 = TimeUtils.intervalTuples(Duration.ofMinutes(1))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .skip(Duration.ofMinutes(1).plusMillis(1)) // ...actually only first 1min 1ms "cutoff" matters to sample
                .sample(Duration.ofMinutes(2))
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux1)
                .expectSubscription()
                .expectNext(2L, 4L, 6L, 8L, 10L)
                .verifyComplete();

        StepVerifier.create(flux2)
                .expectSubscription()
                .expectNext(2L, 4L, 6L, 8L, 10L)
                .verifyComplete();
    }

}
