package roadsimulation.actor

import roadsimulation.actor.RoadEventType.{RunOutOfGas, VehicleAtPosition, VehicleContinueTraveling}
import roadsimulation.actor.VehicleHandlerImpl.calculatePositionToStartSearchingForFuelStation
import roadsimulation.model.{Id, Person, Scenario, TripPlan, Vehicle}
import roadsimulation.simulation.SimulationScheduler
import roadsimulation.simulation.SimulationScheduler.{Interrupted, OnTime}
import roadsimulation.simulation.SimulationScheduler.{Continuation, ContinuationStatus, SimEvent}
import zio.{Exit, UIO, ZIO}

import scala.collection.concurrent.TrieMap

/**
 * @author Dmitry Openkov
 */
class MethodVehicleHandler(
  scenario: Scenario,
  scheduler: SimulationScheduler,
  fillingStationHandler: FillingStationHandler
) extends VehicleHandler:

  private val vehicleSpatialIndex = new VehicleSpatialIndex

  def getAllApproachingVehicles(
    time: Double,
    position: Double
  ): UIO[Seq[(Movement, Double)]] = vehicleSpatialIndex.getAllApproachingVehicles(time, position)

  private val passengers = TrieMap.empty[Id[TripPlan], Seq[Id[Person]]]

  override def takePassenger(vehicleId: Id[TripPlan], personId: Id[Person]): UIO[Seq[Id[Person]]] = {
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
        passengers = Set.empty,
        personsOnRoad = Seq.empty,
        positionInM = 0.0,
        time = plan.startTime)
      scheduler.schedule(plan.startTime, vehicleEntersRoad(plan, vehicle))
    }
  } yield ()

  private def vehicleEntersRoad(plan: TripPlan, vehicle: Vehicle): UIO[Unit] = {
    if (vehicle.positionInM >= scenario.roadLengthInM)
    // we reached the destination, end up here
      return zio.Console.printLine(s"${vehicle.id} is finished").orDie //todo replace with the EndTravel event
    val nextPosition = calculatePositionToStartSearchingForFuelStation(vehicle,
      plan.startSearchingForFillingStationThresholdInM)
    if (nextPosition <= vehicle.positionInM)
      findFillingStation(plan, vehicle)
    else
      for {
        nextVehicle <- goToPosition(
          nextPosition,
          vehicle,
          scenario.speedLimitInMPerS,
          scenario.roadLengthInM,
        )
        _ <- ZIO.when(nextVehicle.positionInM >= nextPosition) {
          findFillingStation(plan, nextVehicle)
        }

      } yield ()

  }

  private def findFillingStation(
    plan: TripPlan,
    vehicle: Vehicle
  ) = {
    fillingStationHandler.findNearestStationAfter(vehicle.positionInM) match
      case Some(station) =>
        for {
          nextVehicle <- goToPosition(station.fillingStation.positionInM,
            vehicle,
            scenario.speedLimitInMPerS,
            scenario.roadLengthInM,
          )
          _ <- ZIO.when(!nextVehicle.isRunOutOfGas) {
            for {
              vehicleExited <- station.enter(nextVehicle, nextVehicle.time)
              _ <- vehicleEntersRoad(plan, vehicleExited)
            } yield ()
          }
        } yield ()

      case None =>
        for {
          nextVehicle <- goToPosition(
            scenario.roadLengthInM,
            vehicle,
            scenario.speedLimitInMPerS,
            scenario.roadLengthInM,
          )
        } yield ()

  }


  private def goToPosition(
    positionInM: Double,
    vehicle: Vehicle,
    speedLimitInMPerS: Double,
    roadLengthInM: Double,
  ): UIO[Vehicle] =
    val nextPosition = math.min(positionInM, scenario.roadLengthInM)
    val nextVehicle = vehicle.drive(nextPosition, speedLimitInMPerS)
    scheduler.continueWhen[Person](nextVehicle.time,
      eventReference => vehicleSpatialIndex.putVehicleChange(vehicle, nextVehicle, eventReference))
      .flatMap {
        case Continuation(time, OnTime) =>
          vehicleSpatialIndex.removeVehicleMovement(vehicle.id) *>
            ZIO.when(nextVehicle.isRunOutOfGas) {
              zio.Console.printLine(s"${nextVehicle} is finished with ROOG").orDie
            } *> ZIO.when(nextVehicle.positionInM >= roadLengthInM) {
            zio.Console.printLine(s"${vehicle} is finished").orDie
          }.as(nextVehicle)
        case Continuation(time, Interrupted(person: Person)) =>
          val actualVehicle = vehicle
            .driveUntilTime(time, speedLimitInMPerS)
            .copy(personsOnRoad = person +: vehicle.personsOnRoad)
          vehicleSpatialIndex.removeVehicleMovement(vehicle.id) *>
            zio.Console.printLine(s"$actualVehicle interrupted by $person").orDie *>
            goToPosition(person.positionInM, actualVehicle, speedLimitInMPerS, roadLengthInM)
      }


end MethodVehicleHandler



