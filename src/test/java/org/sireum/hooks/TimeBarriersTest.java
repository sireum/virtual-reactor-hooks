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

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

import static java.util.Collections.nCopies;
import static org.sireum.hooks.TestUtils.*;

public class TimeBarriersTest {

    @BeforeTest
    public static void beforeAll() {
        Hooks.onOperatorDebug();
    }

    @BeforeMethod
    public void beforeEach() {
        StepVerifier.setDefaultTimeout(DEFAULT_VERIFY_TIMEOUT);
        VirtualTimeScheduler.reset();
        ErrorSchedulerFactory.install();
        verifySchedulerInstallations();
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

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void functionContingentEndTime() {
        final long startTimeAbsoluteMs = 5_000L;
        final long stopTimeDeltaMs = 3_000L;

        final Flux<Tuple2<Long,Long>> flux = TimeUtils.intervalTuples(Duration.ofSeconds(1))
                .skipWhile(stamped -> stamped.getT1() < startTimeAbsoluteMs)
                .transform(it -> TimeBarriers.ENTER_VIRTUAL_TIME(it, stopTime -> stopTime.plusMillis(stopTimeDeltaMs)))
                .take(4)
                .timestamp()
                .transform(it -> TimeBarriers.EXIT_VIRTUAL_TIME(it, () -> Instant.ofEpochMilli(startTimeAbsoluteMs)));

        StepVerifier.create(flux)
                .expectNext(tuple(5000L, 4L))
                .expectNext(tuple(6000L, 5L))
                .expectNext(tuple(7000L, 6L))
                .expectNext(tuple(8000L, 7L))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void unsupportedStartTimeShouldFail() {
        final Flux<String> flux = Flux.just(a, b, c)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .transform(it -> TimeBarriers.EXIT_VIRTUAL_TIME(it, Instant.ofEpochMilli(-1)));

        StepVerifier.create(flux)
                .expectSubscription()
                .expectError(UnsupportedTimeException.class)
                .verify();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void nonMonotonicStartTimeShouldFail() {
        final Flux<String> flux = Flux.just(a, b, c)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .transform(it -> TimeBarriers.EXIT_VIRTUAL_TIME(it, Instant.ofEpochSecond(3)));

        StepVerifier.create(flux)
                .expectSubscription()
                .expectError(UnreachableTimeException.class)
                .verify();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void unsupportedStopTimeShouldFail() {
        final Flux<String> flux = Flux.just(a, b, c)
                .transform(it -> TimeBarriers.ENTER_VIRTUAL_TIME(it, Instant.ofEpochMilli(-1)))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext("a")
                .expectNext("b")
                .expectNext("c")
                .expectError(UnsupportedTimeException.class)
                .verify();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void nonMonotonicStopTimeShouldFail1() {
        final Flux<String> flux = Flux.just(a, b, c)
                .transform(it -> TimeBarriers.ENTER_VIRTUAL_TIME(it, Instant.ofEpochSecond(2)))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext("a")
                .expectNext("b")
                .expectNext("c")
                .expectError(UnreachableTimeException.class)
                .verify();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void nonMonotonicStopTimeShouldFail2() {
        final Flux<String> flux = Flux.just(a, b, c)
                .transform(it -> TimeBarriers.ENTER_VIRTUAL_TIME(it, instant -> instant.minusMillis(1)))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext("a")
                .expectNext("b")
                .expectNext("c")
                .expectError(UnreachableTimeException.class)
                .verify();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void nonMonotonicStopTimeAccumulator1() {
        final Flux<String> flux = Flux.just(a, b, c)
                .transform(it -> TimeBarriers.ENTER_VIRTUAL_TIME(it,
                        () -> Instant.ofEpochSecond(0),
                        (timeAcc, element) -> element.getT1(),
                        lastTime -> lastTime
                        )).transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext("a")
                .expectNext("b")
                .expectNext("c")
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void nonMonotonicStopTimeAccumulator2() {
        final Flux<String> flux = Flux.just(a, b, c)
                .transform(it -> TimeBarriers.ENTER_VIRTUAL_TIME(it,
                        () -> "",
                        (acc, next) -> acc.concat(next.getT2()),
                        combinedLetters -> Instant.ofEpochSecond(combinedLetters.length() * 2)
                )).transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext("a")
                .expectNext("b")
                .expectNext("c")
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void nonMonotonicStopTimeAccumulator3() {
        //  this just sums all times, so it will "overshoot" the "natural" end time of 6sec with 2+4+6 = 12sec
        final Flux<String> flux = Flux.just(a, b, c)
                .transform(it -> TimeBarriers.ENTER_VIRTUAL_TIME(it,
                        () -> Instant.ofEpochMilli(0),
                        (acc, next) -> acc.plusMillis(next.getT1().toEpochMilli()),
                        instant -> instant
                )).transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext("a")
                .expectNext("b")
                .expectNext("c")
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void nonMonotonicStopTimeAccumulator4() {
        // only track half the time passed should result in an error (trying to go backwards in time)
        final Flux<String> flux = Flux.just(a, b, c)
                .transform(it -> TimeBarriers.ENTER_VIRTUAL_TIME(it,
                        () -> 0L,
                        (acc, next) -> next.getT1().toEpochMilli() / 2L,
                        Instant::ofEpochMilli
                )).transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext("a")
                .expectNext("b")
                .expectNext("c")
                .expectError(UnreachableTimeException.class)
                .verify();
    }

    // checks for incorrect use of barriers

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void fluxMissingTimeBarrierBeginTest1() {
        final Flux<Tuple2<Long, String>> flux = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectError(BarrierAssemblyException.class)
                .verify();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void fluxMissingTimeBarrierBeginTest2() {
        Schedulers.resetFactory();
        final Flux<Tuple2<Long, String>> flux = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT)
                .delayElements(Duration.ofSeconds(4))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectError(BarrierAssemblyException.class)
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
                .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectError(BarrierAssemblyException.class)
                .verify();

        final Flux<String> unmatchedNested = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMapSequential(letter -> Flux.just(letter, letter)
                        .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT)
                        .transform(TimeBarriers::EXIT_VIRTUAL_TIME))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        final Flux<String> nestedHint = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMapSequential(letter -> Flux.just(letter, letter)
                        .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(unmatchedNested, opts().scenarioName("Unmatched inner should fail at runtime"))
                .expectSubscription()
                .expectError(BarrierAssemblyException.class)
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
                .expectError(BarrierSubscriptionException.class)
                .verify();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void fluxTimeBasedOnBackpressureBufferUnsupported() {
        final Flux<String> matchedToInner = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .onBackpressureBuffer(Duration.ofSeconds(1), 6, s -> {})
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(matchedToInner)
                .expectSubscription()
                .expectError(AssemblyInstrumentationException.class)
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
                .expectError(BarrierSubscriptionException.class)
                .verify();
    }

    @Test
    void monoMissingTimeBarrierBeginTest1() {
        final Mono<Tuple2<Long, String>> mono = Mono.just(a)
                .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(mono)
                .expectSubscription()
                .expectError(BarrierAssemblyException.class)
                .verify();
    }

    @Test
    void monoMissingTimeBarrierBeginTest2() {
        Schedulers.resetFactory();
        final Mono<Tuple2<Long, String>> mono = Mono.just(a)
                .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT)
                .delayElement(Duration.ofSeconds(4))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(mono)
                .expectSubscription()
                .expectError(BarrierAssemblyException.class)
                .verify();
    }

    @Test
    void monoMissingTimeBarrierBeginIsAHeuristicTest() {

        // test behavior where no instrumentation hint makes catching malformed Flux/Mono's impossible
        // luckily the change in types makes this hard to accidentally do

        // should fail...
        final Mono<String> mono = Mono.just("a").transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        // ...but wont because no INSTRUMENTATION HINT is found in assembly tags
        StepVerifier.create(mono)
                .expectSubscription()
                .expectNext("a")
                .verifyComplete();

        // to see the same behavior with an event throw a SubscriptionInstrumentationException,
        // see the TimeBarriersTest#monoMissingTimeBarrierEndTest test
    }

    @Test
    void monoMissingTimeBarrierBeginNestedTest() {
        final Mono<String> intermediateHint = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(intermediateHint)
                .expectSubscription()
                .expectError(BarrierAssemblyException.class)
                .verify();

        final Mono<String> unmatchedNested = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMap(letter -> Mono.just(letter)
                        .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT)
                        .transform(TimeBarriers::EXIT_VIRTUAL_TIME))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        final Mono<String> nestedHint = Mono.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMap(letter -> Mono.just(letter)
                        .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(unmatchedNested, opts().scenarioName("Unmatched inner should fail at runtime"))
                .expectSubscription()
                .expectError(BarrierAssemblyException.class)
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
                .expectError(BarrierSubscriptionException.class)
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
                .expectError(BarrierSubscriptionException.class)
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

    @Test
    public void outOfOrderMergeTest() {
        final Flux<Tuple2<Long,String>> flux = Flux.merge(Flux.just(a, c, e), Flux.just(b, d, f))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .delaySequence(Duration.ofSeconds(1))
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(3000L, "a"))
                .expectNext(tuple(7000L, "c"))
                .expectNext(tuple(11000L, "e"))
                .expectError(UnreachableTimeException.class)
                .verify();
    }

    @Test
    public void upstreamBackpressureTest1() {
        final TestPublisher<Tuple2<Long,String>> testPub = TestPublisher.create();

        final Flux<Tuple2<Long,String>> flux = testPub.flux()
                .onBackpressureBuffer()
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux, 0)
                .expectSubscription()
                .thenRequest(1)
                .then(() -> testPub.next(a, b, c))
                .expectNext(tuple(2000L, "a"))
                .thenAwait()
                .thenRequest(2)
                .expectNext(tuple(4000L, "b"))
                .expectNext(tuple(6000L, "c"))
                .then(testPub::complete)
                .verifyComplete();
    }

    @Test
    public void upstreamBackpressureTest2() {
        final TestPublisher<Tuple2<Long,String>> testPub = TestPublisher.create();

        final Flux<Tuple2<Long,String>> flux = testPub.flux()
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux, 0)
                .expectSubscription()
                .thenRequest(1)
                .then(() -> testPub.next(a, b, c))
                .expectNext(tuple(2000L, "a"))
                .thenAwait()
                .thenRequest(2)
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    public void parallelSchedulersTest() throws InterruptedException, TimeoutException, ExecutionException {
        Schedulers.resetFactory();

        final Flux<Tuple2<Long, String>> flux = Flux.just(a, b, c, d, e, f)
                .publishOn(Schedulers.parallel())
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .delaySequence(Duration.ofSeconds(3))
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        final int numSubscribers = 500;
        final int poolSize = Math.min(numSubscribers, Schedulers.DEFAULT_POOL_SIZE);
        final ExecutorService executorService = Executors.newFixedThreadPool(poolSize);

        final Callable<?> verifier = () -> {
            StepVerifier.create(flux)
                    .expectSubscription()
                    .expectNext(tuple(5000L, "a"))
                    .expectNext(tuple(7000L, "b"))
                    .expectNext(tuple(9000L, "c"))
                    .expectNext(tuple(11000L, "d"))
                    .expectNext(tuple(13000L, "e"))
                    .expectNext(tuple(15000L, "f"))
                    .verifyComplete();
            return null;
        };

        final List<? extends Future<?>> tasks = executorService.invokeAll(nCopies(numSubscribers, verifier));

        Thread.sleep(20L);

        for (Future<?> task : tasks) {
            // will throw any exceptions that occurred in the Callable
            task.get(10, TimeUnit.SECONDS);
        }
    }

}
