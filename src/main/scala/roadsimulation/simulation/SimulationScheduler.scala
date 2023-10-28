package roadsimulation.simulation

import roadsimulation.simulation.SimulationScheduler.{NoCancellingSupposed, Continuation, EventHandler, EventReference, SimEvent, TimeInThePast, zeroEventKey}
import roadsimulation.simulation.SimulationSchedulerImpl.EventKey
import zio.{Console, Fiber, FiberRef, IO, Promise, Ref, Scope, UIO, URIO, ZIO}

import java.io.IOException
import java.time.Duration
import java.util.concurrent.{ConcurrentMap, ConcurrentNavigableMap, ConcurrentSkipListMap, PriorityBlockingQueue}
import scala.collection.immutable.SortedSet


/**
 * @author Dmitry Openkov
 */
trait SimulationScheduler:
  def schedule[T, U](simEvent: SimEvent[T, U]): UIO[Unit]

  def schedule(time: Double, handler: => UIO[Unit]): UIO[Unit]

  def continueWhen(time: Double, obj: Option[Any] = None): UIO[Continuation]

  def continueNow(obj: Any): UIO[Continuation]

  def startScheduling(): UIO[Unit]


object SimulationScheduler:
  trait EventReference

  case class SimEvent[+T, +U](time: Double, eventType: T)
    (handler: EventHandler[T], cancelHandler: CancelledEventHandler[T, U] = NoCancellingSupposed) {
    def handle(): UIO[Unit] = handler(this.time, this.eventType)

    override def toString: String = "SimEvent(%,.3f, %s)".format(time, eventType)
  }

  val NoCancellingSupposed: CancelledEventHandler[Any, Any] = (expectedTime, eventType, actualTime, userObject) =>
    ZIO.dieMessage(s"No cancelling supposed. input: $expectedTime, $eventType, $actualTime, $userObject")


  type EventHandler[-T] = (Double, T) => UIO[Unit]
  type CancelledEventHandler[-T, -U] = (Double, T, Double, U) => UIO[Unit]

  object SimEvent:
    given orderingByTime: Ordering[SimEvent[_, _]] = Ordering.by(_.time)


  case class Continuation(time: Double, status: ContinuationStatus)

  case class TimeInThePast(
    now: Double,
    event: SimEvent[_, _],
  ) extends Exception(s"Time in the past: ${event.time}, now is $now, eventType = ${event.eventType}")

  enum ContinuationStatus:
    case OnTime, Interrupted

  def make(parallelismWindow: Double, endSimulationTime: Double): URIO[Scope, SimulationSchedulerImpl] =
    for {
      currentEvent <- FiberRef.make(EventKey(-1L, Double.NaN))
      counter <- Ref.make(0L)
      promise <- Promise.make[Nothing, Unit]
      //we put an event that prevents scheduler from get running
    } yield new SimulationSchedulerImpl(counter,
      new ConcurrentSkipListMap(java.util.Collections.singletonMap(zeroEventKey(), ())),
      new ConcurrentSkipListMap(),
      currentEvent,
      promise,
      parallelismWindow,
      endSimulationTime)

  private[simulation] def zeroEventKey() = EventKey(0, Double.NegativeInfinity)

class SimulationSchedulerImpl(
  // event counter to separate events of the same time
  counter: Ref[Long],
  // events that is being processed (using a Map as a Set, hence Unit as a value type)
  beingProcessed: ConcurrentNavigableMap[EventKey, Unit],
  // queued events
  eventQueue: ConcurrentNavigableMap[EventKey, SimEvent[_, _]],
  //
  //  objectMap: ConcurrentMap[Any, Promise],
  currentEventRef: FiberRef[EventKey],
  endPromise: Promise[Nothing, Unit],
  parallelismWindow: Double,
  endSimulationTime: Double,
) extends SimulationScheduler:

  override def schedule[T, U](simEvent: SimEvent[T, U]): UIO[Unit] =
    if (simEvent.time > endSimulationTime)
      Console.printLine("Outside of simulation end time: " + simEvent).orDie
    else for {
      now <- ZIO.succeed(beingProcessed.firstKey().eventTime)
      _ <- ZIO.when(simEvent.time < now)(ZIO.die(TimeInThePast(now, simEvent)))
      number <- counter.updateAndGet(_ + 1)
      eventKey = EventKey(number, simEvent.time)
      shouldBeProcessed = simEvent.time < now + parallelismWindow
      _ <- if (shouldBeProcessed) {
        process(eventKey, simEvent).forkDaemon
      } else {
        ZIO.succeed(eventQueue.put(eventKey, simEvent))
      }
    } yield ()


  def schedule(time: Double, handler: => UIO[Unit]): UIO[Unit] = {
    schedule(SimEvent(time, ())((_, _) => handler, NoCancellingSupposed))
  }


  override def continueWhen(time: Double, obj: Option[Any] = None): UIO[Continuation] =
    for {
      // get the event that triggered this fiber execution
      myEventKey <- currentEventRef.get
      newNumber <- counter.updateAndGet(_ + 1)
      // obviously we need to modify event key since the time is different
      updatedEventKey = myEventKey.copy(eventNumber = newNumber, eventTime = time)
      _ <- currentEventRef.set(updatedEventKey)
      p <- Promise.make[Nothing, Unit]
      // we scheduling a "fake" event for the requrested time
      _ <- schedule(SimEvent(time, ()) { (_, _) =>
        // when it's time we put the original event back to beingProcessed
        ZIO.succeed(beingProcessed.put(updatedEventKey, ())) *>
          // release the fiber
          p.succeed(()).unit
      })
      // now we remove the event that triggered this fiber execution to allow the simulation continue
      _ <- removeEventAndContinue(myEventKey)
      // current fiber must wait until the requested time (until we release it using p Promise)
      _ <- p.await
    } yield Continuation(time, SimulationScheduler.ContinuationStatus.OnTime)


  override def continueNow(obj: Any): UIO[Continuation] = {
    ???
  }

  def startScheduling(): UIO[Unit] =
    for {
      _ <- ZIO.succeed(beingProcessed.remove(zeroEventKey()))
      _ <- processEvents()
      _ <- endPromise.await
    } yield ()

  private def processEvents(): UIO[Unit] =
    import scala.jdk.CollectionConverters._
    for {
      nextEventEntry <- ZIO.succeed(beingProcessed.firstEntry())
      now <- if (nextEventEntry != null) ZIO.succeed(nextEventEntry.getKey.eventTime)
      else ZIO.succeed {
        val firstQueuedEventEntry = eventQueue.firstEntry()
        if (firstQueuedEventEntry != null) firstQueuedEventEntry.getKey.eventTime else Double.NaN
      }
      _ <- ZIO.when(now.isNaN)(endPromise.succeed(()))
      eventsToProcess <- ZIO.succeed {
        import scala.jdk.CollectionConverters._
        val events = eventQueue.headMap(EventKey(0, now + parallelismWindow))
        val keys = events.keySet().asScala
        keys.foldLeft(IndexedSeq.empty[(EventKey, SimEvent[_, _])]) { case (acc, key) =>
          val event = events.remove(key)
          if (event == null) acc
          else acc :+ (key, event)
        }
      }
      _ <- ZIO.foreach(eventsToProcess) { case (eventKey, event) => process(eventKey, event).forkDaemon }
    } yield ()

  private def process(eventKey: EventKey, event: SimEvent[_, _]): UIO[Unit] =
    for {
      _ <- currentEventRef.set(eventKey)
      _ <- ZIO.succeed(beingProcessed.put(eventKey, ()))
      // _ <- Console.printLine(event).orDie
      // _ <- ZIO.sleep(zio.Duration.fromMillis(300))
      _ <- event.handle()
      actualKey <- currentEventRef.get
      _ <- removeEventAndContinue(actualKey)
    } yield ()

  private def removeEventAndContinue(eventKey: EventKey): UIO[Unit] =
    for {
      _ <- ZIO.succeed(beingProcessed.remove(eventKey))
      _ <- processEvents()
    } yield ()


object SimulationSchedulerImpl:
  case class EventKey(eventNumber: Long, eventTime: Double) extends Comparable[EventKey], EventReference {
    override def compareTo(other: EventKey): Int = {
      val timeComp = java.lang.Double.compare(this.eventTime, other.eventTime)
      if (timeComp != 0) timeComp else java.lang.Long.compare(this.eventNumber, other.eventNumber)
    }
  }
