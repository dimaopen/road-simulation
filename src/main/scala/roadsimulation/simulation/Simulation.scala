package roadsimulation.simulation

import roadsimulation.actor.{FillingStationHandler, MethodVehicleHandler, RoadEventType, VehicleHandlerImpl}
import roadsimulation.model.{Scenario, Vehicle}
import zio.*

import java.util.concurrent.{PriorityBlockingQueue, TimeUnit}

/**
 * @author Dmitry Openkov
 */
object Simulation {

  def simulate(scenario: Scenario): UIO[Unit] =
    ZIO.scoped {
      for {
        scheduler <- SimulationScheduler.make(parallelismWindow = 30, endSimulationTime = Double.PositiveInfinity)
        fillingStationHandler <- FillingStationHandler.make(scenario, scheduler)
        vehicleHandler = new MethodVehicleHandler(scenario, scheduler, fillingStationHandler)
//        initialEvents <- vehicleHandler.initialEvents(scenario)
//        _ <- ZIO.foreach(initialEvents)(event => scheduler.schedule(event))
        _ <- vehicleHandler.scheduleInitialEvents(scenario)
        start <- zio.Clock.currentTime(TimeUnit.SECONDS)
        _ <- scheduler.startScheduling()
        end <- zio.Clock.currentTime(TimeUnit.SECONDS)
        _ <- Console.printLine(end - start).orDie
      } yield ()

    }
}
