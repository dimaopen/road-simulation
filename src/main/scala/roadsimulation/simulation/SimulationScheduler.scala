package roadsimulation.simulation

import roadsimulation.simulation.SimulationScheduler.{EventHandler, HoldFinished, NoHandler, SimEvent, TimeInThePast}
import roadsimulation.simulation.SimulationSchedulerImpl.EventContainer
import zio.{IO, Promise, Ref, UIO, ZIO, Console}

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
  case class TimeInThePast(now: Double, scheduleTime: Double) extends Exception

  enum HoldStatus:
    case ItsTime, Interrupted

  def make[T](): UIO[SimulationScheduler[T]] =
    for {
      counter <- Ref.make(0L)
      eventQueue <- Ref.make((SortedSet.empty[EventContainer[T]], SortedSet.empty[EventContainer[T]]))
    } yield new SimulationSchedulerImpl[T](counter, eventQueue, 30.0)

class SimulationSchedulerImpl[T](
  // event counter to separate events of the same time
  counter: Ref[Long],
  // the first set contains events that is being processed, the second one is the queued events
  eventQueue: Ref[(SortedSet[EventContainer[T]], SortedSet[EventContainer[T]])],
  parallelismWindow: Double
) extends SimulationScheduler[T]:
  override def schedule(simEvent: SimEvent[T]): UIO[Unit] =
  // just put the event in the queue
    for {
      number <- counter.updateAndGet(_ + 1)
      oldNow <- eventQueue.modify { case (beingProcessed, queue) =>
        val now = getNowFromBeingProcessed(beingProcessed)
        now -> (beingProcessed, queue + EventContainer(number, simEvent))
      }
      _ <- ZIO.when(simEvent.time < oldNow)(ZIO.die(TimeInThePast(oldNow, simEvent.time)))
      _ <- processEvents()
    } yield ()


  case class Holder(promise: Promise[Nothing, Unit])

  override def holdUntil(time: Double): UIO[HoldFinished] =
    for {
      p <- Promise.make[Nothing, Unit]
      _ <- schedule(SimEvent(time, Holder(p).asInstanceOf[T])(((h: Holder) => h.promise.succeed(()).unit).asInstanceOf[EventHandler[T]]))
      _ <- p.await
    } yield HoldFinished(time, SimulationScheduler.HoldStatus.ItsTime)


  def startScheduling(): UIO[Unit] = processEvents()

  private def getTimeOfFirstEvent(events: Set[EventContainer[T]], default: => Double) =
    events.headOption.fold(default)(_.event.time)

  private def getNowFromBeingProcessed(beingProcessed: SortedSet[EventContainer[T]]) =
    getTimeOfFirstEvent(beingProcessed, 0)

  private def getNowFromQueue(beingProcessed: SortedSet[EventContainer[T]], queue: SortedSet[EventContainer[T]]) =
    getTimeOfFirstEvent(beingProcessed, getTimeOfFirstEvent(queue, 0))

  private def processEvents(): UIO[Unit] =
    for {
      eventsToProcess <- eventQueue.modify { case (beingProcessed, queue) =>
        val now = getNowFromQueue(beingProcessed, queue)
        val separator = EventContainer[T](0, SimEvent(now + parallelismWindow, null.asInstanceOf[T]))
        val toBeProcessed = queue.rangeUntil(separator)
        val futureEvents = queue.rangeFrom(separator)
        (toBeProcessed, (beingProcessed ++ toBeProcessed, futureEvents))
      }
      fibers <- ZIO.foreach(eventsToProcess) ( ec => process(ec))
    } yield ()

  def process(eventContainer: EventContainer[T]): UIO[Unit] = {
    Console.printLine(eventContainer.event).fold(e => throw new RuntimeException("shouldn't happen", e), identity) *>
      eventContainer.event.handle() *>
      eventQueue.update { case (beingProcessed, queue) => (beingProcessed - eventContainer, queue) }
  }


object SimulationSchedulerImpl:
  case class EventContainer[T](eventNumber: Long, event: SimEvent[T])

  given containerOrdering[T]: Ordering[EventContainer[T]] =
    Ordering.by(container => container.event.time -> container.eventNumber)
