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
import org.sireum.hooks.Utils.ErasedPublisher;
import reactor.core.Scannable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.Instant;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Contains the core public API of the library, specifically methods to enter/exit virtual time and methods
 * to add a "not currently in virtual time" hint to a {@link Flux} or {@link Mono}.
 *
 * @see TimeUtils
 */
public final class TimeBarriers {

    /**
     * When in virtual time, this key will point to the {@link VirtualTimeScheduler}
     * stored in subscriberContext.
     */
    static final String SCHEDULER_CONTEXT_KEY;

    // points to DO_NOT_INSTRUMENT_HINT or null
    private static final String INSTRUMENTATION_ASSEMBLY_HINT_KEY; // goes in tags at assembly time

    // hint that instrumentation should be avoided. Boosts performance only, does not effect logic (unless incorrectly
    // used inside a virtual flux (but this is checked unless assembly-time scanning is discontinued by some operation))
    // since checks cannot be guarenteed in a valid virtual section, DO_NOT_INSTRUMENT is a HINT and avoids runtime
    // checks on a can-prove basis
    private static final String DO_NOT_INSTRUMENT_HINT;

    // pre-allocate tuples needed for scan-tag lookup
    private static final Tuple2<String, String> INSTRUMENTATION_GUARD_TAG;

    private static final Instant MIN_EPOCH = Instant.ofEpochMilli(0L);
    private static final Instant MAX_EPOCH = Instant.ofEpochMilli(Long.MAX_VALUE);

    @NotNull
    public static <T> Flux<T> NOT_VIRTUAL_HINT(@NotNull Flux<T> flux) {
        return flux.tag(INSTRUMENTATION_ASSEMBLY_HINT_KEY, DO_NOT_INSTRUMENT_HINT);
    }

    @NotNull
    public static <T> Mono<T> NOT_VIRTUAL_HINT(@NotNull Mono<T> mono) {
        return mono.tag(INSTRUMENTATION_ASSEMBLY_HINT_KEY, DO_NOT_INSTRUMENT_HINT);
    }

    @NotNull
    public static <T> Flux<T> ENTER_VIRTUAL_TIME(@NotNull Flux<Tuple2<Long,T>> flux) {
        return ENTER_VIRTUAL_TIME(flux, MAX_EPOCH);
    }

    @NotNull
    public static <T> Flux<T> ENTER_VIRTUAL_TIME(@NotNull Flux<Tuple2<Long,T>> flux, @NotNull Instant stopTime) {
        return ENTER_VIRTUAL_TIME(flux, () -> stopTime);
    }

    @NotNull
    public static <T> Flux<T> ENTER_VIRTUAL_TIME(@NotNull Flux<Tuple2<Long,T>> flux, @NotNull Supplier<Instant> stopTime) {
        return flux.subscriberContext(context -> context.delete(SCHEDULER_CONTEXT_KEY))
                .transform(TimeBarriers::REMOVE_INSTRUMENTATION_HINTS)
                .transform(source -> BarrierAssembly.fluxBegin(source, stopTime));
    }

    @NotNull
    public static <T> Mono<T> ENTER_VIRTUAL_TIME(@NotNull Mono<Tuple2<Long,T>> mono) {
        return ENTER_VIRTUAL_TIME(mono, MAX_EPOCH);
    }

    @NotNull
    public static <T> Mono<T> ENTER_VIRTUAL_TIME(@NotNull Mono<Tuple2<Long,T>> mono, @NotNull Instant stopTime) {
        return ENTER_VIRTUAL_TIME(mono, () -> stopTime);
    }

    @NotNull
    public static <T> Mono<T> ENTER_VIRTUAL_TIME(@NotNull Mono<Tuple2<Long,T>> mono, @NotNull Supplier<Instant> stopTime) {
        return mono.subscriberContext(context -> context.delete(SCHEDULER_CONTEXT_KEY))
                .transform(TimeBarriers::REMOVE_INSTRUMENTATION_HINTS)
                .transform(source -> BarrierAssembly.monoBegin(source, stopTime));
    }

    @NotNull
    public static <T> Flux<T> EXIT_VIRTUAL_TIME(@NotNull Flux<T> flux) {
        return EXIT_VIRTUAL_TIME(flux, MIN_EPOCH);
    }

    @NotNull
    public static <T> Flux<T> EXIT_VIRTUAL_TIME(@NotNull Flux<T> flux, @NotNull Instant startTime) {
        return EXIT_VIRTUAL_TIME(flux, () -> startTime);
    }

    @NotNull
    public static <T> Flux<T> EXIT_VIRTUAL_TIME(@NotNull Flux<T> flux, @NotNull Supplier<Instant> startTime) {
        final Scannable scan = Scannable.from(flux);
        if (scan.isScanAvailable() && scan.tags().anyMatch(TimeBarriers::guardTag)) {
            return Flux.error(createAssemblyError());
        } else {
            return flux.transform((Flux<T> source) -> BarrierAssembly.fluxEnd(source, startTime))
                .transform(TimeBarriers::NOT_VIRTUAL_HINT);
        }
    }

    @NotNull
    public static <T> Mono<T> EXIT_VIRTUAL_TIME(@NotNull Mono<T> mono) {
        return EXIT_VIRTUAL_TIME(mono, MIN_EPOCH);
    }

    @NotNull
    public static <T> Mono<T> EXIT_VIRTUAL_TIME(@NotNull Mono<T> mono, @NotNull Instant startTime) {
        return EXIT_VIRTUAL_TIME(mono, () -> startTime);
    }

    @NotNull
    public static <T> Mono<T> EXIT_VIRTUAL_TIME(@NotNull Mono<T> mono, @NotNull Supplier<Instant> startTime) {
        final Scannable scan = Scannable.from(mono);
        if (scan.isScanAvailable() && scan.tags().anyMatch(TimeBarriers::guardTag)) {
            return Mono.error(createAssemblyError());
        } else {
            return mono.transform((Mono<T> source) -> BarrierAssembly.monoEnd(source, startTime))
                    .transform(TimeBarriers::NOT_VIRTUAL_HINT);
        }
    }

    @NotNull
    private static InstrumentationAssemblyException createAssemblyError() {
        return new InstrumentationAssemblyException("TimeBarrier's END_VIRTUAL_TIME logic was invoked, but " +
                "the publisher was tagged with a DO_NOT_INSTRUMENT_HINT that should have been removed by an upstream " +
                "call to transform(TimeBarriers::BEGIN_VIRTUAL_TIME). Does this call occur in the assembly chain " +
                "(at the same operator depth)?");
    }

    @NotNull
    private static Tuple2<String, String>[] getOtherTags(@NotNull Scannable scan) {
        return scan.tags().filter(tag -> !guardTag(tag)).toArray((IntFunction<Tuple2<String, String>[]>) Tuple2[]::new);
    }

    @NotNull
    private static <T> Flux<T> REMOVE_INSTRUMENTATION_HINTS(@NotNull Flux<T> flux) {
        final Scannable scan = Scannable.from(flux);

        if (scan.tags().noneMatch(TimeBarriers::guardTag)) {
            return flux;
        }

        Flux<T> erased = Flux.from(new ErasedPublisher<>(flux));
        for (Tuple2<String, String> tag : getOtherTags(scan)) {
            erased = erased.tag(tag.getT1(), tag.getT2());
        }

        return erased;
    }

    @NotNull
    private static <T> Mono<T> REMOVE_INSTRUMENTATION_HINTS(@NotNull Mono<T> mono) {
        final Scannable scan = Scannable.from(mono);

        if (scan.tags().noneMatch(TimeBarriers::guardTag)) {
            return mono;
        }

        Mono<T> erased = Mono.from(new ErasedPublisher<>(mono));
        for (Tuple2<String, String> tag : getOtherTags(scan)) {
            erased = erased.tag(tag.getT1(), tag.getT2());
        }

        return erased;
    }

    // only works after first END_VIRTUAL_TIME usage (otherwise no way to know if inner flux is virtual)
    // (unless a hint is provided by the user)
    static boolean noInstrumentation(ProceedingJoinPoint joinPoint) {
        final Scannable scan = Scannable.from(joinPoint.getTarget());
        return scan.isScanAvailable() && scan.tags().anyMatch(TimeBarriers::guardTag);
    }

    private static boolean guardTag(Tuple2<String, String> tag) {
        return tag.equals(INSTRUMENTATION_GUARD_TAG);
    }

    static {
        SCHEDULER_CONTEXT_KEY = "inspector.instrument.scheduler";
        INSTRUMENTATION_ASSEMBLY_HINT_KEY = "inspector.instrument.assembly";
        DO_NOT_INSTRUMENT_HINT = "inspector.instrument.guard";
        INSTRUMENTATION_GUARD_TAG = Tuples.of(INSTRUMENTATION_ASSEMBLY_HINT_KEY, DO_NOT_INSTRUMENT_HINT);
    }

}