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

import org.jetbrains.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.testng.collections.Lists;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.sireum.hooks.TestConstants.tuple;

public class Verifier<T> {

    private final Flux<T> flux;
    private final Supplier<Flux<T>> vflux;
    private final boolean expectSubscription;

    private interface Step<T> { }

    private static class ExpectNext<T> implements Step<T> {
        final List<T> values;
        ExpectNext(List<T> values) {
            this.values = values;
        }
        @Override
        public String toString() {
            return ".expectNext(" + Arrays.toString(values.toArray()) + ")";
        }
    }

    private static class ExpectNoEvent<T> implements Step<T> {
        final Duration duration;
        public ExpectNoEvent(Duration duration) {
            this.duration = duration;
        }
        @Override
        public String toString() {
            return ".expectNoEvent(" + duration + ")";
        }
    }

    private final List<Step<T>> steps;

    public Verifier(Flux<T> flux, Supplier<Flux<T>> vflux, boolean expectSubscription, List<Step<T>> steps) {
        this.flux = flux;
        this.vflux = vflux;
        this.expectSubscription = expectSubscription;
        this.steps = steps;
    }

    @SafeVarargs
    public final Verifier<T> expectNext(T... values) {
        final Step<T> step = new ExpectNext<>(Arrays.asList(values));
        final List<Step<T>> newList = Collections.unmodifiableList(Lists.merge(this.steps, Collections.singletonList(step)));
        return new Verifier<>(flux, vflux, expectSubscription, newList);
    }

    public final Verifier<T> expectNoEvent(Duration duration) {
        final Step<T> step = new ExpectNoEvent<>(duration);
        final List<Step<T>> newList = Collections.unmodifiableList(Lists.merge(this.steps, Collections.singletonList(step)));
        return new Verifier<>(flux, vflux, expectSubscription, newList);
    }

    public final Verifier<T> expectSubscription() {
        // no op but here to make API match
        return new Verifier<>(flux, vflux, true, steps);
    }

    public final void verifyComplete() {

        // verify instrumented first
        AssertionError instrumentedError = null;
        try {
            verifyInstrumented();
        } catch (AssertionError e) {
            System.err.println("Verify instrumented threw an exception!");
            instrumentedError = e;
        }

        // then relax restrictions on error factories
        VirtualTimeScheduler.reset();
        Schedulers.resetFactory();

        // verify virtual
        AssertionError virtualError = null;
        try {
            verifyVirtual();
        } catch (AssertionError e) {
            System.err.println("Verify virtual threw an exception!");
            virtualError = e;
        }

        if (instrumentedError != null && virtualError != null) {
            System.err.println("instrumented stack track: ");
            instrumentedError.printStackTrace();
            System.err.println("virtual stack track: ");
            virtualError.printStackTrace();
            throw new AssertionError("Both cases failed");
        } else if (instrumentedError != null) {
            throw instrumentedError;
        } else if (virtualError != null) {
            throw  virtualError;
        }

    }

    private StepVerifier.Step<T> createExpectNextStep(StepVerifier.Step<T> current, List<T> values) {
        final int size = values.size();
        switch (size) {
            case 0: return current;
            case 1: return current.expectNext(values.get(0));
            case 2: return current.expectNext(values.get(0),values.get(1));
            case 3: return current.expectNext(values.get(0),values.get(1),values.get(2));
            case 4: return current.expectNext(values.get(0),values.get(1),values.get(2),values.get(3));
            case 5: return current.expectNext(values.get(0),values.get(1),values.get(2),values.get(3),values.get(4));
            case 6: return current.expectNext(values.get(0),values.get(1),values.get(2),values.get(3),values.get(4),values.get(5));
            default: return current.expectNext((T) values.toArray()); // todo can make default, was for sanity check
        }
    }

    private void verifyInstrumented() {
        // create StepVerifier based on template
        StepVerifier.Step<T> head;
        if (expectSubscription) {
            head = StepVerifier.create(flux).expectSubscription();
        } else {
            head = StepVerifier.create(flux);
        }

        for (Step<T> step : steps) {
            if (step instanceof Verifier.ExpectNext) {
                final ExpectNext<T> expectNextStep = (ExpectNext<T>) step;
                head = createExpectNextStep(head, expectNextStep.values);
            } else if (step instanceof Verifier.ExpectNoEvent) {
                // skip expectNoEvent calls because they instrumented should handle this automatically
            } else {
                throw new UnsupportedOperationException("unhandled step type");
            }
        }

        // then run verifyComplete
        head.verifyComplete();
    }

    private void verifyVirtual() {
        // create StepVerifier based on template
        StepVerifier.Step<T> head;
        if (expectSubscription) {
            head = StepVerifier.withVirtualTime(vflux).expectSubscription();
        } else {
            head = StepVerifier.withVirtualTime(vflux);
        }

        for (Step<T> step : steps) {
            if (step instanceof Verifier.ExpectNext) {
                final ExpectNext<T> expectNextStep = (ExpectNext<T>) step;
                head = createExpectNextStep(head, expectNextStep.values);
            } else if (step instanceof Verifier.ExpectNoEvent) {
                final ExpectNoEvent<T> expectNoEventStep = (ExpectNoEvent<T>) step;
                head = head.expectNoEvent(expectNoEventStep.duration);
            } else {
                throw new UnsupportedOperationException("unhandled step type");
            }
        }

        // then run verifyComplete

        head.verifyComplete();
    }

    public static <V> Verifier<V> create(Function<? super Flux<String>, ? extends Publisher<V>> body) {
        return Verifier.create(body, Lists.newArrayList("a", "b", "c", "d", "e", "f"));
    }

    public static <T,V> Verifier<V> create(Function<? super Flux<T>, ? extends Publisher<V>> body, List<T> values) {
        return create(body, values, Duration.ofSeconds(2));
    }

    public static <T,V> Verifier<V> create(Function<? super Flux<T>, ? extends Publisher<V>> body, List<T> values, Duration delay) {
        final List<Tuple2<Long,T>> tuples = IntStream.range(0, values.size())
                .mapToObj(i -> Tuples.of(delay.multipliedBy(i+1).toMillis(), values.get(i))).collect(Collectors.toList());

        final Flux<V> flux = createInstrumentedFluxHead(tuples, null).transform(body).transform(TimeBarriers::EXIT_VIRTUAL_TIME);
        final Supplier<Flux<V>> vflux = () -> createVirtualFluxHead(values, delay).transform(body);

        return new Verifier<>(flux, vflux, false, Collections.emptyList());
    }

    private static <T> Flux<T> createInstrumentedFluxHead(List<Tuple2<Long,T>> values, @Nullable Scheduler scheduler) {
        if (scheduler != null) {
            return Flux.fromIterable(values).publishOn(scheduler)
                    .transform(TimeBarriers::ENTER_VIRTUAL_TIME);
        } else {
            return Flux.fromIterable(values)
                    .transform(TimeBarriers::ENTER_VIRTUAL_TIME);
        }
    }

    private static <T> Flux<T> createVirtualFluxHead(List<T> values, Duration delay) {
        return Flux.fromIterable(values).delayElements(delay);
    }

}
