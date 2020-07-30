<!-- ABOUT THE PROJECT -->
## About The Project

Virtual-reactor-hooks is an unofficial third-party distribution of [Reactor](https://projectreactor.io/) that adds 
the ability to create sections of virtual time.

<!-- GETTING STARTED -->
## Getting Started

**Important:** When using this library, the original reactor-core should not be included as a dependency because 
this library will provide its (modified) sources instead. Any dependencies that themselves rely on reactor-core should 
exclude it as a transitive dependency because virtual-reactor-hooks will already have this covered. If in doubt,
check the top of your project's reactor-core sources for a modification notice.

#### Maven
```maven
<dependency>
    <groupId>org.sireum</groupId>
    <artifactId>virtual-reactor-hooks</artifactId>
    <version>3.3.5.RELEASE-beta</version>
</dependency>
```

#### Gradle
```gradle
compile group: 'org.sireum', name: 'virtual-reactor-hooks', version: '3.3.5.RELEASE-beta'
```

#### Sbt
```sbt
libraryDependencies += "org.sireum" % "virtual-reactor-hooks" % "3.3.5.RELEASE-beta"
```

<!-- USAGE EXAMPLES -->
## Usage

When in a virtual section, any time-based operator called without a specific `Scheduler` will use a 
`VirtualTimeScheduler` behind the scenes.

There are two prerequisites needed for virtual-time scheduling:
1. Have a `Flux` (or `Mono`) of timestamped elements.
    * Each element must be paired with its millisecond timestamp. This is done by converting element `T` to tuple 
    `Tuple2<Long,T>`, where the timestamp is the tuple's first element.
    
2. Identify the boundaries of a "virtual section"
    * Use the `.transform()` operator with `TimeBarriers.ENTER_VIRTUAL_TIME` to start the virtual section and 
    `TimeBarriers.EXIT_VIRTUAL_TIME` to stop it.
        
The result will look something like this:

```java
Flux.just(Tuples.of(100L, "foo")) // timestamp, value pair
        // we are in real time
    .transform(TimeBarriers::ENTER_VIRTUAL_TIME) // consumes the timestamps
        // we are now in virtual time!
    .transform(TimeBarriers::EXIT_VIRTUAL_TIME)
        // we are back to real time
```

## Examples

Let's first define some events:
```java
Tuple2<Long,String> a = Tuples.of( 2000L, "Event A"); // "Event A" occurs at  2 sec
Tuple2<Long,String> b = Tuples.of( 4000L, "Event B"); // "Event B" occurs at  4 sec
Tuple2<Long,String> c = Tuples.of( 6000L, "Event C"); // "Event C" occurs at  6 sec
Tuple2<Long,String> d = Tuples.of( 8000L, "Event D"); // "Event D" occurs at  8 sec
Tuple2<Long,String> e = Tuples.of(10000L, "Event E"); // "Event E" occurs at 10 sec
Tuple2<Long,String> f = Tuples.of(12000L, "Event F"); // "Event F" occurs at 12 sec
```

### Example 1
```java
Flux.just(a, b, c)
    .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
    .timestamp()
    .doOnNext(System.out::println)
    .transform(TimeBarriers::EXIT_VIRTUAL_TIME);
```
Per-subscriber output:
```
[2000,a]
[4000,b]
[6000,c]
```

### Example 2
Virtual-reactor-hooks provides a `TimeUtils` class containing some useful utilities for dealing with virtual time.
In this example, `TimeUtils.attachTimestamp(Instant, <T>)` is used to create the timestamp tuples.
```java
// emits: 6, 8, 10
Flux.range(1, 10)
    .filter(n -> n % 2 == 0) // evens only
    .map(n -> TimeUtils.attachTimestamp(Instant.ofEpochSecond(n), n))
    .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
    .skip(Duration.ofSeconds(5)) 
    .transform(TimeBarriers::EXIT_VIRTUAL_TIME);
```

### Example 3
A virtual section's clock is unique to each subscriber and can run concurrently to other virtual sections without issue.
```java
final CountDownLatch latch = new CountDownLatch(4);

final Function<Flux<Tuple2<Long,String>>,Flux<Tuple2<Long,String>>> printParallelThread = flux -> flux
    .publishOn(Schedulers.parallel())
    .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
    .timestamp()
    .doOnNext(it -> System.out.println(it.getT2() + " is in virtual time on the thread " + 
        Thread.currentThread() + " at time " + it.getT1()))
    .transform(TimeBarriers::EXIT_VIRTUAL_TIME)
    .doOnComplete(latch::countDown);

Flux.just(Tuples.of(4L, "d")).transform(printParallelThread).subscribe();
Flux.just(Tuples.of(2L, "b")).transform(printParallelThread).subscribe();
Flux.just(Tuples.of(3L, "c")).transform(printParallelThread).subscribe();
Flux.just(Tuples.of(1L, "a")).transform(printParallelThread).subscribe();

try {
    latch.await();
} catch (InterruptedException interruptedException) {
    interruptedException.printStackTrace();
}
```
Will likely print:
```
d is in virtual time on the thread Thread[parallel-1,5,main] at time 4
b is in virtual time on the thread Thread[parallel-2,5,main] at time 2
c is in virtual time on the thread Thread[parallel-3,5,main] at time 3
a is in virtual time on the thread Thread[parallel-4,5,main] at time 1
```
but the order is not guaranteed because these are happening in parallel.

While not explicitly forbidden, users should not change Schedulers inside a virtual section unless they have a 
solid grasp of its effects on synchronization and assembly. Use the following workaround instead:
```java
// assume this is within a virtual section
.timestamp() // attach the virtual timestamps
.transform(TimeBarriers::EXIT_VIRTUAL_TIME) // back to real time
.publishOn(Schedulers.parallel()) // swap safely
// consider a backpressure strategy here
.transform(TimeBarriers::ENTER_VIRTUAL_TIME) // pick up where we left off
```
Note the upstream virtual section may outpace the downstream, so synchronization may be needed depending on use case.
However this synchronization is still easier than without the workaround.

For more examples, see the tests:

[Flux Tests](src/test/java/org/sireum/hooks/FluxHooksTest.java)

[Mono Tests](src/test/java/org/sireum/hooks/MonoHooksTest.java)

[TimeBarriers Tests](src/test/java/org/sireum/hooks/TimeBarriersTest.java)


<!-- FAQ -->
## FAQ

**Why use reactor-virtual-hooks over reactor-test's virtual-time options such as
[StepVerifier.withVirtualTime()](https://projectreactor.io/docs/test/release/api/reactor/test/StepVerifier.html#withVirtualTime-java.util.function.Supplier-)
or a raw
[VirtualTimeScheduler](https://projectreactor.io/docs/test/release/api/reactor/test/scheduler/VirtualTimeScheduler.html)
?**
Reactor-test's virtual time options require users manually advance the virtual time forward. Additionally, some
Flux/Mono operators work on a particular scheduler by default and thus exit virtual time. StepVerifier.withVirtualTime() 
fixes this by injecting a VirtualTimeScheduler into all Scheduler factories, but this strategy doesn't work for
concurrent Flux/Mono subscriptions which operate on different schedulers. Additionally, reactor-test's virtual time 
options require users manually advance the virtual time forward. This is great for testing, but otherwise not so much.

This library provides additional benefits: 
  - per-subscriber virtual-time schedulers
  - preexisting Flux/Mono transformers can be used used in virtual time without modification
  - the virtual clock is completely managed behind the scenes and can interoperate with non-reactor-core
    [reactive-streams](https://github.com/reactive-streams/reactive-streams-jvm) libraries 
    (note that the virtual-section itself must be comprised of only reactor-core operators however).
  - can be used as a drop-in replacement for reactor-core in preexisting libraries. *Note: there are a few small
    inconsistencies while in beta, but these are all checked for and provide helpful error messages. These will be
    resolved (or be made extremely clear) before any non-beta release is made.*

**Do virtual sections schedule on unique threads?** No. Virtual time is achieved by using a VirtualTimeScheduler as a
clock overtop the previous scheduler. The current thread will be not change when entering a virtual section, and users are welcome
to run multiple virtual sections concurrently, for example by calling `.publishOn(Schedulers.parallel())` upstream to 
the section.

**How does virtual-reactor-hooks keep time-based operators on the virtual scheduler?**
For any operator that has a default scheduler, reactor-virtual-hooks
simply chooses to prefer the subscriber's virtual scheduler to the default if the call was made inside a virtual 
section. 

**How is it determined whether or not a time-based operator exists within a virtual section?**
In any virtual-time section, each subscriber holds a unique virtual-time scheduler within its
[Context](https://projectreactor.io/docs/core/release/reference/#context.api). When a time-based operator is created,
for a Flux/Mono, this virtual-time-based reactor distribution will instead create and return a corresponding
[deferWithContext](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html#deferWithContext-java.util.function.Function-)
operator which become the user's intended time-based operator at subscription time. 

With this strategy, each subscriber's unique virtual-time scheduler can be retrieved from the 
[Context](https://projectreactor.io/docs/core/release/reference/#context.api) and used to instrument the time-based
operator call as needed. If no virtual-time scheduler is found in the Context, then the operator is not within a 
virtual-time section, and result of the user's original (unmodified) call is made.
*Note: this requires Context is not lost
within a virtual section (e.g. by using a 3rd-party operator that doesn't support reactor's Context).*

For users trying to squeeze out every bit of performance,
[TimeBarrier](src/main/java/org/sireum/hooks/TimeBarriers.java)'s `ATTACH_NOT_VIRTUAL_HINT` method can be used to
avoid this deferred check and return the result of the user's original call at assembly time. 
If `ENTER_VIRTUAL_TIME` is later called downstream, the hint is automatically removed
within the virtual-time section and reattached after it completes.

<!-- LICENSE -->
## License
Licensed under [Apache Software License 2.0](www.apache.org/licenses/LICENSE-2.0)

This project redistributes a modified version of the 
[reactor-core](https://github.com/reactor/reactor-core/blob/master/README.md) library that has been (post-compile)
bytecode weaved to support virtual time. The reactor-core sources are also redistributed with 
a modification notice to make them (1) noticeably distinct and (2) compliant with reactor's Apache 2.0 license.