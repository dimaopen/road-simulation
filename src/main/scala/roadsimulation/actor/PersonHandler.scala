package roadsimulation.actor


import roadsimulation.model.{Id, Person, Scenario, SpaceTime, TripPlan, Vehicle}
import roadsimulation.actor.RoadEventType.*
import roadsimulation.simulation.SimulationScheduler
import roadsimulation.simulation.SimulationScheduler.{EventReference, SimEvent}
import zio.UIO
import zio.ZIO

import scala.collection.concurrent.TrieMap
/**
 * @author Dmitry Openkov
 */
class PersonHandler (
  scenario: Scenario,
  vehicleHandler: VehicleHandler,
  scheduler: SimulationScheduler
):
  private val personToVehicle = TrieMap.empty[Id[Person], Id[TripPlan]]
  def scheduleInitialEvents(scenario: Scenario): UIO[Unit] = for
    _ <- ZIO.foreachParDiscard(scenario.persons.values) { person =>
      scheduler.schedule(person.plan.startTime, onRoad(person))
    }
  yield ()

  def onRoad(person: Person): UIO[Unit] = {
    for {
      vehicleMovements <- vehicleHandler.getAllApproachingVehicles(person.time + 20, person.positionInM)
      
      _ <- ZIO.foreachParDiscard(vehicleMovements) { (movement: Movement, interTime: Double) =>
        scheduler.cancel(movement.eventReference, person)
      }
    } yield ()

  }

end PersonHandler
