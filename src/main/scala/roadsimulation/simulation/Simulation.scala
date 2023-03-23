package roadsimulation.simulation

import roadsimulation.actor.{RoadEventType, FillingStationHandler, VehicleHandlerImpl}
import roadsimulation.model.{Scenario, Vehicle}
import zio.*

import java.util.concurrent.PriorityBlockingQueue

/**
 * @author Dmitry Openkov
 */
object Simulation {

  def simulate(scenario: Scenario): UIO[Unit] =
    ZIO.scoped {
      for {
        scheduler <- SimulationScheduler.make(30, Double.PositiveInfinity)
        fillingStationHandler <- FillingStationHandler.make(scenario, scheduler)
        vehicleHandler = new VehicleHandlerImpl(scenario, scheduler, fillingStationHandler)
        initialEvents <- vehicleHandler.initialEvents(scenario)
        _ <- ZIO.foreach(initialEvents)(event => scheduler.schedule(event))
        _ <- scheduler.startScheduling()
      } yield ()

    }
}
