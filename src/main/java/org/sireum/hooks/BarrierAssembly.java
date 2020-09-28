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

import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Scannable;
import reactor.core.publisher.*;
import reactor.util.annotation.NonNull;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.sireum.hooks.PackageUtils.SCHEDULER_CONTEXT_KEY;

/**
 * Implementation of time barrier operators. These operators are exposed to the package via "assembly" methods which
 * allow them to be intercepted by Reactor's {@link Hooks} pointcut (not to be confused for AspectJ's pointcuts).
 */
final class BarrierAssembly {

    /**
     * This is a utility class and cannot be instantiated.
     */
    private BarrierAssembly() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Package-private entry point for {@link TimeBarriers#ENTER_VIRTUAL_TIME(Flux, Supplier, BiFunction, Function)}
     * implementation. The result will be made a candidate for Reactor's {@link Hooks} by calling onAssembly.
     *
     * @param source the {@link Flux} whose values will be entered into virtual time
     * @param initial {@link Supplier} returning the initial value of some arbitrary state-tracker. This value is
     *                resolved when wrapper's subscribe is called.
     * @param accumulator {@link BiFunction} which, given the state-tracker's current value and the virtual section's
     *                    next value (including timestamp), returns an updated the state tracker. Each new
     *                    value is resolved whenever onNext is called.
     * @param extractor {@link Function} which returns the virtual-section's final STOP time as an {@link Instant} based
     *                                  on the state-tracker's value when onComplete is called. See
     *                                  {@link UnreachableTimeException} and {@link UnsupportedTimeException} for
     *                                  limitations on what this {@link Instant} can be.
     *
     * @param <T> the type of value emitted by the source {@link Flux}
     * @param <A> the type of the accumulator / state-tracker managed as values are pushed into the virtual section
     * @return a {@link Flux} of the same values, without timestamps, and within an unclosed virtual section
     */
    @NonNull
    static <T,A> Flux<T> fluxBegin(@NonNull Flux<Tuple2<Long,T>> source,
                                   @NonNull Supplier<A> initial,
                                   @NonNull BiFunction<A, ? super Tuple2<Instant,T>, ?  extends A> accumulator,
                                   @NonNull Function<A, Instant> extractor) {
        return FluxAssembly.begin(source, initial, accumulator, extractor);
    }

    /**
     * Given a {@link Flux} within an unclosed virtual section, returns a {@link Flux} that closes the virtual section
     * and emits those same values.
     * <br>
     * This method also accepts a {@link Supplier} that returns the START time of this virtual section. This
     * {@link Supplier} is evaluated in the subscribe wrapper that creates the inner operator (after assembly).
     * <br>
     * The returned {@link Flux} will onError with an {@link AssemblyInstrumentationException}
     * if the virtual section is not closed with a downstream EXIT_VIRTUAL_TIME operator before subscription.
     * <br>
     * The returned {@link Flux} may onError an {@link UnsupportedTimeException} if startTime returns an unsupported
     * time. See {@link UnsupportedTimeException} for what constitutes a supported or unsupported time.
     * <br>
     * The returned {@link Flux} may onError an {@link UnreachableTimeException} if startTime returns an unreachable
     * time. This cannot happen under normal circumstances since the scheduler starts and the lowest supported time.
     *
     * @param source the currently-virtual {@link Flux} whose values will be exited from virtual time
     * @param startTime a {@link Supplier} returning the time that each subscriber's virtual-section should START
     * @param <T> the type of value emitted by the source {@link Flux}
     * @return a {@link Flux} of the same values that is no longer in virtual time
     */
    @NonNull
    static <T> Flux<T> fluxEnd(@NonNull Flux<T> source, @NonNull Supplier<Instant> startTime) {
        return FluxAssembly.end(source, startTime);
    }

    /**
     * The {@link Mono}-equivalent of {@link BarrierAssembly#fluxBegin(Flux, Supplier, BiFunction, Function)}.
     *
     * @param source the {@link Mono} that will be entered into virtual time
     * @param initial {@link Supplier} returning the initial value of some arbitrary state-tracker. This value is
     *                resolved when wrapper's subscribe is called.
     * @param accumulator {@link BiFunction} which, given the state-tracker's initial value and the source
     *                    {@link Mono}'s onNext value (including timestamp), returns an updated state tracker.
     *                    If the source onCompletes with no value, this accumulator is never called.
     * @param extractor {@link Function} which returns the virtual-section's final STOP time as an {@link Instant} based
     *                  on the state-tracker's value when onComplete is called. See
     *                  {@link UnreachableTimeException} and {@link UnsupportedTimeException} for
     *                  limitations on what this {@link Instant} can be.
     *
     * @param <T> the type of value emitted by the source {@link Mono}
     * @param <A> the type of the accumulator / state-tracker managed when the value is pushed into the virtual section
     * @return a {@link Mono} of the same value, without its timestamp, and within an unclosed virtual section
     */
    @NonNull
    static <T,A> Mono<T> monoBegin(@NonNull Mono<Tuple2<Long,T>> source,
                                   @NonNull Supplier<A> initial,
                                   @NonNull BiFunction<A, ? super Tuple2<Instant,T>, ?  extends A> accumulator,
                                   @NonNull Function<A, Instant> extractor) {
        return MonoAssembly.begin(source, initial, accumulator, extractor);
    }

    /**
     * The {@link Mono}-equivalent of {@link BarrierAssembly#fluxEnd(Flux, Supplier)}.
     *
     * @param source the currently-virtual {@link Mono} that will be exited from virtual time
     * @param startTime a {@link Supplier} returning the time that each subscriber's virtual-section should START
     * @param <T> the type of value emitted by the source {@link Mono}
     * @return a {@link Mono} of the same value that is no longer in virtual time
     */
    @NonNull
    static <T> Mono<T> monoEnd(@NonNull Mono<T> source, @NonNull Supplier<Instant> startTime) {
        return MonoAssembly.end(source, startTime);
    }

    /**
     * Utility class containing virtual-time "begin" and "end" operators for {@link Flux}
     * {@link org.reactivestreams.Publisher}s. This class only exists because {@link Flux}'s onAssembly method has
     * protected access and thus virtual-time factory methods must eventually delegate to method which extends
     * {@link Flux} and is therefore capable of calling onAssembly.
     *
     * @param <T> the inbound type to be timestamped via a (Long,<T>) {@link Tuple2}.
     */
    private abstract static class FluxAssembly<T> extends Flux<Tuple2<Long,T>> {

        /**
         * This is a utility class and cannot be instantiated.
         */
        private FluxAssembly() {
            throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
        }

        /**
         * File-private delegate method to {@link BarrierAssembly#fluxBegin(Flux, Supplier, BiFunction, Function)} which
         * is contained in a {@link Flux}-extending class so that {@link Flux}'s protected onAssembly method can be
         * called to support user {@link Hooks}.
         *
         * @param source the {@link Flux} whose values will be entered into virtual time.
         * @param initial {@link Supplier} returning the initial value of some arbitrary state-tracker. This value is
         *                resolved when wrapper's subscribe is called.
         * @param accumulator {@link BiFunction} which, given the state-tracker's current value and the virtual section's
         *                    next value (including timestamp), returns an updated the state tracker. Each new
         *                    value is resolved whenever onNext is called.
         * @param extractor {@link Function} which returns the virtual-section's final STOP time as an {@link Instant} based
         *                                  on the state-tracker's value when onComplete is called. See
         *                                  {@link UnreachableTimeException} and {@link UnsupportedTimeException} for
         *                                  limitations on what this {@link Instant} can be.
         * @param <T> the type of value emitted by the source {@link Flux}
         * @param <A> the type of the accumulator / state-tracker managed as values are pushed into the virtual section
         * @return a {@link Flux} of the same values, without timestamps, and within an unclosed virtual section
         */
        @NonNull
        private static <T,A> Flux<T> begin(@NonNull Flux<Tuple2<Long,T>> source,
                                           @NonNull Supplier<A> initial,
                                           @NonNull BiFunction<A, ? super Tuple2<Instant,T>, ?  extends A> accumulator,
                                           @NonNull Function<A, Instant> extractor) {
            return onAssembly(new FluxBeginVirtualTimeOperator<>(source, initial, accumulator, extractor));
        }

        @NonNull
        private static <T> Flux<T> end(@NonNull Flux<T> source, @NonNull Supplier<Instant> startTime) {
            return onAssembly(new FluxEndVirtualTimeOperator<>(source, startTime));
        }
    }

    private abstract static class MonoAssembly<T> extends Mono<Tuple2<Long,T>> {

        /**
         * This is a utility class and cannot be instantiated.s
         */
        private MonoAssembly() {
            throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
        }

        /**
         * Mono-equivalent version of {@link BarrierAssembly#fluxBegin(Flux, Supplier, BiFunction, Function)}.
         *
         * @param source the {@link Mono} whose values will be entered into virtual time.
         * @param initial {@link Supplier} returning the initial value of some arbitrary state-tracker. This value is
         *                resolved when wrapper's subscribe is called.
         * @param accumulator {@link BiFunction} which, given the state-tracker's current value and the virtual section's
         *                    next value (including timestamp), returns an updated the state tracker. Each new
         *                    value is resolved whenever onNext is called.
         * @param extractor {@link Function} which returns the virtual-section's final STOP time as an {@link Instant} based
         *                                  on the state-tracker's value when onComplete is called. See
         *                                  {@link UnreachableTimeException} and {@link UnsupportedTimeException} for
         *                                  limitations on what this {@link Instant} can be.
         * @param <T> the type of value emitted by the source {@link Mono}
         * @param <A> the type of the accumulator / state-tracker managed as values are pushed into the virtual section
         * @return a {@link Mono} of the same values, without timestamps, and within an unclosed virtual section
         */
        @NonNull
        private static <T,A> Mono<T> begin(@NonNull Mono<Tuple2<Long,T>> source,
                                           @NonNull Supplier<A> initial,
                                           @NonNull BiFunction<A, ? super Tuple2<Instant,T>, ?  extends A> accumulator,
                                           @NonNull Function<A, Instant> extractor) {
            return onAssembly(new MonoBeginVirtualTimeOperator<>(source, initial, accumulator, extractor));
        }

        @NonNull
        private static <T> Mono<T> end(@NonNull Mono<T> source, @NonNull Supplier<Instant> startTime) {
            return onAssembly(new MonoEndVirtualTimeOperator<>(source, startTime));
        }
    }

    private static final class FluxBeginVirtualTimeOperator<T,A> extends FluxOperator<Tuple2<Long,T>,T> {

        private final Supplier<A> initial;
        private final BiFunction<A, ? super Tuple2<Instant,T>, ?  extends A> accumulator;
        private final Function<A, Instant> extractor;

        private FluxBeginVirtualTimeOperator(@NonNull Flux<Tuple2<Long,T>> source,
                                             @NonNull Supplier<A> initial,
                                             @NonNull BiFunction<A, ? super Tuple2<Instant,T>, ?  extends A> accumulator,
                                             @NonNull Function<A, Instant> extractor) {
            super(source);
            this.initial = initial;
            this.accumulator = accumulator;
            this.extractor = extractor;
        }

        @Override
        public void subscribe(@NonNull CoreSubscriber<? super T> actual) {
            source.subscribe(new BarrierBeginInnerOperator<>(actual, initial.get(), accumulator, extractor));
        }
    }

    private static final class FluxEndVirtualTimeOperator<T> extends FluxOperator<T,T> {

        @NonNull
        private final Supplier<Instant> startTime;

        private FluxEndVirtualTimeOperator(@NonNull Flux<? extends T> source, @NonNull Supplier<Instant> startTime) {
            super(source);
            this.startTime = startTime;
        }

        @Override
        public void subscribe(@NonNull CoreSubscriber<? super T> actual) {
            source.subscribe(new BarrierEndInnerOperator<>(actual, startTime.get()));
        }
    }

    private static final class MonoBeginVirtualTimeOperator<T, A> extends MonoOperator<Tuple2<Long,T>,T> {

        private final Supplier<A> initial;
        private final BiFunction<A, ? super Tuple2<Instant,T>, ?  extends A> accumulator;
        private final Function<A, Instant> extractor;

        private MonoBeginVirtualTimeOperator(@NonNull Mono<Tuple2<Long,T>> source,
                                             @NonNull Supplier<A> initial,
                                             @NonNull BiFunction<A, ? super Tuple2<Instant,T>, ?  extends A> accumulator,
                                             @NonNull Function<A, Instant> extractor) {
            super(source);
            this.initial = initial;
            this.accumulator = accumulator;
            this.extractor = extractor;
        }

        @Override
        public void subscribe(@NonNull CoreSubscriber<? super T> actual) {
            source.subscribe(new BarrierBeginInnerOperator<>(actual, initial.get(), accumulator, extractor));
        }
    }

    private static final class MonoEndVirtualTimeOperator<T> extends MonoOperator<T,T> {

        @NonNull
        private final Supplier<Instant> startTime;

        private MonoEndVirtualTimeOperator(@NonNull Mono<? extends T> source, @NonNull Supplier<Instant> startTime) {
            super(source);
            this.startTime = startTime;
        }

        @Override
        public void subscribe(@NonNull CoreSubscriber<? super T> actual) {
            source.subscribe(new BarrierEndInnerOperator<>(actual, startTime.get()));
        }
    }

    private static final class BarrierBeginInnerOperator<T,A> implements CoreSubscriber<Tuple2<Long,T>>, Subscription, Scannable {

        private final CoreSubscriber<? super T> actual;
        private final Context context;

        @Nullable
        private final VirtualTimeScheduler scheduler;

        // todo: reactor only makes volatile when cross-thread boundaries are of concern, but what happens if a 3rd
        //       party operator crosses thread boundary and doesn't provide the info at assembly? Do they always take
        //       pessimistic approach? Is best option to write multiple implementations based on assembly info?
        private boolean done;

        // todo: see above todo, reactor makes subscription volatile at boundaries but when are such operators used?
        private Subscription s;

        private final BiFunction<A, ? super Tuple2<Instant,T>, ?  extends A> accumulator;
        private final Function<A, Instant> extractor;

        // todo: thoroughly re-read onNext and onComplete threading rules and make 100% this can't get messed up.
        private A acc;

        private BarrierBeginInnerOperator(@NonNull CoreSubscriber<? super T> actual,
                                          @NonNull A initial,
                                          @NonNull BiFunction<A, ? super Tuple2<Instant,T>, ?  extends A> accumulator,
                                          @NonNull Function<A, Instant> extractor) {
            this.actual = actual;
            this.context = actual.currentContext();
            this.scheduler = context.getOrDefault(SCHEDULER_CONTEXT_KEY, null);
            acc = initial;
            this.accumulator = accumulator;
            this.extractor = extractor;
        }

        @Override
        public final void request(long n) {
            s.request(n);
        }

        @Override
        public final void cancel() {
            s.cancel();
        }

        @Override
        public void onSubscribe(@NonNull Subscription s) {
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
                final Tuple2<Instant,T> mapped = t.mapT1(Instant::ofEpochMilli);
                acc = accumulator.apply(acc, mapped);

                if (scheduler.advanceTimeTo(mapped.getT1())) {
                    actual.onNext(mapped.getT2());
                } else {
                    final Instant now = Instant.ofEpochMilli(scheduler.now(TimeUnit.MILLISECONDS));
                    final UnreachableTimeException cause = new UnreachableTimeException("BeginVirtualTime operator " +
                            "received timestamped element " + mapped + ", which precedes virtual clock time " + now);
                    final Throwable e = Operators.onNextError(t, cause, actual.currentContext(), s);
                    if (e != null) {
                        // ensures the virtual section can be allowed to finish in cases where the error is delayed
                        final Instant stopTime = extractor.apply(acc);
                        scheduler.advanceTimeTo(stopTime);
                        onError(e);
                    } else {
                        s.request(1);
                    }
                }
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;

                final Instant stopTime = extractor.apply(acc);

                if (!PackageUtils.validate(stopTime)) {
                    final UnsupportedTimeException cause = new UnsupportedTimeException(stopTime,
                            "when advancing scheduler clock to stopTime during BeginVirtualTime's onComplete");
                    // call actual's onError (not ours) because done has already been set to true
                    actual.onError(Operators.onOperatorError(s, cause, actual.currentContext()));
                    return;
                }

                final Instant now = Instant.ofEpochMilli(scheduler.now(TimeUnit.MILLISECONDS));

                if (stopTime.isBefore(now)) {
                    final UnreachableTimeException cause = new UnreachableTimeException("BeginVirtualTime operator " +
                            "had stop time " + stopTime + " which precedes virtual clock time " + now);
                    // call actual's onError (not ours) because done has already been set to true
                    actual.onError(Operators.onOperatorError(s, cause, actual.currentContext()));
                    return;
                }

                actual.onComplete(); // must occur before advanceTimeTo (or risk an infinite loop)
                scheduler.advanceTimeTo(stopTime);
            }
        }

        @Override
        public final void onError(Throwable t) {
            if (done) {
                Operators.onErrorDropped(t, context);
            } else {
                done = true;
                actual.onError(t);
            }
        }

        @NonNull
        @Override
        public final Context currentContext() {
            return context;
        }

        private static BarrierSubscriptionException missingSchedulerException() {
            return new BarrierSubscriptionException("Subscription-time logic of TimeBarrier's " +
                    "BEGIN_VIRTUAL_TIME(Flux) was invoked, but no VirtualTimeScheduler was found in the " +
                    "subscriberContext. Is there a (uniquely) corresponding downstream transform" +
                    "(TimeBarriers::END_VIRTUAL_TIME) added to the assembly chain?");
        }

        @Override
        public final Object scanUnsafe(@NonNull Attr key) {
            if (key == Attr.PARENT) return s;
            if (key == Attr.TERMINATED) return done;
            if (key == Attr.ACTUAL) return actual;
            if (key == Attr.RUN_ON) return scheduler;

            return null;
        }
    }

    private static final class BarrierEndInnerOperator<T> implements CoreSubscriber<T>, Subscription, Scannable {

        private final CoreSubscriber<? super T> actual;
        private final Context context;

        private final VirtualTimeScheduler scheduler;

        // todo: reactor only makes volatile when cross-thread boundaries are of concern, but what happens if a 3rd
        //       party operator crosses thread boundary and doesn't provide the info at assembly? Do they always take
        //       pessimistic approach? Todo maybe write multiple implementations based on assembly info?
        private boolean done;

        // todo: see above todo, reactor makes subscription volatile at boundaries but when are such operators used?
        private Subscription s;

        private BarrierEndInnerOperator(@NonNull CoreSubscriber<? super T> actual, @NonNull Instant startTime) {
            this.actual = actual;
            this.scheduler = VirtualTimeScheduler.create();

            if (PackageUtils.validate(startTime)) {
                // recall this method is called in outer operator's onSubscribe
                scheduler.advanceTimeTo(startTime);
                this.context = actual.currentContext().putAll(Context.of(PackageUtils.SCHEDULER_CONTEXT_KEY, scheduler));
            } else {
                // if invalid, add the offending startTime to the context so it can be used to craft an error message
                // during onSubscribe
                this.context = actual.currentContext().putAll(Context.of(PackageUtils.SCHEDULER_CONTEXT_KEY, scheduler, Instant.class, startTime));
            }
        }

        @Override
        public void request(long n) {
            s.request(n);
        }

        @Override
        public void cancel() {
            s.cancel();
        }

        @Override
        public void onSubscribe(@NonNull Subscription s) {
            if (Operators.validate(this.s, s)) {
                this.s = s;

                if (context.hasKey(Instant.class)) {
                    final Instant startTime = context.get(Instant.class);
                    final UnsupportedTimeException cause = new UnsupportedTimeException(startTime,
                            "when initializing scheduler clock to startTime during EndVirtualTime's assembly");
                    Operators.error(actual, Operators.onOperatorError(cause, actual.currentContext()));
                } else {
                    actual.onSubscribe(this);
                }
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
            }
        }

        @NonNull
        @Override
        public Context currentContext() {
            return context;
        }

        @Override
        public Object scanUnsafe(@NonNull Attr key) {
            if (key == Attr.PARENT) return s;
            if (key == Attr.TERMINATED) return done;
            if (key == Attr.ACTUAL) return actual;
            if (key == Attr.RUN_ON) return scheduler;

            return null;
        }
    }
}