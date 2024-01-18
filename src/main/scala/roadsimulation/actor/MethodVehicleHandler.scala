package roadsimulation.actor

import roadsimulation.model.*
import roadsimulation.actor.RoadEventType.{RunOutOfGas, VehicleAtPosition, VehicleContinueTraveling}
import roadsimulation.actor.VehicleHandlerImpl.calculatePositionToStartSearchingForFuelStation
import roadsimulation.simulation.SimulationScheduler
import roadsimulation.simulation.SimulationScheduler.{Continuation, ContinuationStatus, EventReference, Interrupted, OnTime, SimEvent}
import zio.{Exit, Hub, UIO, ZIO}

import java.util.concurrent.ConcurrentSkipListMap
import scala.collection.concurrent.TrieMap

/**
 * @author Dmitry Openkov
 */
class MethodVehicleHandler(
  scenario: Scenario,
  scheduler: SimulationScheduler,
  fillingStationHandler: FillingStationHandler,
  personsOnRoad: ConcurrentSkipListMap[PositionKey[Person], (Person, EventReference[Vehicle])],
  messageHub: Hub[StoredRoadEvent],
) extends VehicleHandler:

  private val vehicleSpatialIndex = new VehicleSpatialIndex

  def getAllApproachingVehicles(
    time: Double,
    position: Double
  ): UIO[Seq[(Movement, Double)]] = vehicleSpatialIndex.getAllApproachingVehicles(time, position)

  private val passengers = TrieMap.empty[Id[Vehicle], Seq[Id[Person]]]

  override def takePassenger(vehicleId: Id[Vehicle], personId: Id[Person]): UIO[Seq[Id[Person]]] = {
    ZIO.succeed(passengers.updateWith(vehicleId) {
      case Some(passengerList) if passengerList.contains(personId) => throw new IllegalArgumentException(
        s"Vehicle $vehicleId has passengers $passengerList. It cannot add $personId again."
      )
      case Some(passengerList) => Some(passengerList :+ personId)
      case None => Some(Seq(personId))
    }.get)
  }

  def scheduleInitialEvents(scenario: Scenario): UIO[Unit] = for {
    _ <- ZIO.foreachParDiscard(scenario.tripPlans.values) { plan =>
      val vehicle = Vehicle(plan.id,
        plan.vehicleType,
        plan.initialFuelLevelInJoule,
        passengers = Seq.empty,
        positionInM = 0.0,
        time = plan.startTime)
      scheduler.schedule(plan.startTime, travelCycle(plan, vehicle))
    }
  } yield ()

  private def travelCycle(plan: TripPlan, vehicle: Vehicle): UIO[Unit] = {
    val searchPosition = calculatePositionToStartSearchingForFuelStation(vehicle,
      plan.startSearchingForFillingStationThresholdInM)
    val resultVehicle = if (searchPosition <= vehicle.positionInM)
      findFillingStationAndFillVehicle(plan, vehicle)
    else
      for {
        nextVehicle <- goToPosition(searchPosition, vehicle, scenario.speedLimitInMPerS)
        filledVehicle <- ZIO.when(nextVehicle.positionInM >=~ searchPosition) {
          findFillingStationAndFillVehicle(plan, nextVehicle)
        }
      } yield filledVehicle.getOrElse(nextVehicle)
    resultVehicle.flatMap {
      case vehicle if vehicle.positionInM >=~ scenario.roadLengthInM =>
        // we reached the destination, end up here
        messageHub.publish(VehicleReachedDestination(vehicle)) as ()
      case vehicle if vehicle.isRunOutOfGas =>
        messageHub.publish(VehicleRunOutOfGas(vehicle)) as ()
      case vehicle =>
        //continue travelling
        travelCycle(plan, vehicle)
    }

  }

  private def findFillingStationAndFillVehicle(
    plan: TripPlan,
    vehicle: Vehicle
  ): UIO[Vehicle] = {
    fillingStationHandler.findNearestStationAfter(vehicle.positionInM) match
      case Some(station) =>
        for {
          nextVehicle <- goToPosition(station.fillingStation.positionInM, vehicle, scenario.speedLimitInMPerS)
          filledVehicle <- ZIO.when(nextVehicle.positionInM >=~ station.fillingStation.positionInM) {
            station.enter(nextVehicle, nextVehicle.time)
          }
        } yield filledVehicle.getOrElse(nextVehicle)

      case None =>
        goToPosition(scenario.roadLengthInM, vehicle, scenario.speedLimitInMPerS)
  }


  private def goToPosition(positionInM: Double, vehicle: Vehicle, speedLimitInMPerS: Double): UIO[Vehicle] =
    val targetPosition = math.min(positionInM, scenario.roadLengthInM)
    val nextPerson = Option(personsOnRoad.ceilingEntry(PositionKey.minKeyForPosition(vehicle.positionInM)))
      .map(_.getValue)
      .filter { case (person, _) => person.positionInM < targetPosition }
    val nextPosition = nextPerson.fold(targetPosition) { case (person, _) => person.positionInM }
    val nextVehicle = vehicle.drive(nextPosition, speedLimitInMPerS)
    scheduler.continueWhen[Person](nextVehicle.time,
      eventReference => vehicleSpatialIndex.putVehicleChange(vehicle, nextVehicle, eventReference))
      .flatMap {
        case Continuation(time, OnTime) =>
          for {
            _ <- vehicleSpatialIndex.removeVehicleMovement(vehicle.id)
            finalVehicle <- ZIO.when(nextPerson.isDefined) {
              val (person, eventRef) = nextPerson.get
              for {
                veh <- ZIO.whenZIO(scheduler.cancel(eventRef, nextVehicle)) {
                  val boardedVehicle = nextVehicle.copy(passengers = nextVehicle.passengers :+ person)
                  messageHub.publish(PersonBoardedVehicle(person, boardedVehicle)) as boardedVehicle
                }
                finVeh <- goToPosition(targetPosition, veh.getOrElse(nextVehicle), speedLimitInMPerS)
              } yield finVeh
            }
          } yield finalVehicle.getOrElse(nextVehicle)
        case Continuation(time, Interrupted(person: Person)) =>
          for {
            _ <- vehicleSpatialIndex.removeVehicleMovement(vehicle.id)
            actualVehicle = vehicle.driveUntilTime(time, speedLimitInMPerS)
            _ <- zio.Console.printLine(s"$actualVehicle interrupted by $person").orDie
          } yield actualVehicle
      }

end MethodVehicleHandler



