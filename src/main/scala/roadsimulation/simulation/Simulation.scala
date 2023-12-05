package roadsimulation.simulation

import roadsimulation.actor.{FillingStationHandler, MethodVehicleHandler, PersonHandler, RoadEventType, VehicleHandlerImpl}
import roadsimulation.model.{Person, PositionKey, Scenario, Vehicle}
import roadsimulation.simulation.SimulationScheduler.EventReference
import zio.*

import java.util.concurrent.{ConcurrentSkipListMap, PriorityBlockingQueue, TimeUnit}

/**
 * @author Dmitry Openkov
 */
object Simulation {

  def simulate(scenario: Scenario): UIO[Unit] =
    ZIO.scoped {
      for {
        scheduler <- SimulationScheduler.make(parallelismWindow = 30, endSimulationTime = 3600 * 24)
        fillingStationHandler <- FillingStationHandler.make(scenario, scheduler)
        personsOnRoad = new ConcurrentSkipListMap[PositionKey[Person], (Person, EventReference[Vehicle])](PositionKey.ordering)
        vehicleHandler = new MethodVehicleHandler(scenario, scheduler, fillingStationHandler, personsOnRoad)
        personHandler = new PersonHandler(scenario, vehicleHandler, personsOnRoad, scheduler)
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
