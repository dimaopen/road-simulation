## Discrete-Event Simulation Engine 

This work represents a proof of concept of discrete-event simulation (DES) engine with next-event time progression.
It executes event handlers in parallel in order to have a better performance on multiprocessor machines. 
A key feature of this engine is a possibility to implement models using regular programming approaches 
like calling functions or class methods and keeping the system state in local/class variables.
We think this approach is a better alternative to a usual approach with event handlers or finite-state machine 
because it allows easier understanding and modifications complex system comparing to go through the multiple weakly
related event handler/producers.

### Regular function/method calls instead of event handling
The main entity is `SimulationScheduler` which handles simulation event queue. The modelling entity can call method `continueWhen (time)`. This method holds program execution until the simulation time reaches `time` value. When the calling entity receives back the execution control that generally means that now the time is `time` and the entity state should be updated.

### Interrupting of entity holding
When we model systems using DES with next-event time progression it is more natural to be able to cancel the following event if some other interfering event happens. I.e. if we are modelling a ride-hail vehicle, and we are having the next event to be the arrival of the vehicle to the destination then an interrupting event could be a customer request that happens in the middle of the vehicle movement. In this case, the customer entity can call `SimulationScheduler.cancel` method providing a data object. And the interrupted entity (the ride-hail vehicle in our case) immediately receives execution control back because `continueWhen` method exits at that time. It also returns the data object provided by the interrupting entity. That helps to interchange data between these 2 entities.

### Implementation
The engine is written in [Scala programming language](https://www.scala-lang.org/) using [ZIO library](https://zio.dev). As you probably know, one cannot create more than roughly 10<sup>5</sup> native program threads in modern operating systems. Switching thread context is also an expensive operation. If we want to model millions of entities which execution can be held, then we have to use 'green threads' of fibers. In our case, we use ZIO implementation of fibers. It also helps us to avoid stack overflow exceptions in case we do recursion calls which are pretty natural in this approach.

### To be done
1. We should be able to cease simulation in the case of a long no-progress which usually indicates programming errors (simulation got stuck).
2. Saving scheduler's internal event queue in case of issues.
3. Saving event sequences for debug purposes.

### Other implementations
We can implement the same approach using the recently introduced in Java language virtual threads. It can be done in
both Java or Scala languages.


