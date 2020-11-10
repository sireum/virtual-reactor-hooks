/* Modifications copyright (c) 2020 Matthew Weis, Kansas State University
 *
 * A modified version of reactor-core's VirtualTimeScheduler.
 *
 * The following modifications have been made:
 *   (1) Removed all support for a "global VirtualTimeScheduler" installed by Scheduler factory hooks
 *      - includes public methods like getOrSet() and reset()
 *      - includes relevant logic in non-removed methods, such as calling Schedulers.resetFactory() in dispose()
 *      - includes local variables, such as the AtomicReference to the current VirtualTimeScheduler
 * 	 (2) Removed "defer" field and constructor/factory parameters because defer is always false in this library
 * 		- Removed the if (!defer || !queue.isEmpty()) { ... } check because !defer was always true
 * 	 (3) Reduced class visibility from public to package-private
 *      - Changed package to match this library (and not conflict with original VirtualTimeScheduler on classpath)
 * 		- Also adjusted constructors/factory methods to match
 *   (4) Changed "advanceTimeTo(Instant)" method to only advance if currentTime <= newTime
 *      - return signature changed from void to boolean (true if time was advanced)
 *   (5) Deleted "advanceTimeBy(Duration)" method
 *   (6) Deleted "advanceTime()" method
 *   (7) Automatic import cleanup was run after making these changes
 *
 */
/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sireum.hooks;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.Exceptions;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Scheduler;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;

import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Modified version of Reactor's VirtualTimeScheduler for internal library use.
 * See the modifications copyright at the top of this source file for a list of changes.
 */
class VirtualTimeScheduler implements Scheduler {

	static VirtualTimeScheduler create() {
		return new VirtualTimeScheduler();
	}

	final Queue<TimedRunnable> queue =
			new PriorityBlockingQueue<>(Queues.XS_BUFFER_SIZE);

	@SuppressWarnings("unused")
	volatile long counter;

	volatile long nanoTime;

	volatile long deferredNanoTime;
	static final AtomicLongFieldUpdater<VirtualTimeScheduler> DEFERRED_NANO_TIME = AtomicLongFieldUpdater.newUpdater(VirtualTimeScheduler.class, "deferredNanoTime");

	volatile int advanceTimeWip;
	static final AtomicIntegerFieldUpdater<VirtualTimeScheduler> ADVANCE_TIME_WIP =
			AtomicIntegerFieldUpdater.newUpdater(VirtualTimeScheduler.class, "advanceTimeWip");

	volatile boolean shutdown;

	final VirtualTimeWorker directWorker;

	VirtualTimeScheduler() {
		directWorker = createWorker();
	}

	/**
	 * Moves the {@link VirtualTimeScheduler}'s clock to a particular moment in time.
	 *
	 * @param instant the point in time to move the {@link VirtualTimeScheduler}'s
	 * clock to
	 */
	public boolean advanceTimeTo(Instant instant) {
		final long targetTime = TimeUnit.NANOSECONDS.convert(instant.toEpochMilli(), TimeUnit.MILLISECONDS);
        final long timeShiftInNanoseconds = targetTime - nanoTime;

        if (timeShiftInNanoseconds < 0L) {
        	return false;
		}

		advanceTime(timeShiftInNanoseconds);
		return true;
	}

	/**
	 * Get the number of scheduled tasks.
	 * <p>
	 * This count includes tasks that have already performed as well as ones scheduled in future.
	 * For periodical task, initial task is first scheduled and counted as one. Whenever
	 * subsequent repeat happens this count gets incremented for the one that is scheduled
	 * for the next run.
	 *
	 * @return number of tasks that have scheduled on this scheduler.
	 */
	public long getScheduledTaskCount() {
		return this.counter;
	}

	@Override
	public VirtualTimeWorker createWorker() {
		if (shutdown) {
			throw new IllegalStateException("VirtualTimeScheduler is shutdown");
		}
		return new VirtualTimeWorker();
	}

	@Override
	public long now(TimeUnit unit) {
		return unit.convert(nanoTime + deferredNanoTime, TimeUnit.NANOSECONDS);
	}

	@Override
	public Disposable schedule(Runnable task) {
		if (shutdown) {
			throw Exceptions.failWithRejected();
		}
		return directWorker.schedule(task);
	}

	@Override
	public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
		if (shutdown) {
			throw Exceptions.failWithRejected();
		}
		return directWorker.schedule(task, delay, unit);
	}

	@Override
	public boolean isDisposed() {
		return shutdown;
	}

	@Override
	public void dispose() {
		if (shutdown) {
			return;
		}
		queue.clear();
		shutdown = true;
		directWorker.dispose();
	}

	@Override
	public Disposable schedulePeriodically(Runnable task,
			long initialDelay,
			long period, TimeUnit unit) {
		if (shutdown) {
			throw Exceptions.failWithRejected();
		}

		PeriodicDirectTask periodicTask = new PeriodicDirectTask(task);

		directWorker.schedulePeriodically(periodicTask, initialDelay, period, unit);

		return periodicTask;
	}

	final void advanceTime(long timeShiftInNanoseconds) {
		Operators.addCap(DEFERRED_NANO_TIME, this, timeShiftInNanoseconds);
		drain();
	}

	final void drain() {
		int remainingWork = ADVANCE_TIME_WIP.incrementAndGet(this);
		if (remainingWork != 1) {
			return;
		}
		for(;;) {
			//resetting for the first time a delayed schedule is called after a deferredNanoTime is set
			long targetNanoTime = nanoTime + DEFERRED_NANO_TIME.getAndSet(this, 0);

			while (!queue.isEmpty()) {
				TimedRunnable current = queue.peek();
				if (current == null || current.time > targetNanoTime) {
					break;
				}
				//for the benefit of tasks that call `now()`
				// if scheduled time is 0 (immediate) use current virtual time
				nanoTime = current.time == 0 ? nanoTime : current.time;
				queue.remove();

				// Only execute if not unsubscribed
				if (!current.scheduler.shutdown) {
					current.run.run();
				}
			}
			nanoTime = targetNanoTime;

			remainingWork = ADVANCE_TIME_WIP.addAndGet(this, -remainingWork);
			if (remainingWork == 0) {
				break;
			}
		}
	}

	static final class TimedRunnable implements Comparable<TimedRunnable> {

		final long              time;
		final Runnable          run;
		final VirtualTimeWorker scheduler;
		final long              count; // for differentiating tasks at same time

		TimedRunnable(VirtualTimeWorker scheduler, long time, Runnable run, long count) {
			this.time = time;
			this.run = run;
			this.scheduler = scheduler;
			this.count = count;
		}

		@Override
		public int compareTo(TimedRunnable o) {
			if (time == o.time) {
				return compare(count, o.count);
			}
			return compare(time, o.time);
		}

		static int compare(long a, long b){
			return a < b ? -1 : (a > b ? 1 : 0);
		}
	}

	final class VirtualTimeWorker implements Worker {

		volatile boolean shutdown;

		VirtualTimeWorker() { }

		@Override
		public Disposable schedule(Runnable run) {
			if (shutdown) {
				throw Exceptions.failWithRejected();
			}
			final TimedRunnable timedTask = new TimedRunnable(this,
					0,
					run,
					COUNTER.getAndIncrement(VirtualTimeScheduler.this));
			queue.add(timedTask);
			drain();
			return () -> {
				queue.remove(timedTask);
				drain();
			};
		}

		@Override
		public Disposable schedule(Runnable run, long delayTime, TimeUnit unit) {
			if (shutdown) {
				throw Exceptions.failWithRejected();
			}
			final TimedRunnable timedTask = new TimedRunnable(this,
					nanoTime + unit.toNanos(delayTime),
					run,
					COUNTER.getAndIncrement(VirtualTimeScheduler.this));
			queue.add(timedTask);
			drain();
			return () -> {
				queue.remove(timedTask);
				drain();
			};
		}

		@Override
		public Disposable schedulePeriodically(Runnable task,
				long initialDelay,
				long period,
				TimeUnit unit) {
			final long periodInNanoseconds = unit.toNanos(period);
			final long firstNowNanoseconds = nanoTime;
			final long firstStartInNanoseconds = firstNowNanoseconds + unit.toNanos(initialDelay);

			PeriodicTask periodicTask = new PeriodicTask(firstStartInNanoseconds, task,
					firstNowNanoseconds,
					periodInNanoseconds);

			replace(periodicTask, schedule(periodicTask, initialDelay, unit));
			return periodicTask;
		}

		@Override
		public void dispose() {
			shutdown = true;
		}

		@Override
		public boolean isDisposed() {
			return shutdown;
		}

		final class PeriodicTask extends AtomicReference<Disposable> implements Runnable,
		                                                                        Disposable {

			final Runnable decoratedRun;
			final long     periodInNanoseconds;
			long count;
			long lastNowNanoseconds;
			long startInNanoseconds;

			PeriodicTask(long firstStartInNanoseconds,
					Runnable decoratedRun,
					long firstNowNanoseconds,
					long periodInNanoseconds) {
				this.decoratedRun = decoratedRun;
				this.periodInNanoseconds = periodInNanoseconds;
				lastNowNanoseconds = firstNowNanoseconds;
				startInNanoseconds = firstStartInNanoseconds;
				lazySet(EMPTY);
			}

			@Override
			public void run() {
				decoratedRun.run();

				if (get() != CANCELLED && !shutdown) {

					long nextTick;

					long nowNanoseconds = nanoTime;
					// If the clock moved in a direction quite a bit, rebase the repetition period
					if (nowNanoseconds + CLOCK_DRIFT_TOLERANCE_NANOSECONDS < lastNowNanoseconds || nowNanoseconds >= lastNowNanoseconds + periodInNanoseconds + CLOCK_DRIFT_TOLERANCE_NANOSECONDS) {
						nextTick = nowNanoseconds + periodInNanoseconds;
		                /*
                         * Shift the start point back by the drift as if the whole thing
                         * started count periods ago.
                         */
						startInNanoseconds = nextTick - (periodInNanoseconds * (++count));
					}
					else {
						nextTick = startInNanoseconds + (++count * periodInNanoseconds);
					}
					lastNowNanoseconds = nowNanoseconds;

					long delay = nextTick - nowNanoseconds;
					replace(this, schedule(this, delay, TimeUnit.NANOSECONDS));
				}
			}

			@Override
			public void dispose() {
				getAndSet(CANCELLED).dispose();
			}
		}
	}

	static final Disposable CANCELLED = Disposables.disposed();
	static final Disposable EMPTY = Disposables.never();

	static boolean replace(AtomicReference<Disposable> ref, @Nullable Disposable c) {
		for (; ; ) {
			Disposable current = ref.get();
			if (current == CANCELLED) {
				if (c != null) {
					c.dispose();
				}
				return false;
			}
			if (ref.compareAndSet(current, c)) {
				return true;
			}
		}
	}

	static class PeriodicDirectTask implements Runnable, Disposable {

		final Runnable run;

		volatile boolean disposed;

		PeriodicDirectTask(Runnable run) {
			this.run = run;
		}

		@Override
		public void run() {
			if (!disposed) {
				try {
					run.run();
				}
				catch (Throwable ex) {
					Exceptions.throwIfFatal(ex);
					throw Exceptions.propagate(ex);
				}
			}
		}

		@Override
		public void dispose() {
			disposed = true;
		}
	}

	static final AtomicLongFieldUpdater<VirtualTimeScheduler> COUNTER =
			AtomicLongFieldUpdater.newUpdater(VirtualTimeScheduler.class, "counter");
	static final long CLOCK_DRIFT_TOLERANCE_NANOSECONDS;

	static {
		CLOCK_DRIFT_TOLERANCE_NANOSECONDS = TimeUnit.MINUTES.toNanos(Long.getLong(
				"reactor.scheduler.drift-tolerance",
				15));
	}


}
