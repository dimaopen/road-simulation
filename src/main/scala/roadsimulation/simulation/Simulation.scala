package roadsimulation.simulation

import roadsimulation.actor.{FillingStationHandler, MethodVehicleHandler, PersonHandler, RoadEventType, VehicleHandlerImpl}
import roadsimulation.model.event.StoredRoadEvent
import roadsimulation.model.{Person, PositionKey, Scenario, Vehicle}
import roadsimulation.simulation.SimulationScheduler.EventReference
import zio.*
import zio.stream.*

import java.util.concurrent.{ConcurrentSkipListMap, PriorityBlockingQueue, TimeUnit}

/**
 * @author Dmitry Openkov
 */
object Simulation {

  def simulate(scenario: Scenario): ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for {
        scheduler <- SimulationScheduler.make(parallelismWindow = 30, endSimulationTime = 3600 * 24)
        messageHub <- Hub.bounded[StoredRoadEvent](1<<12)
        _ <- EventWriter.fromHub(messageHub).fork
        fillingStationHandler <- FillingStationHandler.make(scenario, scheduler)
        personsOnRoad = new ConcurrentSkipListMap[PositionKey[Person], (Person, EventReference[Vehicle])](PositionKey.ordering)
        vehicleHandler = new MethodVehicleHandler(scenario, scheduler, fillingStationHandler, personsOnRoad, messageHub)
        personHandler = new PersonHandler(scenario, vehicleHandler, personsOnRoad, scheduler, messageHub)
        _ <- vehicleHandler.scheduleInitialEvents(scenario)
        _ <- personHandler.scheduleInitialEvents(scenario)
        start <- zio.Clock.currentTime(TimeUnit.MILLISECONDS)
        _ <- scheduler.startScheduling()
        end <- zio.Clock.currentTime(TimeUnit.MILLISECONDS)
        eventsBeyondSimEndTime <- scheduler.eventQueueSize
        _ <- Console.printLine(s"Simulation took ${end - start} ms, queue still contains $eventsBeyondSimEndTime events").orDie
      } yield ()

    }
}
