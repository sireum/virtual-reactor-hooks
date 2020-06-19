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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.Duration;

public final class TimeUtils {

    @NotNull
    public static Flux<Tuple2<Long, Long>> intervalTuples(@NotNull Duration period) {
        return intervalTuples(period, period);
    }

    @NotNull
    public static Flux<Tuple2<Long, Long>> intervalTuples(@NotNull Duration delay, @NotNull Duration period) {
        final long delayMS = delay.toMillis();
        final long periodMS = period.toMillis();

        assertNonNegative(delayMS, "delayMS");
        assertNonNegative(periodMS, "periodMS");

        return Flux.generate(() -> 0L, (index, sink) -> {
            final long adjustedMS = Operators.addCap(delayMS, Operators.multiplyCap(periodMS, index));

            sink.next(attachTimestamp(adjustedMS, index));

            if (index == Long.MAX_VALUE || adjustedMS == Long.MAX_VALUE) {
                sink.complete();
            }

            return Operators.addCap(index, 1L);
        });
    }

    @NotNull
    public static Mono<Tuple2<Long,Long>> delayTuple(@NotNull Duration duration) {
        final long delayMS = duration.toMillis();
        assertNonNegative(delayMS, "delayMS");
        return Mono.just(attachTimestamp(delayMS, 0L));
    }

    @NotNull
    public static <T> Tuple2<Long,T> attachTimestamp(long timestamp, @NotNull T value) {
        return Tuples.of(timestamp, value);
    }

    private static void assertNonNegative(long n, @NotNull String variableName) {
        if (n < 0L) {
            throw new IllegalArgumentException(variableName + " >= 0 required but it was " + n);
        }
    }

    private TimeUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
