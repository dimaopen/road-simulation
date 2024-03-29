package roadsimulation.simulation

import roadsimulation.simulation.SimulationScheduler.*
import roadsimulation.simulation.SimulationSchedulerImpl.EventKey
import zio.{Console, Fiber, FiberRef, IO, Promise, Ref, Scope, UIO, URIO, ZIO}

import java.io.IOException
import java.time.Duration
import java.util.concurrent.*
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.SortedSet


/**
 * @author Dmitry Openkov
 */
trait SimulationScheduler:
  def schedule[T, I](simEvent: SimEvent[T, I]): UIO[EventReference[I]]

  def schedule[I](time: Double, handler: => UIO[Unit]): UIO[EventReference[I]]

  /**
   * Cancel an event
   * @param eventReference the reference to the even being cancelled
   * @param data a data object to be passed to the cancel handler of the cancelled event
   * @tparam I the type of the data object
   * @return a Boolean indicating that the the event was really cancelled. An event may not be cancelled in case
   *         it has already happened (has been processed by the scheduler)
   */
  def cancel[I](eventReference: EventReference[I], data: I): UIO[Boolean]

  /**
   * This method is a (maybe better) alternative to event scheduling. It holds the current fiber execution until
   * simulation `time` is.  
   * @param time the time until the current fiber is hold
   * @param onHold a callback function that can be used to get the event reference 
   *               (for cancelling of this hold if needed) 
   * @tparam I The data type that is expected to be provided at cancelling
   * @return an object that contains the current time (at the moment of continuation of the fiber execution)
   *         and the continuation status (OnTime or Interrupted)
   */
  def continueWhen[I](
    time: Double,
    onHold: EventReference[I] => UIO[Unit] = (_: EventReference[I]) => ZIO.unit
  ): UIO[Continuation[I]]

  def startScheduling(): UIO[Unit]


object SimulationScheduler:
  trait EventReference[+I]:
    def eventTime: Double
  end EventReference

  object NoEventReference extends EventReference[Nothing] {
    override def eventTime: Double = Double.NaN
  }


  case class SimEvent[+T, I](time: Double, eventType: T)
    (handler: EventHandler[T], cancelHandler: CancelledEventHandler[T, I] = NoCancellingSupposed) {
    def handle(): UIO[Unit] = handler(this.time, this.eventType)

    def cancel(cancelTime: Double, data: I): UIO[Unit] =
    // cancelling may happen in an event sequence that happens before this event due to parallelism window
      cancelHandler(time, eventType, math.max(time, cancelTime), data)

    override def toString: String = "SimEvent(%,.3f, %s)".format(time, eventType)
  }

  val NoCancellingSupposed: CancelledEventHandler[Any, Any] = (expectedTime, eventType, actualTime, userObject) =>
    ZIO.dieMessage(s"No cancelling supposed. input: $expectedTime, $eventType, $actualTime, $userObject")

  type EventHandler[-T] = (Double, T) => UIO[Unit]
  type CancelledEventHandler[-T, -I] = (Double, T, Double, I) => UIO[Unit]

  object SimEvent:
    given orderingByTime: Ordering[SimEvent[?, _]] = Ordering.by(_.time)


  case class Continuation[+I](time: Double, status: ContinuationStatus[I])

  case class TimeInThePast(
    now: Double,
    event: SimEvent[?, ?],
  ) extends Exception(s"Time is in the past: ${event.time}, now is $now, eventType = ${event.eventType}")

  case class TimeIsNotDefined(
    event: SimEvent[?, ?],
  ) extends Exception(s"Time is not defined for event $event")

  sealed trait ContinuationStatus[+I]

  object OnTime extends ContinuationStatus[Nothing]

  case class Interrupted[+I](data: I) extends ContinuationStatus[I]

  def make(parallelismWindow: Double, endSimulationTime: Double): URIO[Scope, SimulationSchedulerImpl] =
    for {
      currentEvent: FiberRef[EventKey[?]] <- FiberRef.make(EventKey(-1L, Double.NaN))
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

  private[simulation] def zeroEventKey() = EventKey[Any](0, Double.NegativeInfinity)

class SimulationSchedulerImpl(
  // event counter to separate events of the same time
  counter: Ref[Long],
  // events that is being processed (using a Map as a Set, hence Unit as a value type)
  beingProcessed: ConcurrentNavigableMap[EventKey[?], Unit],
  // queued events
  eventQueue: ConcurrentNavigableMap[EventKey[?], SimEvent[?, ?]],
  //
  //  objectMap: ConcurrentMap[Any, Promise],
  currentEventRef: FiberRef[EventKey[?]],
  endPromise: Promise[Nothing, Unit],
  parallelismWindow: Double,
  endSimulationTime: Double,
) extends SimulationScheduler:

  def eventQueueSize: UIO[Int] = ZIO.succeed(eventQueue.size())

  override def schedule[T, I](simEvent: SimEvent[T, I]): UIO[EventReference[I]] =
    if (simEvent.time.isNaN)
      ZIO.die(TimeIsNotDefined(simEvent))
    else for {
      lowTime <- ZIO.succeed(beingProcessed.firstKey().eventTime)
      eventRef <- currentEventRef.get
      currentTime = if !eventRef.eventTime.isNaN then eventRef.eventTime else lowTime
      _ <- ZIO.when(simEvent.time < currentTime)(ZIO.die(TimeInThePast(lowTime, simEvent)))
      number <- counter.updateAndGet(_ + 1)
      eventKey = EventKey[I](number, simEvent.time)
      shouldBeProcessed = simEvent.time < lowTime + parallelismWindow
      _ <- if (shouldBeProcessed) {
        process(eventKey, simEvent).forkDaemon
      } else {
        ZIO.succeed(eventQueue.put(eventKey, simEvent))
      }
    } yield eventKey


  override def schedule[I](time: Double, handler: => UIO[Unit]): UIO[EventReference[I]] = {
    schedule(SimEvent(time, ())((_, _) => handler, NoCancellingSupposed))
  }

  override def cancel[I](eventReference: EventReference[I], data: I): UIO[Boolean] =
    eventReference match
      case eventKey: EventKey[?] =>
        for {
          evRef <- currentEventRef.get
          _ <- ZIO.when(evRef.eventTime.isNaN) {
            ZIO.dieMessage("Cancelling must be happen within scheduler context." +
              s" Use 'scheduler.schedule(cancelTime, scheduler.cancel(eventRef, data))'. ($eventReference, $data)")
          }
          simEvent <- ZIO.succeed(eventQueue.remove(eventKey).asInstanceOf[SimEvent[?, I]])
          cancelledEventExists = simEvent != null
          _ <- ZIO.when(cancelledEventExists) {
            simEvent.cancel(evRef.eventTime, data)
          }
        } yield cancelledEventExists
      case SimulationScheduler.NoEventReference => ZIO.succeed(false)
      case _ => ZIO.dieMessage(s"Unknown eventReference: $eventReference")

  override def continueWhen[I](time: Double, onHold: EventReference[I] => UIO[Unit]): UIO[Continuation[I]] =
    for {
      // get the event that triggered this fiber execution
      myEventKey <- currentEventRef.get
      p <- Promise.make[Nothing, (Continuation[I], EventKey[I])]
      // we scheduling a "release fiber" event for the requested time
      eventRef <- schedule(SimEvent(time, ())({ (_, _) => {
        // obviously we need to modify event key since the time is different
        val updatedEventKey = myEventKey.copy(eventTime = time).asInstanceOf[EventKey[I]]
        for {
          // when it's time we put our event to beingProcessed
          _ <- ZIO.succeed(beingProcessed.put(updatedEventKey, ()))
          // release the fiber
          _ <- p.succeed(Continuation(time, SimulationScheduler.OnTime) -> updatedEventKey).unit
        } yield ()
      }
      }, cancelHandler = { (time, eventType, cancelTime, cancelData: I) => {
        // cancel handler works in the fiber where scheduler.cancel(..) was called
        // obviously we need to modify event key since the time is different
        val updatedEventKey = myEventKey.copy(eventTime = cancelTime).asInstanceOf[EventKey[I]]
        for {
          // when it's time we put our event to beingProcessed
          _ <- ZIO.succeed(beingProcessed.put(updatedEventKey, ()))
          // release the fiber
          _ <- p.succeed(Continuation(cancelTime, SimulationScheduler.Interrupted(cancelData)) -> updatedEventKey).unit
        } yield ()
      }
      }))
      // call the user function providing the event reference
      _ <- onHold(eventRef)
      // now we remove the event that triggered this fiber execution to allow the simulation continue
      _ <- removeEventAndContinue(myEventKey)
      // current fiber must wait until the requested time (until we release it using p Promise)
      handlerResult <- p.await
      // we put the updated event that came from one of the event handlers to the current fiber event ref
      _ <- currentEventRef.set(handlerResult._2)
    } yield handlerResult._1

  def startScheduling(): UIO[Unit] =
    for {
      _ <- ZIO.succeed(beingProcessed.remove(zeroEventKey()))
      _ <- processEvents()
      _ <- endPromise.await
    } yield ()

  private def processEvents(): UIO[Unit] =
    import scala.jdk.CollectionConverters.*
    for {
      nextEventEntry <- ZIO.succeed(beingProcessed.firstEntry())
      lowTime <- if (nextEventEntry != null)
        ZIO.succeed(nextEventEntry.getKey.eventTime)
      else
        ZIO.succeed {
          val firstQueuedEventEntry = eventQueue.firstEntry()
          if (firstQueuedEventEntry != null) firstQueuedEventEntry.getKey.eventTime else Double.PositiveInfinity
        }
      eventsToProcess <- if lowTime >= endSimulationTime then
        endPromise.succeed(())
          .as(Seq.empty[(EventKey[?], SimEvent[?, ?])])
      else
        ZIO.succeed {
          import scala.jdk.CollectionConverters.*
          val events = eventQueue.headMap(EventKey(0, lowTime + parallelismWindow))
          val keys = events.keySet().asScala
          keys.foldLeft(IndexedSeq.empty[(EventKey[?], SimEvent[?, ?])]) { case (acc, key) =>
            val event = events.remove(key)
            if (event == null) acc
            else acc :+ (key, event)
          }
        }
      _ <- ZIO.foreach(eventsToProcess) { case (eventKey, event) => process(eventKey, event).forkDaemon }
    } yield ()

  private def process[I](eventKey: EventKey[I], event: SimEvent[?, ?]): UIO[Unit] =
    for {
      _ <- currentEventRef.set(eventKey)
      _ <- ZIO.succeed(beingProcessed.put(eventKey, ()))
      // _ <- Console.printLine(event).orDie
      // _ <- ZIO.sleep(zio.Duration.fromMillis(300))
      _ <- event.handle()
      actualKey <- currentEventRef.get
      _ <- removeEventAndContinue(actualKey)
    } yield ()

  private def removeEventAndContinue(eventKey: EventKey[?]): UIO[Unit] =
    for {
      _ <- ZIO.succeed(beingProcessed.remove(eventKey))
      _ <- processEvents()
    } yield ()


object SimulationSchedulerImpl:
  case class EventKey[I](eventNumber: Long, eventTime: Double) extends Comparable[EventKey[I]], EventReference[I] {
    override def compareTo(other: EventKey[I]): Int = {
      val timeComp = java.lang.Double.compare(this.eventTime, other.eventTime)
      if (timeComp != 0) timeComp else java.lang.Long.compare(this.eventNumber, other.eventNumber)
    }
  }
