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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Scheduler;

import java.util.Optional;
import java.util.function.Function;

final class Utils {

    static class ErasedPublisher<T> implements CorePublisher<T> {

        private final Publisher<T> upstream;

        ErasedPublisher(@NotNull CorePublisher<T> source) {
            this.upstream = source;
        }

        @Override
        public void subscribe(@NotNull CoreSubscriber<? super T> delegate) {
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
            };
            upstream.subscribe(coreSubscriber);
        }

        @Override
        public void subscribe(Subscriber<? super T> s) {
            subscribe(Operators.toCoreSubscriber(s));
        }
    }

    @NotNull
    static Object proceed(ProceedingJoinPoint joinPoint) {
        try {
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            throw new InstrumentationAssemblyException(joinPoint, throwable);
        }
    }

    @NotNull
    @SuppressWarnings("unchecked")
    static <T> Mono<T> virtualMono(ProceedingJoinPoint joinPoint, Function<? super Scheduler, ? extends Mono<T>> builder) {
        return Mono.deferWithContext(context -> {
            final Optional<VirtualTimeScheduler> maybeScheduler = context.getOrEmpty(TimeBarriers.SCHEDULER_CONTEXT_KEY);
            if (maybeScheduler.isPresent()) {
                final VirtualTimeScheduler scheduler = maybeScheduler.get();
                return builder.apply(scheduler);
            } else {
                try {
                    return (Mono<T>) joinPoint.proceed();
                } catch (Throwable throwable) {
                    return Mono.error(new InstrumentationAssemblyException(joinPoint, throwable));
                }
            }
        });
    }

    @NotNull
    @SuppressWarnings("unchecked")
    static <T> Flux<T> virtualFlux(ProceedingJoinPoint joinPoint, Function<? super Scheduler, ? extends Flux<T>> builder) {
        return Flux.deferWithContext(context -> {
            final Optional<VirtualTimeScheduler> maybeScheduler = context.getOrEmpty(TimeBarriers.SCHEDULER_CONTEXT_KEY);
            if (maybeScheduler.isPresent()) {
                final VirtualTimeScheduler scheduler = maybeScheduler.get();
                return builder.apply(scheduler);
            } else {
                try {
                    return (Flux<T>) joinPoint.proceed();
                } catch (Throwable throwable) {
                    return Flux.error(new InstrumentationAssemblyException(joinPoint, throwable));
                }
            }
        });
    }

    private Utils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

}
