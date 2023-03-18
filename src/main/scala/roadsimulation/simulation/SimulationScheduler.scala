package roadsimulation.simulation

import roadsimulation.simulation.SimulationScheduler.{EventHandler, HoldFinished, NoHandler, SimEvent, TimeInThePast}
import roadsimulation.simulation.SimulationSchedulerImpl.EventContainer
import zio.{Console, Fiber, FiberRef, IO, Promise, Ref, Scope, UIO, URIO, ZIO}

import java.io.IOException
import java.time.Duration
import java.util.concurrent.PriorityBlockingQueue
import scala.collection.immutable.SortedSet


/**
 * @author Dmitry Openkov
 */
trait SimulationScheduler:
  def schedule[T](simEvent: SimEvent[T]): UIO[Unit]

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

  case class HoldFinished(time: Double, status: HoldExitStatus)

  case class TimeInThePast(
    now: Double,
    event: SimEvent[_],
  ) extends Exception(s"Time in the past: ${event.time}, now is $now, eventType = ${event.eventType}")

  enum HoldExitStatus:
    case ItsTime, Interrupted

  def make(): URIO[Scope, SimulationSchedulerImpl] =
    for {
      currentEvent <- FiberRef.make(null.asInstanceOf[EventContainer])
      counter <- Ref.make(1L)
      //we put an event that prevents scheduler from get running
      processedSet <- Ref.make(SortedSet(zeroEvent()))
      eventQueue <- Ref.make(SortedSet.empty[EventContainer])
    } yield new SimulationSchedulerImpl(counter, processedSet, eventQueue, currentEvent, 30.0)

  private[simulation] def zeroEvent() = EventContainer(0, SimEvent(Double.NegativeInfinity, null))

class SimulationSchedulerImpl(
  // event counter to separate events of the same time
  counter: Ref[Long],
  // events that is being processed
  beingProcessedRef: Ref[SortedSet[EventContainer]],
  // queued events
  eventQueueRef: Ref[SortedSet[EventContainer]],
  currentEventRef: FiberRef[EventContainer],
  parallelismWindow: Double
) extends SimulationScheduler:

  override def schedule[T](simEvent: SimEvent[T]): UIO[Unit] =
    for {
      beingProcessed <- beingProcessedRef.get
      now = getTimeOfFirstEvent(beingProcessed, Double.NaN)
      _ <- ZIO.when(simEvent.time < now)(ZIO.die(TimeInThePast(now, simEvent)))
      number <- counter.updateAndGet(_ + 1)
      eventContainer = EventContainer(number, simEvent)
      shouldBeProcessed = now.isNaN || simEvent.time < now + parallelismWindow
      _ <- if (shouldBeProcessed) {
        beingProcessedRef.update(_ + eventContainer) *> process(eventContainer).forkDaemon
      } else {
        eventQueueRef.update { queue => queue + eventContainer }
      }
    } yield ()


  override def holdUntil(time: Double): UIO[HoldFinished] =
    for {
      myEventContainer <- currentEventRef.get
//      _ <- Console.printLine(s"myEventContainer = $myEventContainer").orDie
      updatedEvent = myEventContainer.event.copy(time = time)(NoHandler)
      containerWithUpdatedTime = myEventContainer.copy(event = updatedEvent)
      _ <- currentEventRef.set(containerWithUpdatedTime)
      p <- Promise.make[Nothing, Unit] //todo maybe Semaphore ?
      _ <- schedule(SimEvent(time, ()) { case _ =>
        // when it's time we put the fiber container back to beingProcessed
        beingProcessedRef.update(_ + containerWithUpdatedTime) *>
        p.succeed(()).unit
      })
      _ <- removeContainerAndContinue(myEventContainer)
      _ <- p.await
    } yield HoldFinished(time, SimulationScheduler.HoldExitStatus.ItsTime)


  def startScheduling(): UIO[Unit] =
    beingProcessedRef.update(_ - SimulationScheduler.zeroEvent())
      *> processEvents()


  private def getTimeOfFirstEvent(events: Set[EventContainer], default: => Double) =
    events.headOption.fold(default)(_.event.time)

  private def processEvents(): UIO[Unit] =
    for {
      beingProcessed <- beingProcessedRef.get
//      q <- eventQueueRef.get
//      _ <- Console.printLine(s"beingProcessed = $beingProcessed, q = $q").orDie
      eventsToProcess <- eventQueueRef.modify { queue =>
        val now = getTimeOfFirstEvent(beingProcessed, getTimeOfFirstEvent(queue, 0))
        val separator = EventContainer(0, SimEvent(now + parallelismWindow, null))
        val toBeProcessed = queue.rangeUntil(separator)
        val futureEvents = queue.rangeFrom(separator)
        (toBeProcessed, futureEvents)
      }
      _ <- ZIO.when(eventsToProcess.nonEmpty)(beingProcessedRef.update(_ ++ eventsToProcess))
      _ <- ZIO.foreach(eventsToProcess)(eventContainer => process(eventContainer).forkDaemon)
    } yield ()

  private def process(eventContainer: EventContainer): UIO[Unit] =
    for {
      _ <- currentEventRef.set(eventContainer)
      _ <- Console.printLine(eventContainer.event).orDie
      //      _ <- ZIO.sleep(zio.Duration.fromMillis(300))
      _ <- eventContainer.event.handle()
      actualContainer <- currentEventRef.get
      _ <- removeContainerAndContinue(actualContainer)
    } yield ()

  private def removeContainerAndContinue(container: EventContainer): UIO[Unit] =
    for {
      shouldContinue <- beingProcessedRef.modify { beingProcessed =>
        val newBeingProcessed = beingProcessed - container
        val shouldContinue = newBeingProcessed
          .isEmpty // || newBeingProcessed.head.event.time > container.event.time //FIXME race condition?
        shouldContinue -> newBeingProcessed
      }
      _ <- ZIO.when(shouldContinue)(processEvents())
    } yield ()


object SimulationSchedulerImpl:
  case class EventContainer(eventNumber: Long, event: SimEvent[_])

  given containerOrdering: Ordering[EventContainer] =
    Ordering.by(container => container.event.time -> container.eventNumber)
