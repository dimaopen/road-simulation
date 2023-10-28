package roadsimulation.actor

import roadsimulation.actor.RoadEventType.{RunOutOfGas, VehicleAtPosition, VehicleContinueTraveling}
import roadsimulation.actor.VehicleHandlerImpl.calculatePositionToStartSearchingForFuelStation
import roadsimulation.model.{Scenario, TripPlan, Vehicle}
import roadsimulation.simulation.SimulationScheduler
import roadsimulation.simulation.SimulationScheduler.{Continuation, SimEvent}
import zio.{UIO, ZIO}

/**
 * @author Dmitry Openkov
 */
class MethodVehicleHandler(
  scenario: Scenario,
  scheduler: SimulationScheduler,
  fillingStationHandler: FillingStationHandler
) extends VehicleHandler {

  def scheduleInitialEvents(scenario: Scenario): UIO[Unit] = for {
    _ <- ZIO.foreachParDiscard(scenario.tripPlans.values) { plan =>
      val vehicle = Vehicle(plan.id, plan.vehicleType, plan.initialFuelLevelInJoule, positionInM = 0.0)
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
      findFillingStation(plan, vehicle, time)
    else
      for {
        result <- goToPosition(
          math.min(nextPosition, scenario.roadLengthInM),
          time,
          vehicle,
          scenario.speedLimitInMPerS)
        _ <- result match
          case (nextTime, Right(vehicle)) =>
            findFillingStation(plan, vehicle, nextTime)
          case (time, Left(runOutOfGas)) =>
            handleRunOutOfGas(time, runOutOfGas)
      } yield ()

  }

  private def findFillingStation(
    plan: TripPlan,
    vehicle: Vehicle,
    time: Double
  ) = {
    fillingStationHandler.findNearestStationAfter(vehicle.positionInM) match
      case Some(station) =>
        for {
          result <- goToPosition(station.fillingStation.positionInM,
            time,
            vehicle,
            scenario.speedLimitInMPerS)
          _ <- result match
            case (arrivedAtStationTime, Right(vehicle)) =>
              for {
                exitFromFillingStation <- station.enter(vehicle, arrivedAtStationTime)
                _ <- vehicleEntersRoad(plan, exitFromFillingStation.vehicle, exitFromFillingStation.time)
              } yield ()
            case (time, Left(runOutOfGas)) =>
              handleRunOutOfGas(time, runOutOfGas)
        } yield ()

      case None =>
        for {
          result <- goToPosition(scenario.roadLengthInM, time,
            vehicle,
            scenario.speedLimitInMPerS)
          _ <- result match
            case (finishTime, Right(vehicle)) =>
              zio.Console.printLine(s"${vehicle.id} is finished at time $finishTime").orDie
            case (time, Left(runOutOfGas)) =>
              handleRunOutOfGas(time, runOutOfGas)
        } yield ()

  }

  private def handleRunOutOfGas(time: Double, runOutOfGas: RunOutOfGas) =
    zio.Console.printLine(s"${runOutOfGas.vehicle.id} is finished with ROOG at $time").orDie


  private def goToPosition(
    positionInM: Double,
    currentTime: Double,
    vehicle: Vehicle,
    averageSpeedInMPerS: Double,
  ): UIO[(Double, Either[RunOutOfGas, Vehicle])] =
    val distanceToTravel = positionInM - vehicle.positionInM
    if (vehicle.remainingRange < distanceToTravel) {
      val nextTime = currentTime + vehicle.remainingRange / averageSpeedInMPerS
      scheduler.continueWhen(nextTime).as(nextTime -> Left(RunOutOfGas(vehicle.drive(vehicle.remainingRange))))
    } else {
      val nextTime = currentTime + distanceToTravel / averageSpeedInMPerS
      scheduler.continueWhen(nextTime).as(nextTime -> Right(vehicle.drive(distanceToTravel)))
    }

}
