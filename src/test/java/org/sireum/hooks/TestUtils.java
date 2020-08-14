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
import org.testng.annotations.BeforeTest;
import reactor.core.publisher.Hooks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifierOptions;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.util.function.*;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public class TestUtils {

    @BeforeTest
    public static void beforeAll() {
        Hooks.onOperatorDebug();
    }

    public static final long DEFAULT_TEST_TIMEOUT = 6_000L;
    public static final Duration DEFAULT_VERIFY_TIMEOUT = Duration.ofSeconds(5);

    public static final long time1 =         2000L;         //  2_000L
    public static final long time2 = time1 + 2000L;         //  4_000L
    public static final long time3 = time2 + 2000L;         //  6_000L
    public static final long time4 = time3 + 2000L;         //  8_000L
    public static final long time5 = time4 + 2000L;         // 10_000L
    public static final long time6 = time5 + 2000L;         // 12_000L

    public static final Tuple2<Long,String> a = tuple(time1, "a");
    public static final Tuple2<Long,String> b = tuple(time2, "b");
    public static final Tuple2<Long,String> c = tuple(time3, "c");
    public static final Tuple2<Long,String> d = tuple(time4, "d");
    public static final Tuple2<Long,String> e = tuple(time5, "e");
    public static final Tuple2<Long,String> f = tuple(time6, "f");

    public static void verifySchedulerInstallations() {
        // assert no virtual time scheduler is installed (due to VirtualTimeScheduler.reset())
        Assert.assertThrows(IllegalStateException.class, VirtualTimeScheduler::get);

        // assert scheduler creation throws error (due to ErrorSchedulerFactory install)
        Assert.assertThrows(ErrorSchedulerFactory.SchedulerCreationException.class, Schedulers::elastic);
        Assert.assertThrows(ErrorSchedulerFactory.SchedulerCreationException.class, Schedulers::boundedElastic);
        Assert.assertThrows(ErrorSchedulerFactory.SchedulerCreationException.class, Schedulers::parallel);
        Assert.assertThrows(ErrorSchedulerFactory.SchedulerCreationException.class, Schedulers::single);
    }

    @SafeVarargs
    public static <T> List<T> list(T... values) {
        return Arrays.asList(values);
    }

    public static <T1, T2> Tuple2<T1, T2> tuple(T1 t1, T2 t2) {
        return Tuples.of(t1, t2);
    }

    public static <T1, T2, T3> Tuple3<T1, T2, T3> tuple(T1 t1, T2 t2, T3 t3) {
        return Tuples.of(t1, t2, t3);
    }

    public static <T1, T2, T3, T4> Tuple4<T1, T2, T3, T4> tuple(T1 t1, T2 t2, T3 t3, T4 t4) {
        return Tuples.of(t1, t2, t3, t4);
    }

    public static <T1, T2, T3, T4, T5> Tuple5<T1, T2, T3, T4, T5> tuple(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5) {
        return Tuples.of(t1, t2, t3, t4, t5);
    }

    public static <T1, T2, T3, T4, T5, T6> Tuple6<T1, T2, T3, T4, T5, T6> tuple(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6) {
        return Tuples.of(t1, t2, t3, t4, t5, t6);
    }

    public static <T1, T2, T3, T4, T5, T6, T7> Tuple7<T1, T2, T3, T4, T5, T6, T7> tuple(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7) {
        return Tuples.of(t1, t2, t3, t4, t5, t6, t7);
    }

    public static <T1, T2, T3, T4, T5, T6, T7, T8> Tuple8<T1, T2, T3, T4, T5, T6, T7, T8> tuple(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8) {
        return Tuples.of(t1, t2, t3, t4, t5, t6, t7, t8);
    }

    private static void assertNonNegative(long n, String variableName) {
        if (n < 0L) {
            throw new IllegalArgumentException(variableName + " >= 0 required but it was " + n);
        }
    }

    public static StepVerifierOptions opts() {
        return StepVerifierOptions.create();
    }

}
