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
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Collections.nCopies;
import static org.sireum.hooks.TestUtils.*;

public class FluxHooksTest {

    @BeforeMethod
    public void beforeEach() {
        StepVerifier.setDefaultTimeout(DEFAULT_VERIFY_TIMEOUT);
        VirtualTimeScheduler.reset();
        ErrorSchedulerFactory.install();
        verifySchedulerInstallations();
    }

    @AfterMethod
    public static void afterEach() {
        VirtualTimeScheduler.reset();
        Schedulers.resetFactory();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void takeTest() {
        final Function<Flux<String>, Flux<Tuple2<Long, String>>> flux = abcdef -> abcdef
                .timestamp()
                .take(Duration.ofSeconds(6));

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(2000L, "a"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(4000L, "b"))
                .expectNoEvent(Duration.ofSeconds(2))
                // ignoring "c" is expected and also the behavior in a virtual time test
                .verifyComplete();

        final Function<Flux<String>, Flux<Tuple2<Long, String>>> exemplifyingExclusiveCutoff = abcdef -> abcdef
                .timestamp()
                .take(Duration.ofMillis(4001));

        Verifier.create(exemplifyingExclusiveCutoff)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(2000L, "a"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(4000L, "b"))
                .expectNoEvent(Duration.ofMillis(1))
                .verifyComplete();
    }

//    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
//    void simpleBackpressureBufferTest1() {
//        final List<String> dropped = Collections.synchronizedList(new ArrayList<>(6));
//        final Flux<String> flux = Flux.just(a, b, c, d, e, f) // send "a" to "f" with two second delays (see defs)
//                .transform(TimeBarriers::BEGIN_VIRTUAL_TIME)
//                // requests as much as possible from upstream and stores values in a buffer if there is not enough
//                // downstream demand. We have two args for the buffer:
//                //  (1) "Duration.ofSeconds(2)" - let stored elements live for two seconds
//                //  (2) "6" - store up to a max of 6 elements (which is all possible elements in this case)
//                .onBackpressureBuffer(Duration.ofSeconds(2), 6, dropped::add)
//                .delaySequence(Duration.ofSeconds(8))
//                .transform(TimeBarriers::END_VIRTUAL_TIME);
//
//        StepVerifier.create(flux, 3)
//                .expectNext("a", "b", "c")
//                .thenRequest(3)
//                .expectNext("f")
//                .verifyComplete();
//
//        Assert.assertEquals(list("d", "e"), dropped);
//    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void timeoutTest1() {
        final Flux<String> flux = Flux.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timeout(Duration.ofSeconds(2))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectError(TimeoutException.class)
                .verify();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void timeoutTest2() {
        final Flux<String> flux = Flux.just(a, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timeout(Duration.ofSeconds(3), Flux.just("timeout","fallback","flux"))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext("a")
                .expectNext("timeout")
                .expectNext("fallback")
                .expectNext("flux")
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void simpleIntervalTest() {
        Schedulers.resetFactory();
        final Flux<Integer> flux = Flux.range(1, 10)
                .publishOn(Schedulers.parallel())
                .filter(n -> n % 2 == 0) // evens only
                .map(n -> TimeUtils.attachTimestamp(Instant.ofEpochSecond(n), n))
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .skip(Duration.ofSeconds(5))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(6, 8, 10)
                .expectComplete()
                .verify();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void threadTest() {
        Schedulers.resetFactory();

        final Flux<String> flux = Flux.just(Tuples.of(0L, "foo"))
                .publishOn(Schedulers.parallel())
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .map(it -> Thread.currentThread().getName())
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNextMatches(threadName -> threadName.contains("parallel"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void simpleElapsedTest() {
        final Function<Flux<String>, Flux<Tuple2<Long, String>>> flux = abcdef -> abcdef
                .elapsed();

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(2000L, "a"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(2000L, "b"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(2000L, "c"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(2000L, "d"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(2000L, "e"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(2000L, "f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void simpleTimestampTest() {
        final Function<Flux<String>, Flux<Tuple2<Long, String>>> flux = abcdef -> abcdef
                .timestamp();

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(2000L, "a"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(4000L, "b"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(6000L, "c"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(8000L, "d"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(10000L, "e"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(12000L, "f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void elapsedTest() {
        final Function<Flux<String>, Flux<Tuple2<Long, List<String>>>> flux = abcdef -> abcdef
                .delayElements(Duration.ofSeconds(3))
                .buffer(Duration.ofSeconds(6))
                .elapsed();

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(6))
                .expectNext(tuple(6000L, list("a")))
                .expectNoEvent(Duration.ofSeconds(6))
                .expectNext(tuple(6000L, list("b", "c")))
                .expectNoEvent(Duration.ofSeconds(6))
                .expectNext(tuple(6000L, list("d", "e")))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(2000L, list("f")))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void timestampTest() {
        final Function<Flux<String>, Flux<Tuple2<Long, List<String>>>> flux = abcdef -> abcdef
                .delayElements(Duration.ofSeconds(3))
                .buffer(Duration.ofSeconds(6))
                .timestamp();

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(6))
                .expectNext(tuple(6000L, list("a")))
                .expectNoEvent(Duration.ofSeconds(6))
                .expectNext(tuple(12000L, list("b", "c")))
                .expectNoEvent(Duration.ofSeconds(6))
                .expectNext(tuple(18000L, list("d", "e")))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(20000L, list("f")))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void timestampTest2() {
        Schedulers.resetFactory();
        final Function<Flux<String>, Flux<Tuple2<Long, List<String>>>> flux = abcdef -> abcdef
                .delayElements(Duration.ofSeconds(3))
                .buffer(Duration.ofSeconds(6))
                .timestamp();

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(6))
                .expectNext(tuple(6000L, list("a")))
                .expectNoEvent(Duration.ofSeconds(6))
                .expectNext(tuple(12000L, list("b", "c")))
                .expectNoEvent(Duration.ofSeconds(6))
                .expectNext(tuple(18000L, list("d", "e")))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(20000L, list("f")))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void virtualTimestampTest() {
        final Supplier<Flux<Tuple2<Long, List<String>>>> flux = () -> Flux.just("a","b","c","d","e","f")
                .delayElements(Duration.ofSeconds(2))
                .delayElements(Duration.ofSeconds(3))
                .buffer(Duration.ofSeconds(6))
                .timestamp();

        StepVerifier.withVirtualTime(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(6))
                .expectNext(tuple(6000L, list("a")))
                .expectNoEvent(Duration.ofSeconds(6))
                .expectNext(tuple(12000L, list("b", "c")))
                .expectNoEvent(Duration.ofSeconds(6))
                .expectNext(tuple(18000L, list("d", "e")))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(20000L, list("f")))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void doNotInstrumentHintTest() {
        final Flux<Tuple2<Long, List<String>>> flux = Flux.just(a, b, c, d, e, f)
                // "no instrumentation" hint means delayElements "noInstrumentation(joinPoint)" check should be true
                .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT)
                .map(element -> tuple("some random transformation", element))
                .map(Tuple2::getT2)

                // then instrument
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .timestamp()
                .map(Tuple2::getT2)
                .materialize()
                .<String>dematerialize()
                .delayElements(Duration.ofSeconds(3))
                .timestamp()
                .map(Tuple2::getT2)
                .buffer(Duration.ofSeconds(6))
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(6000L, list("a")))
                .expectNext(tuple(12000L, list("b", "c")))
                .expectNext(tuple(18000L, list("d", "e")))
                .expectNext(tuple(20000L, list("f")))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void delayTest() {
        final Function<Flux<String>, Flux<Tuple2<Long, String>>> flux = abcdef -> abcdef
                .delayElements(Duration.ofSeconds(3))
                .elapsed();

        Verifier.create(flux)
                .expectSubscription()
                // unlike
                .expectNoEvent(Duration.ofSeconds(5))
                .expectNext(tuple(5000L, "a"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(tuple(3000L, "b"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(tuple(3000L, "c"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(tuple(3000L, "d"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(tuple(3000L, "e"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(tuple(3000L, "f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void delaySubscriptionDoesNothingTest() {
        final Flux<Tuple2<Long, String>> flux = Flux.just(a, b)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .delaySubscription(Duration.ofSeconds(3)) // no effects on virtual time which starts after subscription
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(2000L, "a"))
                .expectNext(tuple(4000L, "b"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void multipleTimeShiftsTest1() {

        // since all delays are <= the delays between the natural arrival times of a-f

        final Function<Flux<String>, Flux<Tuple2<Long, String>>> flux = abcdef -> abcdef
                .delayElements(Duration.ofSeconds(2))
                .delayElements(Duration.ofSeconds(2))
                .timestamp();

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(6))
                .expectNext(tuple(6000L, "a"))  //  2000ms -->  6000ms due to delays totaling 4000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(8000L, "b"))  //  4000ms -->  8000ms due to delays totaling 4000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(10000L, "c")) //  6000ms --> 10000ms due to delays totaling 4000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(12000L, "d")) //  8000ms --> 12000ms due to delays totaling 4000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(14000L, "e")) // 10000ms --> 14000ms due to delays totaling 4000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(16000L, "f")) // 12000ms --> 16000ms due to delays totaling 4000ms
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void multipleTimeShiftsTest2() {
        final Function<Flux<String>, Flux<Tuple2<Long, String>>> flux = abcdef -> abcdef
                .delayElements(Duration.ofSeconds(2))
                .delayElements(Duration.ofSeconds(3))
                .timestamp();

        // note: if this behavior appears to be "off" see the delayElements documentation.
        //       Since one of the two delays is for 3000ms, but each element arrives 2000ms apart,
        //       each have an extra second of accumulating delay

        // see multipleTimeShiftsTest3 for how one to implement non-accumulating delay with delaySequence

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(7))
                .expectNext(tuple(7000L, "a"))  //  2000ms -->  7000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(tuple(10000L, "b")) //  4000ms --> 10000ms due to delays totaling 5000ms + 1000ms
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(tuple(13000L, "c")) //  6000ms --> 13000ms due to delays totaling 5000ms + 2000ms
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(tuple(16000L, "d")) //  8000ms --> 16000ms due to delays totaling 5000ms + 3000ms
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(tuple(19000L, "e")) // 10000ms --> 19000ms due to delays totaling 5000ms + 4000ms
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(tuple(22000L, "f")) // 12000ms --> 22000ms due to delays totaling 5000ms + 5000ms
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void multipleTimeShiftsTest3() {
        // this test shows how to achieve the behavior one might expect to receive from multipleTimeShiftsTest2

        final Function<Flux<String>, Flux<Tuple2<Long, String>>> flux = abcdef -> abcdef
                .delaySequence(Duration.ofSeconds(2)) // could also be delayElements since value is 2
                .delaySequence(Duration.ofSeconds(3))
                .timestamp();

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(7))
                .expectNext(tuple(7000L, "a"))  //  2000ms -->  7000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(9000L, "b"))  //  4000ms -->  9000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(11000L, "c")) //  6000ms --> 11000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(13000L, "d")) //  8000ms --> 13000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(15000L, "e")) // 10000ms --> 15000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(17000L, "f")) // 12000ms --> 17000ms due to delays totaling 5000ms
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void innerFluxTest1() {
        final Function<Flux<String>, Flux<Tuple2<Long, String>>> flux = abcdef -> abcdef
                .flatMap(letter -> Mono.delay(Duration.ofSeconds(2)).thenReturn(letter))
                .delaySequence(Duration.ofSeconds(3))
                .timestamp();

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(7))
                .expectNext(tuple(7000L, "a"))  //  2000ms -->  7000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(9000L, "b"))  //  4000ms -->  9000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(11000L, "c")) //  6000ms --> 11000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(13000L, "d")) //  8000ms --> 13000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(15000L, "e")) // 10000ms --> 15000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(17000L, "f")) // 12000ms --> 17000ms due to delays totaling 5000ms
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void innerFluxTest2() {
        final Function<Flux<String>, Flux<Tuple2<Long, String>>> flux = abcdef -> abcdef
                .flatMap(letter -> Mono.just(letter).delayElement(Duration.ofSeconds(2)))
                .delaySequence(Duration.ofSeconds(3))
                .timestamp();

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(7))
                .expectNext(tuple(7000L, "a"))  //  2000ms -->  7000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(9000L, "b"))  //  4000ms -->  9000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(11000L, "c")) //  6000ms --> 11000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(13000L, "d")) //  8000ms --> 13000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(15000L, "e")) // 10000ms --> 15000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(17000L, "f")) // 12000ms --> 17000ms due to delays totaling 5000ms
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void innerFluxTest3() {
        final Function<Flux<String>, Flux<Tuple2<Long, String>>> flux = abcdef -> abcdef
                .flatMap(letter -> Flux.just(letter).delaySequence(Duration.ofSeconds(2)))
                .delaySequence(Duration.ofSeconds(3))
                .timestamp();

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(7))
                .expectNext(tuple(7000L, "a"))  //  2000ms -->  7000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(9000L, "b"))  //  4000ms -->  9000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(11000L, "c")) //  6000ms --> 11000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(13000L, "d")) //  8000ms --> 13000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(15000L, "e")) // 10000ms --> 15000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(17000L, "f")) // 12000ms --> 17000ms due to delays totaling 5000ms
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void innerFluxTest4() {
        final Function<Flux<String>, Flux<Tuple2<Long, String>>> flux = abcdef -> abcdef
                .flatMap(letter -> Flux.just(letter).delayElements(Duration.ofSeconds(2)))
                .delaySequence(Duration.ofSeconds(3))
                .timestamp();

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(7))
                .expectNext(tuple(7000L, "a"))  //  2000ms -->  7000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(9000L, "b"))  //  4000ms -->  9000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(11000L, "c")) //  6000ms --> 11000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(13000L, "d")) //  8000ms --> 13000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(15000L, "e")) // 10000ms --> 15000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(17000L, "f")) // 12000ms --> 17000ms due to delays totaling 5000ms
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void innerFluxTest5() {
        final Function<Flux<String>, Flux<Tuple2<Long, String>>> flux = abcdef -> abcdef
                .flatMap(letter -> Mono.just("foo")
                        .delayElement(Duration.ofSeconds(1))
                        .thenReturn(letter)
                        .flatMapMany(sameLetter -> Flux.zip(
                                Flux.just(sameLetter).delayElements(Duration.ofMillis(500)),
                                Mono.just(letter).delayElement(Duration.ofSeconds(1))))
                        .flatMapIterable(zipped -> list(zipped.getT1(), zipped.getT2()))
                        .distinctUntilChanged())
                .delaySequence(Duration.ofSeconds(3))
                .timestamp();

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(7))
                .expectNext(tuple(7000L, "a"))  //  2000ms -->  7000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(9000L, "b"))  //  4000ms -->  9000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(11000L, "c")) //  6000ms --> 11000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(13000L, "d")) //  8000ms --> 13000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(15000L, "e")) // 10000ms --> 15000ms due to delays totaling 5000ms
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(17000L, "f")) // 12000ms --> 17000ms due to delays totaling 5000ms
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void bufferTest() {
        final Function<Flux<String>, Flux<List<String>>> flux = abcdef -> abcdef
                .buffer(Duration.ofSeconds(3));

        // buffers are [startTime, endTime)     i.e. inclusive, exclusive

        // timeline:
        // note: o = buffering elements, x = dropping elements. Capital letter = aligns with event
        // ===============================================
        // events:             a       b       c       d       e       f
        // time(s):    0   1   2   3   4   5   6   7   8   9   0   1   2 ...
        // state:      [----------)[----------)[----------)[----------)[-...
        // includes:       a            b          c, d         e        f

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("a"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("b"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("c", "d"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("e"))
                // no more time needs to pass because the mere evaluation of f upstream
                // causes onComplete() to signal which cuts off the final buffer off short
                // and returns its contents (containing only f) immediately.
                .expectNext(list("f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void exactBuffersBufferTest() {

        // this test should be the SAME as buffersTest()

        // with two Duration args, buffer's signature is: buffer(Duration bufferingTimespan, Duration openBufferEvery)
        // this is "exact buffers" because bufferingTimespan = openBufferEvery
        // see buffer javadoc for more info + image (hint: in intellij press ctrl-j (mac), ctrl-q (windows/linux))

        final Function<Flux<String>, Flux<List<String>>> flux = abcdef -> abcdef
                .buffer(Duration.ofSeconds(3), Duration.ofSeconds(3));

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("a"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("b"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("c", "d"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("e"))
                // no more time needs to pass because the mere evaluation of f upstream
                // causes onComplete() to signal which cuts off the final buffer off short
                // and returns its contents (containing only f) immediately.
                .expectNext(list("f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void droppingBuffersBufferTest() {

        // with two Duration args, buffer's signature is: buffer(Duration bufferingTimespan, Duration openBufferEvery)
        // this is "dropping buffers" because bufferingTimespan < openBufferEvery
        // see buffer javadoc for more info + image (hint: in intellij press ctrl-j (mac), ctrl-q (windows/linux))

        final Function<Flux<String>, Flux<Tuple2<Long,List<String>>>> flux = abcdef -> abcdef
                .buffer(Duration.ofSeconds(2), Duration.ofSeconds(3))
                .timestamp();

        // buffers are [startTime, endTime)     i.e. inclusive, exclusive

        // timeline (notice that events a and d are dropped)
        // note: o = buffering elements, x = dropping elements. Capital letter = aligns with event
        // ===============================================
        // events:         a     b     c     d     e     f
        // time(s):  0  1  2  3  4  5  6  7  8  9  0  1  2
        // state:    ooooooXxxoooOooxxxOoooooXxxoooOooxxxO

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2)) // time = 2 sec
                .expectNext(tuple(2_000L, list()))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 5 sec
                .expectNext(tuple(5_000L, list("b")))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 8 sec
                .expectNext(tuple(8_000L, list("c")))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 11 sec
                // (d is missed)
                .expectNext(tuple(11_000L, list("e")))
                .expectNoEvent(Duration.ofSeconds(1)) // time = 12 sec
                .expectNext(tuple(12_000L, list("f")))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void overlappingBuffersBufferTest() {

        // with two Duration args, buffer's signature is: buffer(Duration bufferingTimespan, Duration openBufferEvery)
        // this is "overlapping buffers" because bufferingTimespan > openBufferEvery
        // see buffer javadoc for more info + image (hint: in intellij press ctrl-j (mac), ctrl-q (windows/linux))

        final Function<Flux<String>, Flux<List<String>>> flux = abcdef -> abcdef
                .buffer(Duration.ofSeconds(4), Duration.ofSeconds(3));

        // buffers are [startTime, endTime)     i.e. inclusive, exclusive

        // timeline:
        // ===============================================
        // events:             a       b       c       d       e       f
        // time(s):    0   1   2   3   4   5   6   7   8   9   0   1   2 ...
        // buffers:    [-------a------)
        //                         [---b-------c--)
        //                                     c-------d------)
        //                                                 [---e-------f--)
        //                                                             f---...

        // sanity check
        StepVerifier.withVirtualTime(() -> Flux.just("a","b","c","d","e","f")
                .delayElements(Duration.ofSeconds(2))
                .buffer(Duration.ofSeconds(4), Duration.ofSeconds(3)))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(4))
                .expectNext(list("a"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("b", "c"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("c", "d"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(list("e", "f"))
                .expectNext(list("f"))
                .verifyComplete();

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(4)) // time = 4 sec
                .expectNext(list("a"))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 7 sec
                .expectNext(list("b", "c"))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 10 sec
                .expectNext(list("c", "d"))
                .expectNoEvent(Duration.ofSeconds(2)) // time = 12 sec
                .expectNext(list("e", "f"))
                // no more time needs to pass because the mere evaluation of f upstream
                // causes onComplete() to signal which cuts off all open buffers
                // and return their contents immediately.
                .expectNext(list("f"))
                .verifyComplete();

        // another example

        final Function<Flux<String>, Flux<List<String>>> flux2 = abcdef -> abcdef
                .buffer(Duration.ofSeconds(5), Duration.ofSeconds(3));

        // timeline:
        // note: o = buffering elements, x = dropping elements. Capital letter = aligns with event
        // ===============================================
        // events:             a       b       c       d       e       f
        // time(s):    0   1   2   3   4   5   6   7   8   9   0   1   2 ...
        // buffers:    [-------a-------b--)
        //                         [---b-------c------)
        //                                     c-------d-------e--)
        //                                                 [---e-------f-...
        //                                                             f---...

        Verifier.create(flux2)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(5)) // time = 5 sec
                .expectNext(list("a", "b"))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 8 sec
                .expectNext(list("b", "c"))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 11 sec
                .expectNext(list("c", "d", "e"))
                .expectNoEvent(Duration.ofSeconds(1)) // time = 12 sec
                .expectNext(list("e", "f"))
                // no more time needs to pass because the mere evaluation of f upstream
                // causes onComplete() to signal which cuts off the final buffer off short
                // and returns its contents (containing only f) immediately.
                .expectNext(list("f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void bufferTimeoutTest1a() {
        final Flux<List<String>> cutoffByDuration = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .bufferTimeout(10, Duration.ofSeconds(1))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(cutoffByDuration)
                .expectSubscription()
                .expectNext(list("a"))
                .expectNext(list("b"))
                .expectNext(list("c"))
                .expectNext(list("d"))
                .expectNext(list("e"))
                .expectNext(list("f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void bufferTimeoutTest1b() {
        final Flux<List<String>> cutoffByDuration = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .bufferTimeout(10, Duration.ofSeconds(2))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(cutoffByDuration)
                .expectSubscription()
                .expectNext(list("a"))
                .expectNext(list("b"))
                .expectNext(list("c"))
                .expectNext(list("d"))
                .expectNext(list("e"))
                .expectNext(list("f"))
                .verifyComplete();

        VirtualTimeScheduler.getOrSet();
        final Supplier<Flux<List<String>>> supplier = () -> Flux.just("a", "b", "c", "d", "e", "f")
                .timestamp()
                .delayElements(Duration.ofSeconds(2))
                .map(Tuple2::getT2)
                .bufferTimeout(10, Duration.ofSeconds(2));


        StepVerifier.withVirtualTime(supplier)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2)) // for initial delay of elements
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(list("a"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(list("b"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(list("c"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(list("d"))
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(list("e"))
                // no more time needs to pass because the mere evaluation of f upstream
                // causes onComplete() to signal which cuts off the final buffer off short
                // and returns its contents (containing only f) immediately.
                .expectNext(list("f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void bufferTimeoutTest1c() {
        final Flux<List<String>> closeBySize = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .bufferTimeout(1, Duration.ofSeconds(20))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(closeBySize)
                .expectSubscription()
                .expectNext(list("a"))
                .expectNext(list("b"))
                .expectNext(list("c"))
                .expectNext(list("d"))
                .expectNext(list("e"))
                .expectNext(list("f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void bufferTimeoutTest1d() {
        final Flux<List<String>> closeBySize = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .bufferTimeout(2, Duration.ofSeconds(20))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(closeBySize)
                .expectSubscription()
                .expectNext(list("a", "b"))
                .expectNext(list("c", "d"))
                .expectNext(list("e", "f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void bufferTimeoutTest2a() {
        final Supplier<List<String>> collectionSupplier = () -> Collections.synchronizedList(new ArrayList<String>(6));
        final Flux<List<String>> cutoffByDuration = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .bufferTimeout(10, Duration.ofSeconds(1), collectionSupplier)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(cutoffByDuration)
                .expectSubscription()
                .expectNext(list("a"))
                .expectNext(list("b"))
                .expectNext(list("c"))
                .expectNext(list("d"))
                .expectNext(list("e"))
                .expectNext(list("f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void bufferTimeoutTest2b() {
        final Supplier<List<String>> collectionSupplier = () -> Collections.synchronizedList(new ArrayList<String>(6));
        final Flux<List<String>> cutoffByDuration = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .bufferTimeout(10, Duration.ofSeconds(2), collectionSupplier)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(cutoffByDuration)
                .expectSubscription()
                .expectNext(list("a"))
                .expectNext(list("b"))
                .expectNext(list("c"))
                .expectNext(list("d"))
                .expectNext(list("e"))
                .expectNext(list("f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void bufferTimeoutTest2c() {
        final Supplier<List<String>> collectionSupplier = () -> Collections.synchronizedList(new ArrayList<String>(6));
        final Flux<List<String>> closeBySize = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .bufferTimeout(1, Duration.ofSeconds(20), collectionSupplier)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(closeBySize)
                .expectSubscription()
                .expectNext(list("a"))
                .expectNext(list("b"))
                .expectNext(list("c"))
                .expectNext(list("d"))
                .expectNext(list("e"))
                .expectNext(list("f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void bufferTimeoutTest2d() {
        final Supplier<List<String>> collectionSupplier = () -> Collections.synchronizedList(new ArrayList<String>(6));
        final Flux<List<String>> closeBySize = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .bufferTimeout(2, Duration.ofSeconds(20), collectionSupplier)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(closeBySize)
                .expectSubscription()
                .expectNext(list("a", "b"))
                .expectNext(list("c", "d"))
                .expectNext(list("e", "f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void windowTest() {
        final Function<Flux<String>, Flux<List<String>>> flux = abcdef -> abcdef
                .window(Duration.ofSeconds(3))
                .flatMap(Flux::collectList);

        // 0  1  2  3  4  5  6  7  8  9  0  1  2  3  4  5  6  7  8
        //       a     b     c     d     e     f
        // [-------)[-------)[-------)[-------)[-------)[-------)[

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("a"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("b"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("c", "d"))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("e"))
//                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list("f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void exactWindowsWindowTest() {

        // this test should be the SAME as buffersTest()

        // with two Duration args, buffer's signature is: buffer(Duration bufferingTimespan, Duration openBufferEvery)
        // this is "exact buffers" because bufferingTimespan = openBufferEvery
        // see buffer javadoc for more info + image (hint: in intellij press ctrl-j (mac), ctrl-q (windows/linux))

        final Function<Flux<String>, Publisher<List<Tuple2<Long,String>>>> fn = flux -> flux
                .timestamp()
                .window(Duration.ofSeconds(3), Duration.ofSeconds(3))
                .flatMap(Flux::collectList);

        //   [start, end)    i.e. inclusive then exclusive
        //   2   4  6   8   10   12 (exclusive to 12)
        //|    |    |     |      |

        Verifier.create(fn)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list(tuple(2000L,"a")))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list(tuple(4000L,"b")))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list(tuple(6000L,"c"), tuple(8000L,"d")))
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(list(tuple(10000L,"e")))
                // due to completion signal, able to end "right away"
                // but the time IS accounted for. Just not by virtual-time scheduler's
                // measurements
                .expectNext(list(tuple(12000L,"f")))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void droppingWindowsWindowTest() {

        // with two Duration args, buffer's signature is: buffer(Duration bufferingTimespan, Duration openBufferEvery)
        // this is "dropping buffers" because bufferingTimespan < openBufferEvery
        // see buffer javadoc for more info + image (hint: in intellij press ctrl-j (mac), ctrl-q (windows/linux))

        final Function<Flux<String>, Flux<Tuple2<Long,List<String>>>> flux = abcdef -> abcdef
                .window(Duration.ofSeconds(2), Duration.ofSeconds(3))
                .flatMap(Flux::collectList)
                .timestamp();

        // windows are [startTime, endTime)     i.e. inclusive, exclusive

        // timeline (notice that events a and d are dropped)
        // note: o = adding elements, x = dropping elements. Capital letter = aligns with event
        // ===============================================
        // events:         a     b     c     d     e     f
        // time(s):  0  1  2  3  4  5  6  7  8  9  0  1  2
        // state:    ooooooXxxoooOooxxxOoooooXxxoooOooxxxO

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2)) // time = 2s
                .expectNext(tuple(2_000L, list()))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 5s
                .expectNext(tuple(5_000L, list("b")))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 9s
                .expectNext(tuple(8_000L, list("c")))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 11s
                // (d is missed)
                .expectNext(tuple(11_000L, list("e")))
                .expectNoEvent(Duration.ofSeconds(1)) // time = 12s
                .expectNext(tuple(12_000L, list("f")))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void overlappingWindowsWindowTest() {

        // with two Duration args, buffer's signature is: buffer(Duration bufferingTimespan, Duration openBufferEvery)
        // this is "overlapping buffers" because bufferingTimespan > openBufferEvery
        // see buffer javadoc for more info + image (hint: in intellij press ctrl-j (mac), ctrl-q (windows/linux))

        final Function<Flux<String>, Flux<List<String>>> flux = abcdef -> abcdef
                .window(Duration.ofSeconds(4), Duration.ofSeconds(3))
                .flatMap(Flux::collectList);

        // fluxes are [startTime, endTime)     i.e. inclusive, exclusive

        // timeline:
        // ===============================================
        // events:             a       b       c       d       e       f
        // time(s):    0   1   2   3   4   5   6   7   8   9   0   1   2 ...
        // buffers:    [-------a------)
        //                         [---b-------c--)
        //                                     c-------d------)
        //                                                 [---e-------f--)
        //                                                             f---...

        Verifier.create(flux)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(4)) // time = 4 sec
                .expectNext(list("a"))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 7 sec
                .expectNext(list("b", "c"))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 10 sec
                .expectNext(list("c", "d"))
                .expectNoEvent(Duration.ofSeconds(2)) // time = 12 sec
                .expectNext(list("e", "f"))
                // no more time needs to pass because the mere evaluation of f upstream
                // causes onComplete() to signal which cuts off all open buffers
                // and return their contents immediately.
                .expectNext(list("f"))
                .verifyComplete();

        // another example

        final Function<Flux<String>, Flux<List<String>>> flux2 = abcdef -> abcdef
                .window(Duration.ofSeconds(5), Duration.ofSeconds(3))
                .flatMap(Flux::collectList);

        // timeline:
        // note: o = buffering elements, x = dropping elements. Capital letter = aligns with event
        // ===============================================
        // events:             a       b       c       d       e       f
        // time(s):    0   1   2   3   4   5   6   7   8   9   0   1   2 ...
        // buffers:    [-------a-------b--)
        //                         [---b-------c------)
        //                                     c-------d-------e--)
        //                                                 [---e-------f-...
        //                                                             f---...

        Verifier.create(flux2)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(5)) // time = 5 sec
                .expectNext(list("a", "b"))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 8 sec
                .expectNext(list("b", "c"))
                .expectNoEvent(Duration.ofSeconds(3)) // time = 11 sec
                .expectNext(list("c", "d", "e"))
                .expectNoEvent(Duration.ofSeconds(1)) // time = 12 sec
                .expectNext(list("e", "f"))
                // no more time needs to pass because the mere evaluation of f upstream
                // causes onComplete() to signal which cuts the final buffer off short
                // and returns its contents (containing only f) immediately.
                .expectNext(list("f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void windowTimeoutTest1() {
        final Supplier<Flux<List<String>>> supplier = () -> Flux.just("a","b","c","d","e","f")
                .delayElements(Duration.ofSeconds(2))
                .windowTimeout(10, Duration.ofSeconds(1))
                .flatMap(Flux::collectList);

        final Flux<List<String>> cutoffByDuration = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .windowTimeout(10, Duration.ofSeconds(1))
                .flatMap(Flux::collectList)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        // timeline:
        // note: o = buffering elements, x = dropping elements. Capital letter = aligns with event.
        //       [ = open buffer (inclusive), ) = close buffer (exclusive)
        // ===============================================
        // events:             a       b       c       d       e       f
        // time(s):    0   1   2   3   4   5   6   7   8   9   0   1   2 ...
        // buffers:    [---)
        //                 [---)
        //                     a---)
        //                         [---)
        //                             b---)
        //                                 [---)
        //                                     c---)
        //                                         [---)
        //                                             d---)
        //                                                 [---)
        //                                                     e---)
        //                                                         [---)
        //                                                             f-... (cutoff immediately by onComplete)

        StepVerifier.create(cutoffByDuration)
                .expectSubscription()
                .expectNext(list())
                .expectNext(list())
                .expectNext(list("a"))
                .expectNext(list())
                .expectNext(list("b"))
                .expectNext(list())
                .expectNext(list("c"))
                .expectNext(list())
                .expectNext(list("d"))
                .expectNext(list())
                .expectNext(list("e"))
                .expectNext(list())
                .expectNext(list("f"))
                .verifyComplete();

        Schedulers.resetFactory();

        StepVerifier.withVirtualTime(supplier)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(list())
                // todo look into why this is needed in normal but not virtual
//                .expectNoEvent(Duration.ofSeconds(1))
//                .expectNext(list())
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(list("a"))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(list())
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(list("b"))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(list())
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(list("c"))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(list())
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(list("d"))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(list())
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(list("e"))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(list())
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(list("f"))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void windowTimeoutTest2() {

        final Function<Flux<String>,Flux<Tuple2<Long,List<String>>>> cutoffByDuration = abcdef -> abcdef
                .windowTimeout(10, Duration.ofSeconds(2))
                .flatMap(Flux::collectList)
                .timestamp();

        // timeline:
        // note: o = buffering elements, x = dropping elements. Capital letter = aligns with event
        // ===============================================
        // events:             a       b       c       d       e       f
        // time(s):    0   1   2   3   4   5   6   7   8   9   0   1   2 ...
        // buffers:    [-------)
        //                     a-------)
        //                             b-------)
        //                                     c-------)
        //                                             d-------)
        //                                                     e-------)
        //                                                             f-... (cutoff immediately by onComplete)

        Verifier.create(cutoffByDuration)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2)) // time = 2 sec
                .expectNext(tuple(2000L, list()))
                .expectNoEvent(Duration.ofSeconds(2)) // time = 4 sec
                .expectNext(tuple(4000L, list("a")))
                .expectNoEvent(Duration.ofSeconds(2)) // time = 6 sec
                .expectNext(tuple(6000L, list("b")))
                .expectNoEvent(Duration.ofSeconds(2)) // time = 8 sec
                .expectNext(tuple(8000L, list("c")))
                .expectNoEvent(Duration.ofSeconds(2)) // time = 10 sec
                .expectNext(tuple(10000L, list("d")))
                .expectNoEvent(Duration.ofSeconds(2)) // time = 12 sec
                .expectNext(tuple(12000L, list("e")))
                // with event f comes an onComplete() signal which
                // ends the open buffer prematurely, making it also end at time 12 sec
                .expectNext(tuple(12000L, list("f"))) // time = STILL 12
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void windowTimeoutTest3() {
        final Flux<List<String>> closeBySize = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .windowTimeout(1, Duration.ofSeconds(20))
                .flatMap(Flux::collectList)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(closeBySize)
                .expectSubscription()
                .expectNext(list("a"))
                .expectNext(list("b"))
                .expectNext(list("c"))
                .expectNext(list("d"))
                .expectNext(list("e"))
                .expectNext(list("f"))
                .expectNext(list())
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void windowTimeoutTest4() {
        final Flux<List<String>> closeBySize = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .windowTimeout(2, Duration.ofSeconds(20))
                .flatMap(Flux::collectList)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(closeBySize)
                .expectSubscription()
                .expectNext(list("a", "b"))
                .expectNext(list("c", "d"))
                .expectNext(list("e", "f"))
                .expectNext(list())
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void skipBehaviorTest_OneSkip() {

        final Flux<Tuple2<Long,Integer>> secondCounter =
                Flux.range(0, Integer.MAX_VALUE).map(n -> tuple((n+1)*1000L, n));

        /*
         * In reactor, multiple chained skips such as:
         *      foo.skip(Duration.ofSeconds(1)).skip(Duration.ofSeconds(1))
         * is NOT the same as foo.skip(Duration.ofSeconds(2)) because each is running from the same
         * start time. In other words, both skips act as a filter, but do not advance the time.
         *
         * For an operator that advances the time (rather than just skipping it) see operators starting with "delay"
         *
         * This test exists as a reminder that this is the correct behavior.
         */

        final Flux<Tuple2<Long, Integer>> oneSkip = secondCounter
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .skip(Duration.ofSeconds(2))
                .timestamp()
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        final Supplier<Flux<Tuple2<Long,Integer>>> oneSkipRef = () -> Flux.range(0, Integer.MAX_VALUE)
                .delayElements(Duration.ofSeconds(1))
                .skip(Duration.ofSeconds(2))
                .take(5)
                .timestamp();

        StepVerifier.create(oneSkip)
                .expectSubscription()
                .expectNext(tuple(2000L, 1))
                .expectNext(tuple(3000L, 2))
                .expectNext(tuple(4000L, 3))
                .expectNext(tuple(5000L, 4))
                .expectNext(tuple(6000L, 5))
                .verifyComplete();

        StepVerifier.withVirtualTime(oneSkipRef, 5)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(tuple(2000L, 1))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple(3000L, 2))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple(4000L, 3))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple(5000L, 4))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple(6000L, 5))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void skipBehaviorTest_twoSkips() {

        final Flux<Tuple2<Long,Integer>> secondCounter =
                Flux.range(0, Integer.MAX_VALUE).map(n -> tuple((n+1)*1000L, n));

        /*
         * In reactor, multiple chained skips such as:
         *      foo.skip(Duration.ofSeconds(1)).skip(Duration.ofSeconds(1))
         * is NOT the same as foo.skip(Duration.ofSeconds(2)) because each is running from the same
         * start time. In other words, both skips act as a filter, but do not advance the time.
         *
         * For an operator that advances the time (rather than just skipping it) see operators starting with "delay"
         *
         * This test exists as a reminder that this is the correct behavior.
         */

        final Flux<Tuple2<Long, Integer>> twoSkips = secondCounter
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .skip(Duration.ofSeconds(1))
                .skip(Duration.ofSeconds(1))
                .timestamp()
                .take(5)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        final Supplier<Flux<Tuple2<Long,Integer>>> twoSkipsRef = () -> Flux.range(0, Integer.MAX_VALUE)
                .delayElements(Duration.ofSeconds(1))
                .skip(Duration.ofSeconds(1))
                .skip(Duration.ofSeconds(1))
                .take(5)
                .timestamp();

        StepVerifier.create(twoSkips)
                .expectSubscription()
                .expectNext(tuple(1000L, 0))
                .expectNext(tuple(2000L, 1))
                .expectNext(tuple(3000L, 2))
                .expectNext(tuple(4000L, 3))
                .expectNext(tuple(5000L, 4))
                .verifyComplete();

        StepVerifier.withVirtualTime(twoSkipsRef, 5)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple(1000L, 0))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple(2000L, 1))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple(3000L, 2))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple(4000L, 3))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple(5000L, 4))
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void multiSubscriberTest() throws InterruptedException, TimeoutException, ExecutionException {
        final PublisherProbe<Tuple2<Long, List<String>>> probe = PublisherProbe.of(Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME) // doesn't have to be deferred
                .delayElements(Duration.ofSeconds(3))
                .buffer(Duration.ofSeconds(6))
                .elapsed()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME)); // must be deferred

        final int numSubscribers = 6;
        final int poolSize = Math.min(numSubscribers, Schedulers.DEFAULT_POOL_SIZE);
        final ExecutorService executorService = Executors.newFixedThreadPool(poolSize);

        final Callable<?> verifier = () -> {
            StepVerifier.create(probe.flux())
                    .expectSubscription()
                    .expectNext(tuple(6000L, list("a")))
                    .expectNext(tuple(6000L, list("b", "c")))
                    .expectNext(tuple(6000L, list("d", "e")))
                    .expectNext(tuple(2000L, list("f")))
                    .verifyComplete();
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

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void reentrantVirtualTimeTest1() {
        final Flux<Tuple2<Long, List<String>>> bufferedLetters = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .buffer(Duration.ofSeconds(6))
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        final Flux<Integer> evenNumbers = Flux.just(1, 2, 3).map(n -> n * 2);

        final Flux<Object> flux = Flux.concat(bufferedLetters, evenNumbers, bufferedLetters);

        // a [2 sec],  b [4 sec]
        // c [6 sec], d [8 sec], e [10 sec]
        // f [12 sec]

        // there is no real VirtualTimeScheduler equivalent to compare this to

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple(6000L, list("a", "b")))
                .expectNext(tuple(12000L, list("c", "d", "e")))
                .expectNext(tuple(12000L, list("f"))) // f is at 12000L due to onComplete cutoff
                .expectNext(2, 4, 6)
                .expectNext(tuple(6000L, list("a", "b")))
                .expectNext(tuple(12000L, list("c", "d", "e")))
                .expectNext(tuple(12000L, list("f"))) // f is at 12000L due to onComplete cutoff
                .verifyComplete();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void reentrantVirtualTimeTest2() throws InterruptedException, TimeoutException, ExecutionException {
        Schedulers.resetFactory();

        final Flux<Tuple2<Long, List<String>>> bufferedLetters = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .buffer(Duration.ofSeconds(6))
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        final Flux<Integer> middle = Flux.just(0, 1, 2);

        final Supplier<Flux<Object>> flux = () ->
                Flux.concat(bufferedLetters, middle, bufferedLetters);

        final int numSubscribers = 8;
        final int poolSize = Math.min(numSubscribers, Schedulers.DEFAULT_POOL_SIZE);
        final ExecutorService executorService = Executors.newFixedThreadPool(poolSize);

        final Callable<?> verifier = () -> {
            final Runnable realTimePause = () -> {
                final long time = (long) (Math.random() * 10);
                try {
                    Thread.sleep(time);
                } catch (InterruptedException interruptedException) {
                    throw new IllegalStateException(interruptedException);
                }
            };
            StepVerifier.create(flux.get(), 15)
                    .expectSubscription()
                    .expectNext(tuple(6000L, list("a", "b")))
                    .expectNext(tuple(12000L, list("c", "d", "e")))
                    .expectNext(tuple(12000L, list("f")))
                    .then(realTimePause)
                    .expectNext(0)
                    .then(realTimePause)
                    .expectNext(1)
                    .then(realTimePause)
                    .expectNext(2)
                    .expectNext(tuple(6000L, list("a", "b")))
                    .expectNext(tuple(12000L, list("c", "d", "e")))
                    .expectNext(tuple(12000L, list("f")))
                    .verifyComplete();
            return null;
        };

        final List<? extends Future<?>> tasks = executorService.invokeAll(nCopies(numSubscribers, verifier));

        for (Future<?> task : tasks) {
            // will throw any exceptions that occurred in the Callable
            task.get(5, TimeUnit.SECONDS);
        }
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void cacheTest1() {
        final Flux<String> flux = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .cache(Duration.ofSeconds(2))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .verifyError(AssemblyInstrumentationException.class);
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void cacheTest2() {
        final Flux<String> flux = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .cache(6, Duration.ofSeconds(2))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .verifyError(AssemblyInstrumentationException.class);
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void replayTest1() {
        Schedulers.resetFactory();

        // replay throws instantly due to issue with propagating error signal and converting to connectable flux
        Assert.assertThrows(AssemblyInstrumentationException.class, () ->
                Flux.just(a, b, c, d, e, f)
                        .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                        .replay(Duration.ofSeconds(2))
                        .transform(TimeBarriers::EXIT_VIRTUAL_TIME));

        // it also throws it cannot find a noInstrumentation hint at assembly time
        // because its impossible to do the proper check at subscription time
        Assert.assertThrows(AssemblyInstrumentationException.class, () ->
                Flux.just(a, b, c, d, e, f).replay(Duration.ofSeconds(2)));

        // this is not an issue if noInstrumentation is inferred after an EXIT_VIRTUAL_TIME
        Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME)
                .replay(Duration.ofSeconds(2))
                .connect();

        // otherwise the user can work around this with a hint if noInstrumentation can not be inferred
        Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT)
                .replay(Duration.ofSeconds(2))
                .connect();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void replayTest2() {
        Schedulers.resetFactory();

        // replay throws instantly due to issue with propagating error signal and converting to connectable flux
        Assert.assertThrows(AssemblyInstrumentationException.class, () ->
                Flux.just(a, b, c, d, e, f)
                        .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                        .replay(1, Duration.ofSeconds(2))
                        .transform(TimeBarriers::EXIT_VIRTUAL_TIME));

        // it also throws it cannot find a noInstrumentation hint at assembly time
        // because its impossible to do the proper check at subscription time
        Assert.assertThrows(AssemblyInstrumentationException.class, () ->
                Flux.just(a, b, c, d, e, f).replay(1, Duration.ofSeconds(2)));

        // this is not an issue if noInstrumentation can be inferred
        Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME)
                .replay(1, Duration.ofSeconds(2))
                .connect();

        // and the user can always work around this with a hint if noInstrumentation can not be inferred
        Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT)
                .replay(1, Duration.ofSeconds(2))
                .connect();
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void onBackpressureBufferTest() {
        final Flux<String> flux = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .onBackpressureBuffer(Duration.ofSeconds(1), 6, s -> {  })
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .verifyError(AssemblyInstrumentationException.class);
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void retryWhenTest() {
        final Flux<String> flux = Flux.just(a, b, c, d, e, f)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .retryWhen(Retry.indefinitely())
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .verifyError(AssemblyInstrumentationException.class);
    }

    @Test//(timeOut = DEFAULT_TEST_TIMEOUT)
    void flatMapIntervalTest() {
        final Flux<Tuple2<Long, String>> flux = Flux.just(a, b, c, d)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .flatMap(element -> Flux.interval(Duration.ofSeconds(1)).map(n -> element))
                .take(20)
                .timestamp()
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(flux)
                .expectSubscription()
                .expectNext(tuple( 3000L, "a"))
                .expectNext(tuple( 4000L, "a"))
                .expectNext(tuple( 5000L, "a"), tuple( 5000L, "b"))
                .expectNext(tuple( 6000L, "a"), tuple( 6000L, "b"))
                .expectNext(tuple( 7000L, "a"), tuple( 7000L, "b"), tuple( 7000L, "c"))
                .expectNext(tuple( 8000L, "a"), tuple( 8000L, "b"), tuple( 8000L, "c"))
                .expectNext(tuple( 9000L, "a"), tuple( 9000L, "b"), tuple( 9000L, "c"), tuple( 9000L, "d"))
                .expectNext(tuple(10000L, "a"), tuple(10000L, "b"), tuple(10000L, "c"), tuple(10000L, "d"))
                .verifyComplete();

        final Supplier<Flux<Tuple2<Long, String>>> supplier = () -> Flux.just("a", "b", "c", "d")
                .delayElements(Duration.ofSeconds(2))
                .flatMap(element -> Flux.interval(Duration.ofSeconds(1)).map(n -> element))
                .take(20)
                .timestamp();

        Schedulers.resetFactory();
        VirtualTimeScheduler.getOrSet();

        StepVerifier.withVirtualTime(supplier)
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(3))
                .expectNext(tuple( 3000L, "a"))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple( 4000L, "a"))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple( 5000L, "b"), tuple( 5000L, "a"))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple( 6000L, "b"), tuple( 6000L, "a"))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple( 7000L, "c"), tuple( 7000L, "b"), tuple( 7000L, "a"))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple( 8000L, "c"), tuple( 8000L, "b"), tuple( 8000L, "a"))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple( 9000L, "d"), tuple( 9000L, "c"), tuple( 9000L, "b"), tuple( 9000L, "a"))
                .expectNoEvent(Duration.ofSeconds(1))
                .expectNext(tuple(10000L, "d"), tuple(10000L, "c"), tuple(10000L, "b"), tuple(10000L, "a"))
                .verifyComplete();
    }

    @Test(timeOut = DEFAULT_TEST_TIMEOUT, invocationCount = 2)
    void verifySchedulerErrorFactoryInstallation() {
        verifySchedulerInstallations();

        // install a VirtualTimeScheduler
        // since this is a repeated test, this will cause an exception if the @BeforeEach is not resetting properly
        VirtualTimeScheduler.getOrSet();
    }

}