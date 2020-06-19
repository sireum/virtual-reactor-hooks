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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.*;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.sireum.hooks.TimeBarriers.SCHEDULER_CONTEXT_KEY;

final class BarrierAssembly {

    @NotNull
    static <T> Flux<T> fluxBegin(@NotNull Flux<Tuple2<Long,T>> source, @NotNull Supplier<Instant> stopTime) {
        return FluxAssembly.begin(source, stopTime);
    }

    @NotNull
    static <T> Flux<T> fluxEnd(@NotNull Flux<T> source, @NotNull Supplier<Instant> startTime) {
        return FluxAssembly.end(source, startTime);
    }

    @NotNull
    static <T> Mono<T> monoBegin(@NotNull Mono<Tuple2<Long,T>> source, @NotNull Supplier<Instant> stopTime) {
        return MonoAssembly.begin(source, stopTime);
    }

    @NotNull
    static <T> Mono<T> monoEnd(@NotNull Mono<T> source, @NotNull Supplier<Instant> startTime) {
        return MonoAssembly.end(source, startTime);
    }

    private abstract static class FluxAssembly<T> extends Flux<Tuple2<Long,T>> {

        @NotNull
        static <T> Flux<T> begin(@NotNull Flux<Tuple2<Long,T>> source, @NotNull Supplier<Instant> stopTime) {
            return onAssembly(new FluxBeginVirtualTimeOperator<>(source, stopTime));
        }

        @NotNull
        static <T> Flux<T> end(@NotNull Flux<T> source, @NotNull Supplier<Instant> startTime) {
            return onAssembly(new FluxEndVirtualTimeOperator<>(source, startTime));
        }

        private FluxAssembly() {
            throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
        }
    }

    private abstract static class MonoAssembly<T> extends Mono<Tuple2<Long,T>> {

        @NotNull
        static <T> Mono<T> begin(@NotNull Mono<Tuple2<Long,T>> source, @NotNull Supplier<Instant> stopTime) {
            return onAssembly(new MonoBeginVirtualTimeOperator<>(source, stopTime));
        }

        @NotNull
        static <T> Mono<T> end(@NotNull Mono<T> source, @NotNull Supplier<Instant> startTime) {
            return onAssembly(new MonoEndVirtualTimeOperator<>(source, startTime));
        }

        private MonoAssembly() {
            throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
        }
    }

    private static final class FluxBeginVirtualTimeOperator<T> extends FluxOperator<Tuple2<Long,T>,T> {

        @NotNull
        private final Supplier<Instant> stopTime;

        protected FluxBeginVirtualTimeOperator(@NotNull Flux<Tuple2<Long, T>> source, @NotNull Supplier<Instant> stopTime) {
            super(source);
            this.stopTime = stopTime;
        }

        @Override
        public void subscribe(@NotNull CoreSubscriber<? super T> actual) {
            source.subscribe(new BarrierBeginInnerOperator<>(actual, stopTime));
        }
    }

    private static final class FluxEndVirtualTimeOperator<T> extends FluxOperator<T,T> {

        @NotNull
        private final Supplier<Instant> startTime;

        protected FluxEndVirtualTimeOperator(@NotNull Flux<? extends T> source, @NotNull Supplier<Instant> startTime) {
            super(source);
            this.startTime = startTime;
        }

        @Override
        public void subscribe(@NotNull CoreSubscriber<? super T> actual) {
            source.subscribe(new BarrierEndInnerOperator<>(actual, startTime));
        }
    }

    private static final class MonoBeginVirtualTimeOperator<T> extends MonoOperator<Tuple2<Long,T>,T> {

        @NotNull
        private final Supplier<Instant> stopTime;

        protected MonoBeginVirtualTimeOperator(@NotNull Mono<Tuple2<Long,T>> source, @NotNull Supplier<Instant> stopTime) {
            super(source);
            this.stopTime = stopTime;
        }

        @Override
        public void subscribe(@NotNull CoreSubscriber<? super T> actual) {
            source.subscribe(new BarrierBeginInnerOperator<>(actual, stopTime));
        }
    }

    private static final class MonoEndVirtualTimeOperator<T> extends MonoOperator<T,T> {

        @NotNull
        private final Supplier<Instant> startTime;

        protected MonoEndVirtualTimeOperator(@NotNull Mono<? extends T> source, @NotNull Supplier<Instant> startTime) {
            super(source);
            this.startTime = startTime;
        }

        @Override
        public void subscribe(@NotNull CoreSubscriber<? super T> actual) {
            source.subscribe(new BarrierEndInnerOperator<>(actual, startTime));
        }
    }

    private static final class BarrierEndInnerOperator<T> implements CoreSubscriber<T>, Subscription {

        private final CoreSubscriber<? super T> actual;
        private final Context context;

        final VirtualTimeScheduler scheduler;

        private volatile boolean done;
        private volatile Subscription s;

        BarrierEndInnerOperator(@NotNull CoreSubscriber<? super T> actual, @NotNull Supplier<Instant> startTime) {
            this.actual = actual;
            this.scheduler = VirtualTimeScheduler.create();
            this.scheduler.advanceTimeTo(startTime.get());
            this.context = Context.of(TimeBarriers.SCHEDULER_CONTEXT_KEY, scheduler);
        }

        @Override
        public void request(long n) {
            if (Operators.validate(n)) {
                s.request(n);
            } else {
            }
        }

        @Override
        public void cancel() {
            // "done" variable is intentionally left unchanged in this method
            s.cancel();
            // todo operators.terminate?
        }

        @Override
        public void onSubscribe(@NotNull Subscription s) {
            if (Operators.validate(this.s, s)) {
                this.s = s;
                actual.onSubscribe(this);
            } else {
            }
        }

        @Override
        public void onNext(T t) {
            if (done) {
                Operators.onNextDropped(t, actual.currentContext());
            } else {
                actual.onNext(t);
            }
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                Operators.onErrorDropped(t, context);
            } else {
                done = true;
                actual.onError(t);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                actual.onComplete();
            } else {
            }
        }

        @NotNull
        @Override
        public Context currentContext() {
            return context;
        }

    }

    private static final class BarrierBeginInnerOperator<T> implements CoreSubscriber<Tuple2<Long,T>>, Subscription {

        private final CoreSubscriber<? super T> actual;
        private final Context context;

        @Nullable
        private final VirtualTimeScheduler scheduler;

        private final Supplier<Instant> stopTime;

        private volatile boolean done;
        private volatile Subscription s;

        BarrierBeginInnerOperator(@NotNull CoreSubscriber<? super T> actual, @NotNull Supplier<Instant> stopTime) {
            this.actual = actual;
            this.stopTime = stopTime;
            this.context = actual.currentContext();
            this.scheduler = context.getOrDefault(SCHEDULER_CONTEXT_KEY, null);
        }

        @Override
        public void request(long n) {
            if (Operators.validate(n)) {
                s.request(n);
            }
        }

        @Override
        public void cancel() {
            // done intentionally not changed
            s.cancel();
        }

        @Override
        public void onSubscribe(@NotNull Subscription s) {
            if (Operators.validate(this.s, s)) {
                this.s = s;
                if (scheduler == null) {
                    // by spec, onSubscribe MUST be called before onError, and Operators.error ensures this
                    Operators.error(actual, Operators.onOperatorError(missingSchedulerException(), context));
                } else {
                    actual.onSubscribe(this);
                }
            }
        }

        @Override
        public void onNext(Tuple2<Long,T> t) {
            if (done) {
                Operators.onNextDropped(t, actual.currentContext());
            } else {
                scheduler.advanceTimeTo(Instant.ofEpochMilli(t.getT1()));
                actual.onNext(t.getT2());
            }
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                Operators.onErrorDropped(t, context);
            } else {
                done = true;
                actual.onError(t);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                actual.onComplete(); // must occur before advanceTimeTo (or risk an infinite loop)
                scheduler.advanceTimeTo(stopTime.get());
            }
        }

        @NotNull
        @Override
        public Context currentContext() {
            return context;
        }

        private static InstrumentationSubscriptionException missingSchedulerException() {
            return new InstrumentationSubscriptionException("Subscription-time logic of TimeBarrier's " +
                    "BEGIN_VIRTUAL_TIME(Flux) was invoked, but no VirtualTimeScheduler was found in the " +
                    "subscriberContext. Is there a (uniquely) corresponding downstream transform" +
                    "(TimeBarriers::END_VIRTUAL_TIME) added to the assembly chain (at the same operator depth)?");
        }
    }

    private BarrierAssembly() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
