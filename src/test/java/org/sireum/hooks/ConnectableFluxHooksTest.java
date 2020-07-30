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

import org.sireum.hooks.ErrorSchedulerFactory.SchedulerCreationException;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.util.function.Tuple2;

import java.time.Duration;

import static org.sireum.hooks.TestConstants.tuple;

public class ConnectableFluxHooksTest {

    final long time1 = 2000L;

    final Tuple2<Long,String> a = tuple(time1, "a");

    @BeforeMethod
    public void beforeEach() {
        StepVerifier.setDefaultTimeout(Duration.ofSeconds(5));
        ErrorSchedulerFactory.install();
        verifySchedulerInstallations();
    }

    @AfterMethod
    public static void afterEach() {
        VirtualTimeScheduler.reset();
        Schedulers.resetFactory();
    }

    @Test
    void refCountTest() {
        final Flux<String> matchedToInner = Flux.just(a)
                .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
                .replay()
                .refCount(2, Duration.ofSeconds(3))
                .transform(TimeBarriers::EXIT_VIRTUAL_TIME);

        StepVerifier.create(matchedToInner)
                .expectSubscription()
                .expectError(AssemblyInstrumentationException.class)
                .verify();
    }

    @Test(invocationCount = 2)
    void verifySchedulerErrorFactoryInstallation() {
        // verifySchedulerInstallations();

        // install a VirtualTimeScheduler
        // since this is a repeated test, this will cause an exception if the @BeforeEach is not resetting properly
        VirtualTimeScheduler.getOrSet();
    }

    static void verifySchedulerInstallations() {
        // assert no virtual time scheduler is installed (due to VirtualTimeScheduler.reset())
        Assert.assertThrows(IllegalStateException.class, VirtualTimeScheduler::get);

        // assert scheduler creation throws error (due to ErrorSchedulerFactory install)
        Assert.assertThrows(SchedulerCreationException.class, Schedulers::elastic);
        Assert.assertThrows(SchedulerCreationException.class, Schedulers::boundedElastic);
        Assert.assertThrows(SchedulerCreationException.class, Schedulers::parallel);
        Assert.assertThrows(SchedulerCreationException.class, Schedulers::single);
    }

}