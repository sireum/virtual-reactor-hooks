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

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.annotation.NonNull;

import java.util.concurrent.ThreadFactory;

final class ErrorSchedulerFactory implements Schedulers.Factory {

    // use Schedulers.resetFactory() to uninstall
    public static void install() {
        Schedulers.setFactory(new ErrorSchedulerFactory());
    }

    @Override
    public final @NonNull Scheduler newElastic(int ttlSeconds, @NonNull ThreadFactory threadFactory) {
        throw new SchedulerCreationException("attempted to create new Elastic scheduler");
    }

    @Override
    public final @NonNull Scheduler newBoundedElastic(int threadCap, int queuedTaskCap, @NonNull ThreadFactory threadFactory, int ttlSeconds) {
        throw new SchedulerCreationException("attempted to create new BoundedElastic scheduler");
    }

    @Override
    public final @NonNull Scheduler newParallel(int parallelism, @NonNull ThreadFactory threadFactory) {
        throw new SchedulerCreationException("attempted to create new Parallel scheduler");
    }

    @Override
    public final @NonNull Scheduler newSingle(@NonNull ThreadFactory threadFactory) {
        throw new SchedulerCreationException("attempted to create new Single scheduler");
    }

    public static class SchedulerCreationException extends RuntimeException {
        public SchedulerCreationException(String message) {
            super(message);
        }
    }
}

