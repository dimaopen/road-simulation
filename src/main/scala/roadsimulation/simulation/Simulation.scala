package roadsimulation.simulation

import roadsimulation.actor.{RoadEventType, FillingStationHandler, VehicleHandlerImpl}
import roadsimulation.model.{Scenario, Vehicle}
import zio.*

import java.util.concurrent.PriorityBlockingQueue

/**
 * @author Dmitry Openkov
 */
object Simulation {

  def simulate(scenario: Scenario): IO[AnyRef, Unit] =
    for {
      scheduler <- SimulationScheduler.make[RoadEventType]()
      fillingStationHandler <- FillingStationHandler.make(scenario, scheduler)
      vehicleHandler = new VehicleHandlerImpl(scenario, scheduler, fillingStationHandler)
      initialEvents <- vehicleHandler.initialEvents(scenario)
      _ <- ZIO.foreach(initialEvents)(event => scheduler.schedule(event))
      _ <- scheduler.startScheduling()
    } yield ()


}
