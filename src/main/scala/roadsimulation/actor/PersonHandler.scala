package roadsimulation.actor


import roadsimulation.model.{Id, Person, PositionKey, Scenario, TripPlan, Vehicle}
import roadsimulation.model.event.*
import roadsimulation.actor.RoadEventType.*
import roadsimulation.simulation.SimulationScheduler
import roadsimulation.simulation.SimulationScheduler.{Continuation, EventReference, Interrupted, OnTime, SimEvent}
import zio.{Hub, UIO, ZIO}

import java.util.concurrent.ConcurrentSkipListMap
import scala.collection.concurrent.TrieMap

/**
 * @author Dmitry Openkov
 */
class PersonHandler(
  scenario: Scenario,
  vehicleHandler: VehicleHandler,
  personsOnRoad: ConcurrentSkipListMap[PositionKey[Person], (Person, EventReference[Vehicle])],
  scheduler: SimulationScheduler,
  messageHub: Hub[StoredRoadEvent]
):
  private val personToVehicle = TrieMap.empty[Id[Person], Id[TripPlan]]

  def scheduleInitialEvents(scenario: Scenario): UIO[Unit] = for
    _ <- ZIO.foreachParDiscard(scenario.persons.values) { person =>
      scheduler.schedule(person.plan.startTime, onRoad(person))
    }
  yield ()

  def onRoad(person: Person): UIO[Unit] = {
    val currentPositionKey = PositionKey(person.positionInM, person.time, person.id)
    messageHub.publish(PersonGotToRoad(person)) *>
    scheduler.continueWhen[Vehicle](person.time + 3600, eventRef => for {
      _ <- ZIO.succeed(personsOnRoad.put(currentPositionKey, (person, eventRef)))
      vehicleMovements <- vehicleHandler.getAllApproachingVehicles(person.time + 20, person.positionInM)
      _ <- ZIO.foreachParDiscard(vehicleMovements) { (movement: Movement, interTime: Double) =>
        scheduler.cancel(movement.eventReference, person)
      }
    } yield ()
    ).flatMap{
      case Continuation(time, OnTime) =>
        zio.Console.printLine(s"No one has taken $person within an hour ($time).").orDie
          .as(personsOnRoad.remove(currentPositionKey))
          *> onRoad(person.copy(time = time))
      case Continuation(time, Interrupted(vehicle: Vehicle)) =>
        for {
          _ <- ZIO.succeed(personsOnRoad.remove(currentPositionKey))
          _ <- zio.Console.printLine(s"$vehicle has taken $person at $time.").orDie
        } yield ()
    }

  }

end PersonHandler


