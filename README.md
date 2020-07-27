<!-- ABOUT THE PROJECT -->
## About The Project



<!-- GETTING STARTED -->
## Getting Started

#### Maven
```maven
<dependency>
    <groupId>org.sireum.hooks</groupId>
    <artifactId>virtual-reactor-hooks</artifactId>
    <version>3.3.5.RELEASE-beta</version>
</dependency>
```

#### Gradle
```gradle
compile group: 'org.sireum.hooks', name: 'virtual-reactor-hooks', version: '3.3.5.RELEASE-beta'
```

#### Sbt
```sbt
libraryDependencies += "org.sireum.hooks" % "virtual-reactor-hooks" % "3.3.5.RELEASE-beta"
```

<!-- USAGE EXAMPLES -->
## Usage

There are two prerequisites needed for virtual-time scheduling.
1. **Have a Flux (or Mono) of timestamped elements.**
    * Each element must be paired with its timestamp. This is done by converting element `T` to tuple `Tuple2<Long,T>`, 
    where the timestamp is the tuple's first element.
    
2. **Identify the boundaries of a "virtual section"**
    * Use the `.transform()` operator with `TimeBarriers.ENTER_VIRTUAL_TIME` to start the virtual section and 
    `TimeBarriers.EXIT_VIRTUAL_TIME` to stop it.

### Example 1
The basic structure of a Flux with a virtual section.
```java
final Tuple2<Long,String> a = Tuples.of(2000L, "Event A"); // "Event A" occurs at 2 sec
final Tuple2<Long,String> b = Tuples.of(4000L, "Event B"); // "Event B" occurs at 4 sec
final Tuple2<Long,String> c = Tuples.of(6000L, "Event C"); // "Event C" occurs at 6 sec

Flux<String> flux = Flux.just(a, b, c)
        // we are in real time
    .transform(TimeBarriers::ENTER_VIRTUAL_TIME) // consumes the timestamps
        // we are now in virtual time!
    .transform(TimeBarriers::EXIT_VIRTUAL_TIME)
        // we are back to real time
```

### Example 2
When in a virtual section, any time-based operator called without a specific `Scheduler` will use a `VirtualTimeScheduler` behind the scenes.
```java
Flux<Tuple2<Long, String>> stampedAgain = Flux.just(a, b, c)
    .transform(TimeBarriers::ENTER_VIRTUAL_TIME)
    .timestamp()
    .doOnNext(System.out::println)
    .transform(TimeBarriers::EXIT_VIRTUAL_TIME);
```
Will output:
```
[2000,a]
[4000,b]
[6000,c]
```

For many more examples, see the test cases:

[Flux Tests](src/test/java/org/sireum/hooks/FluxHooksTest.java)

[Mono Tests](src/test/java/org/sireum/hooks/MonoHooksTest.java)

<!-- FAQ -->
## FAQ

**Why use reactor-virtual-hooks over reactor-test's virtual-time options such as
[VirtualTimeScheduler](https://projectreactor.io/docs/test/release/api/reactor/test/scheduler/VirtualTimeScheduler.html)
or
[StepVerifier.withVirtualTime()](https://projectreactor.io/docs/test/release/api/reactor/test/StepVerifier.html#withVirtualTime-java.util.function.Supplier-)
?**
Reactor-test's virtual time options require users manually advance the virtual time forward. Additionally, some
operators work on a particular scheduler by default and thus exit virtual time. StepVerifier.withVirtualTime() fixes
this by injecting a VirtualTimeScheduler into all Scheduler factories, but this strategy doesn't work for concurrent 
Flux/Mono subscriptions which operate on different schedulers.

**Do virtual sections have unique schedulers?** No. Virtual time is achieved by using a VirtualTimeScheduler as a
clock, not a scheduler.

For any operator that has a default scheduler, reactor-virtual-hooks
simply chooses to prefer the subscriber's virtual scheduler to the default if the call was made inside a virtual 
section.

<!-- LICENSE -->
## License
Licensed under [Apache Software License 2.0](www.apache.org/licenses/LICENSE-2.0)

This project redistributes a modified version of the 
[reactor-core](https://github.com/reactor/reactor-core/blob/master/README.md) library that has been (post-compile)
bytecode weaved to support virtual time. The reactor-core sources are also redistributed with 
a modification notice to make them visually distinct and compliant with reactor's Apache 2.0 license.