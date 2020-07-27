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

import org.aspectj.lang.ProceedingJoinPoint;
import org.jetbrains.annotations.NotNull;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CorePublisher;
import reactor.core.CoreSubscriber;
import reactor.core.Scannable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.Instant;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * The core public API of the library, specifically contains methods that enter/exit virtual time.
 * Also contains methods to add "not in virtual time" hint tags to existing chains for assembly-time optimization.
 *
 * @see TimeUtils
 */
public final class TimeBarriers {

    /**
     * Points to a DO_NOT_INSTRUMENT_HINT or nothing. Time-sensitive {@link Flux} and {@link Mono} operators will
     * always check for a DO_NOT_INSTRUMENT_HINT at assembly time as an attempt to optimize away from a deferred
     * "is this operator in a virtual segment" subscription-time check. The deferred check is definitive but also
     * occurs at the start of each individual subscription and prevents the operator from undergoing assembly-time
     * optimizations.
     *
     * @see TimeBarriers#ATTACH_NOT_VIRTUAL_HINT(Flux)
     * @see TimeBarriers#ATTACH_NOT_VIRTUAL_HINT(Mono)
     */
    private static final String INSTRUMENTATION_ASSEMBLY_HINT_KEY; // used in Scannable tags at assembly time

    /**
     * Hint that instrumentation should be avoided. Boosts performance only, does not effect logic (unless incorrectly
     * used inside a virtual flux (but this is checked (unless assembly-time scanning is discontinued by some operation)))
     * Since assembly checks cannot be guaranteed in a valid virtual section, DO_NOT_INSTRUMENT is a heuristic hint to
     * avoid subscribe-time checks on a can-prove basis (pessimistic approach).
     *
     * @see TimeBarriers#ATTACH_NOT_VIRTUAL_HINT(Flux)
     * @see TimeBarriers#ATTACH_NOT_VIRTUAL_HINT(Mono)
     */
    private static final String DO_NOT_INSTRUMENT_HINT;

    /**
     * Pre-allocated tuple needed for scan-tag lookup.
     *
     * @see TimeBarriers#guardTag(Tuple2)
     */
    private static final Tuple2<String, String> INSTRUMENTATION_GUARD_TAG;

    /**
     * Pre-allocated {@link Instant} supplier that actually just returns null. Useful for ENTER_VIRTUAL_TIME operators
     * whose accumulators can handle null initial values (or just avoid accumulated values all together).
     *
     * @see TimeBarriers#ENTER_VIRTUAL_TIME(Flux,Function)
     * @see TimeBarriers#ENTER_VIRTUAL_TIME(Mono,Function)
     */
    private static final Supplier<Instant> NULL_SUPPLIER = () -> null;

    /**
     * Pre-allocated {@link BiFunction} that outputs the timestamp of the second argument (and ignores the first).
     * Useful for tracking the current time without any additional modifications or metrics.
     *
     * @see TimeBarriers#ENTER_VIRTUAL_TIME(Flux,Function)
     * @see TimeBarriers#ENTER_VIRTUAL_TIME(Mono,Function)
     */
    private static final BiFunction<Instant, Tuple2<Instant,?>, Instant> LAST_TIME_ACC = (prev, ts) -> ts.getT1();

    /**
     * Pre-allocated {@link Function} of type {@link Instant} -> {@link Instant} that ignores its input and returns
     * {@link PackageUtils#MAX_EPOCH}. Used as a default stopTime function for the ENTER_VIRTUAL_TIME barrier.
     *
     * @see TimeBarriers#ENTER_VIRTUAL_TIME(Flux)
     * @see TimeBarriers#ENTER_VIRTUAL_TIME(Mono)
     */
    private static final Function<Instant, Instant> MAX_TIME_EXTRACTOR = ignored -> PackageUtils.MAX_EPOCH;

    /**
     * Pre-allocated {@link Supplier} of {@link Instant} that returns {@link PackageUtils#MIN_EPOCH}.
     * Used as a default startTime supplier for EXIT_VIRTUAL_TIME barrier.
     *
     * @see TimeBarriers#EXIT_VIRTUAL_TIME(Flux)
     * @see TimeBarriers#EXIT_VIRTUAL_TIME(Mono)
     */
    private static final Supplier<Instant> MIN_TIME_SUPPLIER = () -> PackageUtils.MIN_EPOCH;

    /**
     * Marks downstream operators as being non-virtual. Normally, operators that may potentially need a virtual
     * scheduler will undergo this determination at subscription time. If however the operator is marked with a
     * virtual-time hint, a "is-not-virtual" determination can be made at assembly time and the operator can be
     * assembled normally as if nothing ever happened.
     * <br>
     * It is NEVER ok to use this hint inside of a virtual-section.
     * <br>
     * It is perfectly OK to use this hint upstream to a ENTER_VIRTUAL_TIME call. The ENTER_VIRTUAL_TIME call will
     * automatically remove any not-virtual-time hint before beginning the virtual-section. Additionally,
     * EXIT_VIRTUAL_TIME will automatically add a not-virtual-time hint to the downstream because it's a free
     * optimization.
     * <br>
     * The not-virtual-time hint is tracked through the underlying {@link Scannable} path and will be lost if the chain
     * of {@link Scannable} operators is broken.
     *
     * @param flux the {@link Flux} whose downstream operators will be tagged with a not-virtual-time hint
     * @param <T> the type of value emitted by the source {@link Flux}
     * @return the same {@link Flux}, except now downstream operators will have a non-virtual-hint attached (except for
     *          within virtual sections)
     */
    @NotNull
    public static <T> Flux<T> ATTACH_NOT_VIRTUAL_HINT(@NotNull Flux<T> flux) {
        return flux.tag(INSTRUMENTATION_ASSEMBLY_HINT_KEY, DO_NOT_INSTRUMENT_HINT);
    }

    /**
     * Marks downstream operators as being non-virtual. Normally, operators that may potentially need a virtual
     * scheduler will undergo this determination at subscription time. If however the operator is marked with a
     * virtual-time hint, a "is-not-virtual" determination can be made at assembly time and the operator can be
     * assembled normally as if nothing ever happened.
     * <br>
     * It is NEVER ok to use this hint inside of a virtual-section.
     * <br>
     * It is perfectly OK to use this hint upstream to a ENTER_VIRTUAL_TIME call. The ENTER_VIRTUAL_TIME call will
     * automatically remove any not-virtual-time hint before beginning the virtual-section. Additionally,
     * EXIT_VIRTUAL_TIME will automatically add a not-virtual-time hint to the downstream because it's a free
     * optimization.
     * <br>
     * The not-virtual-time hint is tracked through the underlying {@link Scannable} path and will be lost if the chain
     * of {@link Scannable} operators is broken.
     *
     * @param mono the {@link Mono} whose downstream operators will be tagged with a not-virtual-time hint
     * @param <T> the type of value emitted by the source {@link Mono}
     * @return the same {@link Mono}, except now downstream operators will have a non-virtual-hint attached (except for
     *          within virtual sections)
     */
    @NotNull
    public static <T> Mono<T> ATTACH_NOT_VIRTUAL_HINT(@NotNull Mono<T> mono) {
        return mono.tag(INSTRUMENTATION_ASSEMBLY_HINT_KEY, DO_NOT_INSTRUMENT_HINT);
    }

    /**
     * Enters a {@link Flux} of timestamped values into virtual time by pushing each value into a downstream
     * virtual section with a timestamp-synchronized clock. Values are emitted without their corresponding timestamps,
     * (but these can be restored with {@link Flux#timestamp()}). A corresponding EXIT_VIRTUAL_TIME call should be
     * made downstream to this ENTER_VIRTUAL_TIME call in order to define the bounds of the virtual section.
     * <br>
     * This virtual section's STOP time will be the maximum {@link Instant} supported by the scheduler. To adjust the
     * STOP time, see {@link TimeBarriers#ENTER_VIRTUAL_TIME(Flux, Instant)},
     * {@link TimeBarriers#ENTER_VIRTUAL_TIME(Flux, Function)},
     * or {@link TimeBarriers#ENTER_VIRTUAL_TIME(Flux, Supplier, BiFunction, Function)}.
     *
     * @param flux the {@link Flux} whose values will be entered into virtual time
     * @param <T> the type of value emitted by the source {@link Flux}
     * @return a {@link Flux} of the same values, without timestamps, and within an unclosed virtual section
     */
    @NotNull
    public static <T> Flux<T> ENTER_VIRTUAL_TIME(@NotNull Flux<Tuple2<Long,T>> flux) {
        return ENTER_VIRTUAL_TIME(flux, MAX_TIME_EXTRACTOR);
    }

    /**
     * Enters a {@link Flux} of timestamped values into virtual time by pushing each value into a downstream
     * virtual section with a timestamp-synchronized clock. Values are emitted without their corresponding timestamps,
     * (but these can be restored with {@link Flux#timestamp()}). A corresponding EXIT_VIRTUAL_TIME call should be
     * made downstream to this ENTER_VIRTUAL_TIME call in order to define the bounds of the virtual section.
     * <br>
     * This virtual section's STOP time will be the maximum {@link Instant} supported by the scheduler. For more precise
     * control of the STOP time, see {@link TimeBarriers#ENTER_VIRTUAL_TIME(Flux, Function)},
     * or {@link TimeBarriers#ENTER_VIRTUAL_TIME(Flux, Supplier, BiFunction, Function)}.
     *
     * @param flux the {@link Flux} whose values will be entered into virtual time
     * @param stopTime the STOP time of the virtual section. Must be greater than or equal to the last element's timestamp
     * @param <T> the type of value emitted by the source {@link Flux}
     * @return a {@link Flux} of the same values, without timestamps, and within an unclosed virtual section
     */
    @NotNull
    public static <T> Flux<T> ENTER_VIRTUAL_TIME(@NotNull Flux<Tuple2<Long,T>> flux, @NotNull Instant stopTime) {
        return ENTER_VIRTUAL_TIME(flux, ignored -> stopTime);
    }

    /**
     * Enters a {@link Flux} of timestamped values into virtual time by pushing each value into a downstream
     * virtual section with a timestamp-synchronized clock. Values are emitted without their corresponding timestamps,
     * (but these can be restored with {@link Flux#timestamp()}). A corresponding EXIT_VIRTUAL_TIME call should be
     * made downstream to this ENTER_VIRTUAL_TIME call in order to define the bounds of the virtual section.
     * <br>
     * This virtual section's STOP time will be the maximum {@link Instant} supported by the scheduler. For more precise
     * control of the STOP time use {@link TimeBarriers#ENTER_VIRTUAL_TIME(Flux, Supplier, BiFunction, Function)}.
     *
     * @param flux the {@link Flux} whose values will be entered into virtual time
     * @param stopTime a function of the last element's timestamp to the final STOP time. The final stop time MUST be
     *                 greater than or equal to the last element's timestamp
     * @param <T> the type of value emitted by the source {@link Flux}
     * @return a {@link Flux} of the same values, without timestamps, and within an unclosed virtual section
     */
    @NotNull
    public static <T> Flux<T> ENTER_VIRTUAL_TIME(@NotNull Flux<Tuple2<Long,T>> flux, @NotNull Function<Instant, Instant> stopTime) {
        return ENTER_VIRTUAL_TIME(flux, NULL_SUPPLIER, LAST_TIME_ACC, stopTime);
    }

    /**
     * Enters a {@link Flux} of timestamped values into virtual time by pushing each value into a downstream
     * virtual section with a timestamp-synchronized clock. Values are emitted without their corresponding timestamps,
     * but these can be restored with {@link Flux#timestamp()}.
     * <br>
     * This method also accepts a strategy to resolve the final STOP time of the virtual section when the {@link Flux}
     * completes. This is tracked using a supplier to set an initial value, an accumulator to update the value each time
     * a new timestamped value is pushed into the virtual section, and an extractor to return the final STOP time from
     * the final accumulated value once the {@link Flux} has completed. See params: initial, accumulator, and extractor
     * for more information.
     * <br>
     * The returned {@link Flux} will onError a {@link BarrierSubscriptionException}
     * if the virtual section is not closed with a downstream EXIT_VIRTUAL_TIME operator before subscription.
     * <br>
     * The returned {@link Flux} may onError an {@link UnreachableTimeException} if the extractor returns an unreachable
     * time. See {@link UnreachableTimeException} for what constitutes an unreachable time.
     * <br>
     * The returned {@link Flux} may onError an {@link UnsupportedTimeException} if the extractor returns an unsupported
     * time. See {@link UnsupportedTimeException} for what constitutes a supported or unsupported time.
     *
     * @param flux the {@link Flux} whose values will be entered into virtual time
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
    @NotNull
    public static <T,A> Flux<T> ENTER_VIRTUAL_TIME(@NotNull Flux<Tuple2<Long,T>> flux,
                                                   @NotNull Supplier<A> initial,
                                                   @NotNull BiFunction<A, ? super Tuple2<Instant,T>, ? extends A> accumulator,
                                                   @NotNull Function<A, Instant> extractor) {
        return flux
                .subscriberContext(context -> context.delete(PackageUtils.SCHEDULER_CONTEXT_KEY))
                .transform(TimeBarriers::REMOVE_INSTRUMENTATION_HINTS)
                .transform(source -> BarrierAssembly.fluxBegin(source, initial, accumulator, extractor));
    }

    /**
     * Enters a {@link Mono} of timestamped values into virtual time by pushing each value into a downstream
     * virtual section with a timestamp-synchronized clock. Values are emitted without their corresponding timestamps,
     * (but these can be restored with {@link Mono#timestamp()}). A corresponding EXIT_VIRTUAL_TIME call should be
     * made downstream to this ENTER_VIRTUAL_TIME call in order to define the bounds of the virtual section.
     * <br>
     * This virtual section's STOP time will be the maximum {@link Instant} supported by the scheduler. To adjust the
     * STOP time, see {@link TimeBarriers#ENTER_VIRTUAL_TIME(Mono, Instant)},
     * {@link TimeBarriers#ENTER_VIRTUAL_TIME(Mono, Function)},
     * or {@link TimeBarriers#ENTER_VIRTUAL_TIME(Mono, Supplier, BiFunction, Function)}.
     *
     * @param mono the {@link Mono} whose values will be entered into virtual time
     * @param <T> the type of value emitted by the source {@link Mono}
     * @return a {@link Mono} of the same values, without timestamps, and within an unclosed virtual section
     */
    @NotNull
    public static <T> Mono<T> ENTER_VIRTUAL_TIME(@NotNull Mono<Tuple2<Long,T>> mono) {
        return ENTER_VIRTUAL_TIME(mono, MAX_TIME_EXTRACTOR);
    }

    /**
     * Enters a {@link Mono} of timestamped values into virtual time by pushing each value into a downstream
     * virtual section with a timestamp-synchronized clock. Values are emitted without their corresponding timestamps,
     * (but these can be restored with {@link Mono#timestamp()}). A corresponding EXIT_VIRTUAL_TIME call should be
     * made downstream to this ENTER_VIRTUAL_TIME call in order to define the bounds of the virtual section.
     * <br>
     * This virtual section's STOP time will be the maximum {@link Instant} supported by the scheduler. For more precise
     * control of the STOP time, see {@link TimeBarriers#ENTER_VIRTUAL_TIME(Mono, Function)},
     * or {@link TimeBarriers#ENTER_VIRTUAL_TIME(Mono, Supplier, BiFunction, Function)}.
     *
     * @param mono the {@link Mono} whose values will be entered into virtual time
     * @param stopTime the STOP time of the virtual section. Must be greater than or equal to the last element's timestamp
     * @param <T> the type of value emitted by the source {@link Mono}
     * @return a {@link Mono} of the same values, without timestamps, and within an unclosed virtual section
     */
    @NotNull
    public static <T> Mono<T> ENTER_VIRTUAL_TIME(@NotNull Mono<Tuple2<Long,T>> mono, @NotNull Instant stopTime) {
        return ENTER_VIRTUAL_TIME(mono, ignored -> stopTime);
    }

    /**
     * Enters a {@link Mono} of timestamped values into virtual time by pushing each value into a downstream
     * virtual section with a timestamp-synchronized clock. Values are emitted without their corresponding timestamps,
     * (but these can be restored with {@link Mono#timestamp()}). A corresponding EXIT_VIRTUAL_TIME call should be
     * made downstream to this ENTER_VIRTUAL_TIME call in order to define the bounds of the virtual section.
     * <br>
     * This virtual section's STOP time will be the maximum {@link Instant} supported by the scheduler. For more precise
     * control of the STOP time use {@link TimeBarriers#ENTER_VIRTUAL_TIME(Mono, Supplier, BiFunction, Function)}.
     *
     * @param mono the {@link Mono} whose values will be entered into virtual time
     * @param stopTime a function of the last element's timestamp to the final STOP time. The final stop time MUST be
     *                 greater than or equal to the last element's timestamp
     * @param <T> the type of value emitted by the source {@link Mono}
     * @return a {@link Mono} of the same values, without timestamps, and within an unclosed virtual section
     */
    @NotNull
    public static <T> Mono<T> ENTER_VIRTUAL_TIME(@NotNull Mono<Tuple2<Long,T>> mono, @NotNull Function<Instant, Instant> stopTime) {
        return ENTER_VIRTUAL_TIME(mono, NULL_SUPPLIER, LAST_TIME_ACC, stopTime);
    }

    /**
     * Enters a {@link Mono} of timestamped values into virtual time by pushing each value into a downstream
     * virtual section with a timestamp-synchronized clock. Values are emitted without their corresponding timestamps,
     * but these can be restored with {@link Mono#timestamp()}.
     * <br>
     * This method also accepts a strategy to resolve the final STOP time of the virtual section when the {@link Mono}
     * completes. This is tracked using a supplier to set an initial value, an accumulator to update the value each time
     * a new timestamped value is pushed into the virtual section, and an extractor to return the final STOP time from
     * the final accumulated value once the {@link Mono} has completed. See params: initial, accumulator, and extractor
     * for more information.
     * <br>
     * The returned {@link Mono} will onError a {@link BarrierSubscriptionException}
     * if the virtual section is not closed with a downstream EXIT_VIRTUAL_TIME operator before subscription.
     * <br>
     * The returned {@link Mono} may onError an {@link UnreachableTimeException} if the extractor returns an unreachable
     * time. See {@link UnreachableTimeException} for what constitutes an unreachable time.
     * <br>
     * The returned {@link Mono} may onError an {@link UnsupportedTimeException} if the extractor returns an unsupported
     * time. See {@link UnsupportedTimeException} for what constitutes a supported or unsupported time.
     *
     * @param mono the {@link Mono} whose values will be entered into virtual time
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
     * @param <T> the type of value emitted by the source {@link Mono}
     * @param <A> the type of the accumulator / state-tracker managed as values are pushed into the virtual section
     * @return a {@link Mono} of the same values, without timestamps, and within an unclosed virtual section
     */
    @NotNull
    public static <T,A> Mono<T> ENTER_VIRTUAL_TIME(@NotNull Mono<Tuple2<Long,T>> mono,
                                                   @NotNull Supplier<A> initial,
                                                   @NotNull BiFunction<A, ? super Tuple2<Instant,T>, ? extends A> accumulator,
                                                   @NotNull Function<A, Instant> extractor) {
        return mono
                .subscriberContext(context -> context.delete(PackageUtils.SCHEDULER_CONTEXT_KEY))
                .transform(TimeBarriers::REMOVE_INSTRUMENTATION_HINTS)
                .transform(source -> BarrierAssembly.monoBegin(source, initial, accumulator, extractor));
    }

    /**
     * Given a {@link Flux} within an unclosed virtual section, returns a {@link Flux} that closes the virtual section
     * and emits those same values.
     * <br>
     * This virtual section's START time will be the maximum {@link Instant} supported by the scheduler. To adjust the
     * START time, see {@link TimeBarriers#EXIT_VIRTUAL_TIME(Flux, Instant)},
     * or {@link TimeBarriers#EXIT_VIRTUAL_TIME(Flux, Supplier)}.
     *
     * @param flux the currently-virtual {@link Flux} whose values will be exited from virtual time
     * @param <T> the type of value emitted by the source {@link Flux}
     * @return a {@link Flux} of the same values that is no longer in virtual time
     */
    @NotNull
    public static <T> Flux<T> EXIT_VIRTUAL_TIME(@NotNull Flux<T> flux) {
        return EXIT_VIRTUAL_TIME(flux, MIN_TIME_SUPPLIER);
    }

    /**
     * Given a {@link Flux} within an unclosed virtual section, returns a {@link Flux} that closes the virtual section
     * and emits those same values.
     * <br>
     * This virtual section's START time will be the maximum {@link Instant} supported by the scheduler. To adjust the
     * START time, see {@link TimeBarriers#EXIT_VIRTUAL_TIME(Flux, Supplier)}.
     *
     * @param flux the currently-virtual {@link Flux} whose values will be exited from virtual time
     * @param startTime the START time of the virtual section.
     * @param <T> the type of value emitted by the source {@link Flux}
     * @return a {@link Flux} of the same values that is no longer in virtual time
     */
    @NotNull
    public static <T> Flux<T> EXIT_VIRTUAL_TIME(@NotNull Flux<T> flux, @NotNull Instant startTime) {
        return EXIT_VIRTUAL_TIME(flux, () -> startTime);
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
     * @param flux the currently-virtual {@link Flux} whose values will be exited from virtual time
     * @param startTime a {@link Supplier} returning the time that each subscriber's virtual-section should START
     * @param <T> the type of value emitted by the source {@link Flux}
     * @return a {@link Flux} of the same values that is no longer in virtual time
     */
    @NotNull
    public static <T> Flux<T> EXIT_VIRTUAL_TIME(@NotNull Flux<T> flux, @NotNull Supplier<Instant> startTime) {
        final Scannable scan = Scannable.from(flux);
        if (scan.isScanAvailable() && scan.tags().anyMatch(TimeBarriers::guardTag)) {
            return Flux.error(createIllegalHintError());
        } else {
            return flux
                    .transform((Flux<T> source) -> BarrierAssembly.fluxEnd(source, startTime))
                    .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT);
        }
    }

    /**
     * Given a {@link Mono} within an unclosed virtual section, returns a {@link Mono} that closes the virtual section
     * and emits those same values.
     * <br>
     * This virtual section's START time will be the maximum {@link Instant} supported by the scheduler. To adjust the
     * START time, see {@link TimeBarriers#EXIT_VIRTUAL_TIME(Mono, Instant)},
     * or {@link TimeBarriers#EXIT_VIRTUAL_TIME(Mono, Supplier)}.
     *
     * @param mono the currently-virtual {@link Mono} whose values will be exited from virtual time
     * @param <T> the type of value emitted by the source {@link Mono}
     * @return a {@link Mono} of the same values that is no longer in virtual time
     */
    @NotNull
    public static <T> Mono<T> EXIT_VIRTUAL_TIME(@NotNull Mono<T> mono) {
        return EXIT_VIRTUAL_TIME(mono, MIN_TIME_SUPPLIER);
    }

    /**
     * Given a {@link Mono} within an unclosed virtual section, returns a {@link Mono} that closes the virtual section
     * and emits those same values.
     * <br>
     * This virtual section's START time will be the maximum {@link Instant} supported by the scheduler. To adjust the
     * START time, see {@link TimeBarriers#EXIT_VIRTUAL_TIME(Mono, Supplier)}.
     *
     * @param mono the currently-virtual {@link Mono} whose values will be exited from virtual time
     * @param startTime the START time of the virtual section.
     * @param <T> the type of value emitted by the source {@link Mono}
     * @return a {@link Mono} of the same values that is no longer in virtual time
     */
    @NotNull
    public static <T> Mono<T> EXIT_VIRTUAL_TIME(@NotNull Mono<T> mono, @NotNull Instant startTime) {
        return EXIT_VIRTUAL_TIME(mono, () -> startTime);
    }

    /**
     * Given a {@link Mono} within an unclosed virtual section, returns a {@link Mono} that closes the virtual section
     * and emits those same values.
     * <br>
     * This method also accepts a {@link Supplier} that returns the START time of this virtual section. This
     * {@link Supplier} is evaluated in the subscribe wrapper that creates the inner operator (after assembly).
     * <br>
     * The returned {@link Mono} will onError with an {@link AssemblyInstrumentationException}
     * if the virtual section is not closed with a downstream EXIT_VIRTUAL_TIME operator before subscription.
     * <br>
     * The returned {@link Mono} *may* onError an {@link UnsupportedTimeException} if startTime returns an unsupported
     * time. See {@link UnsupportedTimeException} for what constitutes a supported or unsupported time. "*May*" is
     * asterisked because this is an assembly-time check and therefore requires an unbroken {@link Scannable} chain
     * to persist throughout the virtual segment for the error to be guaranteed.
     * <br>
     * The returned {@link Mono} will onError an {@link UnreachableTimeException} if startTime returns an unreachable
     * time. This cannot happen under normal circumstances since the scheduler starts and the lowest supported time.
     *
     * @param mono the currently-virtual {@link Flux} whose values will be exited from virtual time
     * @param startTime a {@link Supplier} returning the time that each subscriber's virtual-section should START
     * @param <T> the type of value emitted by the source {@link Flux}
     * @return a {@link Flux} of the same values that is no longer in virtual time
     */
    @NotNull
    public static <T> Mono<T> EXIT_VIRTUAL_TIME(@NotNull Mono<T> mono, @NotNull Supplier<Instant> startTime) {
        final Scannable scan = Scannable.from(mono);
        if (scan.isScanAvailable() && scan.tags().anyMatch(TimeBarriers::guardTag)) {
            return Mono.error(createIllegalHintError());
        } else {
            return mono
                    .transform((Mono<T> source) -> BarrierAssembly.monoEnd(source, startTime))
                    .transform(TimeBarriers::ATTACH_NOT_VIRTUAL_HINT);
        }
    }

    /**
     * Returns a new error for when an END_VIRTUAL_TIME operator detects a DO_NOT_INSTRUMENT_HINT tag on the assembly
     * chain. This should not be possible because the upstream ENTER_VIRTUAL_TIME operator should have removed this
     * tag. Therefore, if the tag does exist, it must have been created within the virtual section, and may have lead
     * to invalid optimizations.
     *
     * @return A new {@link BarrierAssemblyException} indicating EXIT_VIRTUAL_TIME discovered an erroneous
     *         DO_NOT_INSTRUMENT_HINT within its upstream virtual-time section.
     */
    @NotNull
    private static BarrierAssemblyException createIllegalHintError() {
        return new BarrierAssemblyException("TimeBarrier's EXIT_VIRTUAL_TIME logic was invoked, but " +
                "the publisher was tagged with a DO_NOT_INSTRUMENT_HINT that should have been removed by an upstream " +
                "call to transform(TimeBarriers::ENTER_VIRTUAL_TIME). Does this call occur in the assembly chain?");
    }

    /**
     * Returns an array containing all of the scan's tags except for INSTRUMENTATION_GUARD_TAG.
     *
     * @param scan the {@link Scannable} whose tags should be copied with the exception of INSTRUMENTATION_GUARD_TAG
     * @return an array of scan's tags, excluding INSTRUMENTATION_GUARD_TAG
     */
    @NotNull
    private static Tuple2<String, String>[] getOtherTags(@NotNull Scannable scan) {
        return scan.tags().filter(tag -> !guardTag(tag)).toArray((IntFunction<Tuple2<String, String>[]>) Tuple2[]::new);
    }

    /**
     * Removes all INSTRUMENTATION_GUARD_TAG tags from a {@link Flux}. If the input {@link Flux} has no such tag,
     * it is returned. Otherwise, a new {@link Flux} is created which keeps the existing context and flags, except for
     * INSTRUMENTATION_GUARD_TAG.
     *
     * @param flux the {@link Flux} that will be returned with INSTRUMENTATION_GUARD_TAG removed if present
     * @param <T> the type of value emitted by the flux
     * @return if no INSTRUMENTATION_GUARD_TAG is found, flux is returned. Otherwise, a copy is returned minus said tag
     */
    @NotNull
    private static <T> Flux<T> REMOVE_INSTRUMENTATION_HINTS(@NotNull Flux<T> flux) {
        final Scannable scan = Scannable.from(flux);

        if (scan.tags().noneMatch(TimeBarriers::guardTag)) {
            return flux;
        }

        Flux<T> erased = Flux.from(new TagErasedPublisher<>(flux));
        for (Tuple2<String, String> tag : getOtherTags(scan)) {
            erased = erased.tag(tag.getT1(), tag.getT2());
        }

        return erased;
    }

    /**
     * Removes all INSTRUMENTATION_GUARD_TAG tags from a {@link Mono}. If the input {@link Mono} has no such tag,
     * it is returned. Otherwise, a new {@link Mono} is created which keeps the existing context and flags, except for
     * INSTRUMENTATION_GUARD_TAG.
     *
     * @param mono the {@link Mono} that will be returned with INSTRUMENTATION_GUARD_TAG removed if present
     * @param <T> the type of value emitted by the mono
     * @return if no INSTRUMENTATION_GUARD_TAG is found, mono is returned. Otherwise, a copy is returned minus said tag
     */
    @NotNull
    private static <T> Mono<T> REMOVE_INSTRUMENTATION_HINTS(@NotNull Mono<T> mono) {
        final Scannable scan = Scannable.from(mono);

        if (scan.tags().noneMatch(TimeBarriers::guardTag)) {
            return mono;
        }

        Mono<T> erased = Mono.from(new TagErasedPublisher<>(mono));
        for (Tuple2<String, String> tag : getOtherTags(scan)) {
            erased = erased.tag(tag.getT1(), tag.getT2());
        }

        return erased;
    }

    // only works after first EXIT_VIRTUAL_TIME usage (otherwise no way to know if inner flux is virtual)
    // (unless a hint is provided by the user)

    /**
     * Returns true if the given {@link ProceedingJoinPoint} does NOT point to a target tagged with
     * INSTRUMENTATION_GUARD_TAG.
     *
     * @param joinPoint the {@link ProceedingJoinPoint} whose target's tags should be checked for INSTRUMENTATION_GUARD_TAG
     * @return true iff joinPoint contained the tag INSTRUMENTATION_GUARD_TAG
     */
    static boolean noInstrumentation(ProceedingJoinPoint joinPoint) {
        final Scannable scan = Scannable.from(joinPoint.getTarget());
        return scan.isScanAvailable() && scan.tags().anyMatch(TimeBarriers::guardTag);
    }

    /**
     * Returns true if the given tag equals INSTRUMENTATION_GUARD_TAG.
     *
     * @param tag the tag to compare to INSTRUMENTATION_GUARD_TAG
     * @return whether or not the given tag equals INSTRUMENTATION_GUARD_TAG
     */
    private static boolean guardTag(Tuple2<String, String> tag) {
        return tag.equals(INSTRUMENTATION_GUARD_TAG);
    }

    /**
     * Wraps a target {@link CorePublisher} with another {@link CorePublisher} that maintains the source's
     * {@link Context} but erases all {@link Scannable} tags. This is useful for removing instrumentation hints when
     * they become invalid (by entering a virtual section for example).
     *
     * @param <T> the type of value emitted by the {@link CorePublisher}
     */
    private static final class TagErasedPublisher<T> implements CorePublisher<T> {

        /**
         * The source whose behavior is copied but {@link Scannable} tags are erased.
         */
        private final Publisher<T> upstream;

        /**
         * Create a copy of the source, but without any assembly time {@link Scannable} tags.
         *
         * @param source the template for this assembly-tag erased {@link CorePublisher} clone.
         */
        private TagErasedPublisher(@NotNull CorePublisher<T> source) {
            this.upstream = source;
        }

        /**
         * Creates {@link CoreSubscriber} from which all behavior is deferred, and uses it to subscribe to the source.
         *
         * @param delegate the source which all actions are delegated to
         */
        @Override
        public final void subscribe(@NotNull CoreSubscriber<? super T> delegate) {
            final CoreSubscriber<T> coreSubscriber = new CoreSubscriber<T>() {
                @Override
                public void onSubscribe(@NotNull Subscription subscription) {
                    delegate.onSubscribe(subscription);
                }
                @Override
                public void onNext(T t) {
                    delegate.onNext(t);
                }
                @Override
                public void onError(Throwable t) {
                    delegate.onError(t);
                }
                @Override
                public void onComplete() {
                    delegate.onComplete();
                }
                @Override
                public Context currentContext() {
                    return delegate.currentContext();
                }
            };
            upstream.subscribe(coreSubscriber);
        }

        /**
         * Overrides the default reactive-streams {@link Publisher#subscribe(Subscriber)} method and wraps the input
         * with {@link Operators#toCoreSubscriber(Subscriber)}.
         *
         * @param s the non-core subscriber to wrap in a core-subscriber before delegating {@link Publisher#subscribe(Subscriber)}
         */
        @Override
        public final void subscribe(Subscriber<? super T> s) {
            subscribe(Operators.toCoreSubscriber(s));
        }

    }

    static {
        INSTRUMENTATION_ASSEMBLY_HINT_KEY = "org.sireum.hooks.assembly";
        DO_NOT_INSTRUMENT_HINT = "org.sireum.hooks.do-not-instrument";
        INSTRUMENTATION_GUARD_TAG = Tuples.of(INSTRUMENTATION_ASSEMBLY_HINT_KEY, DO_NOT_INSTRUMENT_HINT);
    }

}