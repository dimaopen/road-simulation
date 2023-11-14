package roadsimulation.actor

import roadsimulation.actor.RoadEventType.{RunOutOfGas, VehicleAtPosition, VehicleContinueTraveling}
import roadsimulation.actor.VehicleHandlerImpl.calculatePositionToStartSearchingForFuelStation
import roadsimulation.model.{Scenario, TripPlan, Vehicle}
import roadsimulation.simulation.SimulationScheduler
import roadsimulation.simulation.SimulationScheduler.{Interrupted, OnTime}
import roadsimulation.simulation.SimulationScheduler.{Continuation, ContinuationStatus, SimEvent}
import zio.{Exit, UIO, ZIO}

/**
 * @author Dmitry Openkov
 */
class MethodVehicleHandler(
  scenario: Scenario,
  scheduler: SimulationScheduler,
  fillingStationHandler: FillingStationHandler
) extends VehicleHandler:

  private val vehicleSpatialIndex = new VehicleSpatialIndex

  def scheduleInitialEvents(scenario: Scenario): UIO[Unit] = for {
    _ <- ZIO.foreachParDiscard(scenario.tripPlans.values) { plan =>
      val vehicle = Vehicle(plan.id,
        plan.vehicleType,
        plan.initialFuelLevelInJoule,
        passengers = Set.empty,
        positionInM = 0.0,
        time = plan.startTime)
      scheduler.schedule(plan.startTime, vehicleEntersRoad(plan, vehicle, plan.startTime))
    }
  } yield ()

  private def vehicleEntersRoad(plan: TripPlan, vehicle: Vehicle, time: Double): UIO[Unit] = {
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
          time,
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
            vehicle.time,
            vehicle,
            scenario.speedLimitInMPerS,
            scenario.roadLengthInM,
          )
          _ <- ZIO.when(!nextVehicle.isRunOutOfGas) {
            for {
              exitFromFillingStation <- station.enter(nextVehicle, nextVehicle.time)
              _ <- vehicleEntersRoad(plan, exitFromFillingStation.vehicle, exitFromFillingStation.time)
            } yield ()
          }
        } yield ()

      case None =>
        for {
          nextVehicle <- goToPosition(
            scenario.roadLengthInM,
            vehicle.time,
            vehicle,
            scenario.speedLimitInMPerS,
            scenario.roadLengthInM,
          )
        } yield ()

  }


  private def goToPosition(
    positionInM: Double,
    currentTime: Double,
    vehicle: Vehicle,
    speedLimitInMPerS: Double,
    roadLengthInM: Double,
  ): UIO[Vehicle] =
    val nextPosition = math.min(positionInM, scenario.roadLengthInM)
    val nextVehicle = vehicle.drive(nextPosition, speedLimitInMPerS)
    scheduler.continueWhen(nextVehicle.time)
      .flatMap {
        case Continuation(time, OnTime) =>
          ZIO.when(nextVehicle.isRunOutOfGas) {
            zio.Console.printLine(s"${nextVehicle} is finished with ROOG").orDie
          } *> ZIO.when(nextVehicle.positionInM >= roadLengthInM) {
            zio.Console.printLine(s"${vehicle} is finished").orDie
          }.as(nextVehicle)
        case Continuation(time, Interrupted(person)) =>
          ZIO.succeed(nextVehicle)
      }


end MethodVehicleHandler



