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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.annotation.NonNull;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.time.Instant;

/**
 * A public API with utility methods to support use of the core {@link TimeBarriers} API.
 */
public final class TimeUtils {

    /**
     * This is a utility class and cannot be instantiated.
     */
    private TimeUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Returns a {@link Flux} of tuples which can be used emulate {@link Flux#interval(Duration)} in virtual time.
     *
     * @param period the period {@link Duration} between each increment
     * @return a cold {@link Flux} emitting increasing numbers at regular intervals
     */
    @NonNull
    public static Flux<Tuple2<Long, Long>> intervalTuples(@NonNull Duration period) {
        return intervalTuples(period, period);
    }

    /**
     * Returns a {@link Flux} of tuples which can be used emulate {@link Flux#interval(Duration, Duration)} in virtual
     * time.
     *
     * @param delay  the {@link Duration} to wait before emitting 0L
     * @param period the period {@link Duration} before each following increment
     * @return a {@link Flux} emitting increasing numbers which are timestamped at regular intervals
     */
    @NonNull
    public static Flux<Tuple2<Long, Long>> intervalTuples(@NonNull Duration delay, @NonNull Duration period) {
        final long delayMS = delay.toMillis();
        final long periodMS = period.toMillis();

        return Flux.generate(() -> 0L, (index, sink) -> {
            final long adjustedMS = Operators.addCap(delayMS, Operators.multiplyCap(periodMS, index));

            sink.next(attachTimestamp(adjustedMS, index));

            if (index == Long.MAX_VALUE || adjustedMS == Long.MAX_VALUE) {
                sink.complete();
            }

            return Operators.addCap(index, 1L);
        });
    }

    /**
     * Returns a {@link Mono} containing a tuple which can be used emulate {@link Mono#delay(Duration)} in virtual time.
     *
     * @param duration the duration of the delay
     * @return a {@link Mono} that emits a tuple of ("duration's timestamp", 0L)
     */
    @NonNull
    public static Mono<Tuple2<Long,Long>> delayTuple(@NonNull Duration duration) {
        final long delayMS = duration.toMillis();
        return Mono.just(attachTimestamp(delayMS, 0L));
    }

    /**
     * Convenience function to attach a timestamp to a value. This is the same as {@code Tuples.of(timestamp, value)},
     * but with the additional benefit of validating the timestamps.
     *
     * @param timestamp the timestamp's millisecond representation
     * @param value the value to be associated with a timestamp
     * @param <T> the type of the input value
     * @return a {@link Tuple2} containing ({@link Long} timestamp, T value)
     * @throws UnsupportedTimeException if the timestamp is outside the supported range
     */
    @NonNull
    public static <T> Tuple2<Long,T> attachTimestamp(long timestamp, @NonNull T value) {
        return attachTimestamp(Instant.ofEpochMilli(timestamp), value);
    }

    /**
     * Convenience function to attach a timestamp to a value. This is the same as {@code Tuples.of(timestamp, value)},
     * but with the additional benefit of validating the timestamps.
     *
     * @param timestamp the {@link Instant} of this timestamp
     * @param value the value to be associated with a timestamp
     * @param <T> the type of the input value
     * @return a {@link Tuple2} containing ({@link Long} timestamp, T value)
     * @throws UnsupportedTimeException if the timestamp is outside the supported range
     */
    @NonNull
    public static <T> Tuple2<Long,T> attachTimestamp(@NonNull Instant timestamp, @NonNull T value) {
        PackageUtils.validate(timestamp);
        return Tuples.of(timestamp.toEpochMilli(), value);
    }

}
