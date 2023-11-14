package roadsimulation.actor


import roadsimulation.model.{Scenario, SpaceTime, TripPlan, Vehicle}
import roadsimulation.actor.RoadEventType.*
import roadsimulation.actor.VehicleHandlerImpl.calculatePositionToStartSearchingForFuelStation
import roadsimulation.simulation.SimulationScheduler
import roadsimulation.simulation.SimulationScheduler.{EventReference, SimEvent}
import zio.UIO
import zio.ZIO
/**
 * @author Dmitry Openkov
 */
class PersonHandler (
  scenario: Scenario,
  scheduler: SimulationScheduler
):
  def scheduleInitialEvents(scenario: Scenario): UIO[Unit] = for
    _ <- ZIO.foreachParDiscard(scenario.persons.values) { person =>
      scheduler.schedule(person.plan.startTime, ???)
    }
  yield ()

end PersonHandler
