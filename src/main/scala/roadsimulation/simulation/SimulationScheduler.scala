package roadsimulation.simulation

import roadsimulation.simulation.SimulationScheduler.{EventHandler, HoldFinished, NoHandler, SimEvent, TimeInThePast}
import roadsimulation.simulation.SimulationSchedulerImpl.EventContainer
import zio.{Console, Fiber, IO, Promise, Ref, UIO, ZIO}

import java.io.IOException
import java.time.Duration
import java.util.concurrent.PriorityBlockingQueue
import scala.collection.immutable.SortedSet


/**
 * @author Dmitry Openkov
 */
trait SimulationScheduler[-T]:
  def schedule(simEvent: SimEvent[T]): UIO[Unit]

  def holdUntil(time: Double): UIO[HoldFinished]

  def startScheduling(): UIO[Unit]


object SimulationScheduler:
  case class SimEvent[+T](time: Double, eventType: T)(handler: EventHandler[T] = NoHandler) {
    def handle(): UIO[Unit] = handler(this)

    override def toString: String = "SimEvent(%,.1f, %s)".format(time, eventType)
  }

  type EventHandler[-T] = SimEvent[T] => UIO[Unit]

  val NoHandler: EventHandler[Any] = _ => ZIO.succeed(())

  object SimEvent:
    given orderingByTime: Ordering[SimEvent[_]] = Ordering.by(_.time)

    def apply[T](time: Double, eventType: T): SimEvent[T] = SimEvent(time, eventType)(NoHandler)

  case class HoldFinished(time: Double, status: HoldStatus)

  case class TimeInThePast(
    now: Double,
    scheduleTime: Double
  ) extends Exception(s"Time in the past: $scheduleTime, now is $now")

  enum HoldStatus:
    case ItsTime, Interrupted

  def make[T](): UIO[SimulationScheduler[T]] =
    for {
      counter <- Ref.make(1L)
      //we put an event that prevents scheduler from get running
      processedSet <- Ref.make(SortedSet(zeroEvent[T]()))
      eventQueue <- Ref.make(SortedSet.empty[EventContainer[T]])
    } yield new SimulationSchedulerImpl[T](counter, processedSet, eventQueue, 30.0)

  private[simulation] def zeroEvent[T]() = EventContainer(0, SimEvent(Double.NegativeInfinity, null.asInstanceOf[T]))

class SimulationSchedulerImpl[T]( //todo move type parameter to schedule method
  // event counter to separate events of the same time
  counter: Ref[Long],
  // events that is being processed
  processedSet: Ref[SortedSet[EventContainer[T]]],
  // queued events
  eventQueue: Ref[SortedSet[EventContainer[T]]],
  parallelismWindow: Double
) extends SimulationScheduler[T]:

  override def schedule(simEvent: SimEvent[T]): UIO[Unit] =
  // just put the event in the queue
    for {
      beingProcessed <- processedSet.get
      now = getTimeOfFirstEvent(beingProcessed, Double.NaN)
      _ <- ZIO.when(simEvent.time < now)(ZIO.die(TimeInThePast(now, simEvent.time)))
      number <- counter.updateAndGet(_ + 1)
      _ <- eventQueue.update { queue =>
        queue + EventContainer(number, simEvent)
      }
      _ <- ZIO.when(now.isNaN || simEvent.time < now + parallelismWindow) {
        processEvents()
      }
    } yield ()


  case class Holder(promise: Promise[Nothing, Unit])

  override def holdUntil(time: Double): UIO[HoldFinished] =
    for {
      myFiberDescriptor <- ZIO.descriptor
      _ <- processedSet.update { beingProcessed =>
        //todo O(1)
        val maybeMyContainer = beingProcessed.find(container => container.fiber.id.id == myFiberDescriptor.id.id)
        maybeMyContainer.fold(beingProcessed)(beingProcessed - _)
      }
      p <- Promise.make[Nothing, Unit]
      _ <- schedule(SimEvent(time, Holder(p).asInstanceOf[T]) {
        case SimEvent(_, Holder(promise)) => promise.succeed(()).unit
      })
      _ <- p.await
    } yield HoldFinished(time, SimulationScheduler.HoldStatus.ItsTime)


  def startScheduling(): UIO[Unit] =
    processedSet.update(_ - SimulationScheduler.zeroEvent())
      *> processEvents()


  private def getTimeOfFirstEvent(events: Set[EventContainer[T]], default: => Double) =
    events.headOption.fold(default)(_.event.time)

  private def processEvents(): UIO[Unit] =
    for {
      beingProcessed <- processedSet.get
      q <- eventQueue.get
      _ <- Console.printLine(s"beingProcessed = $beingProcessed, q = $q")
        .fold((_: IOException) => throw new RuntimeException(), identity)
      eventsToProcess <- eventQueue.modify { queue =>
        val now = getTimeOfFirstEvent(beingProcessed, getTimeOfFirstEvent(queue, 0))
        val separator = EventContainer[T](0, SimEvent(now + parallelismWindow, null.asInstanceOf[T]))
        val toBeProcessed = queue.rangeUntil(separator)
        val futureEvents = queue.rangeFrom(separator)
        (toBeProcessed, futureEvents)
      }
      toProcessWithFibers <- ZIO.foreach(eventsToProcess)(container => process(container).fork
        .map(fiber => container.copy(fiber = fiber))
      )
      _ <- ZIO.when(toProcessWithFibers.nonEmpty)(processedSet.update(_ ++ toProcessWithFibers))
      _ <- ZIO.foreach(toProcessWithFibers)(container => container.fiber.join)
    } yield ()

  private def process(eventContainer: EventContainer[T]): UIO[Unit] = {
    for {
      _ <- Console.printLine(eventContainer.event)
        .fold(e => throw new RuntimeException("shouldn't happen", e), identity)
      //      _ <- ZIO.sleep(zio.Duration.fromMillis(300))
      _ <- eventContainer.event.handle()
      //todo probably keep now as double directly, and beingProcessed is to be just a Set
      shouldContinue <- processedSet.modify { beingProcessed =>
        val newBeingProcessed = beingProcessed - eventContainer
        val shouldContinue = newBeingProcessed.isEmpty || newBeingProcessed.head.event.time > eventContainer.event.time
        shouldContinue -> newBeingProcessed
      }
      _ <- ZIO.when(shouldContinue)(processEvents())
    } yield ()
  }


object SimulationSchedulerImpl:
  case class EventContainer[T](eventNumber: Long, event: SimEvent[T], fiber: Fiber.Runtime[Nothing, Unit] = null)

  given containerOrdering[T]: Ordering[EventContainer[T]] =
    Ordering.by(container => container.event.time -> container.eventNumber)
