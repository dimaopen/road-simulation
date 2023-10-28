package roadsimulation.actor

import roadsimulation.model.{Scenario, SpaceTime, TripPlan, Vehicle}
import roadsimulation.actor.RoadEventType.*
import roadsimulation.actor.VehicleHandlerImpl.calculatePositionToStartSearchingForFuelStation
import roadsimulation.simulation.SimulationScheduler
import roadsimulation.simulation.SimulationScheduler.{SimEvent}
import zio.UIO
import zio.ZIO

/**
 * @author Dmitry Openkov
 */
trait VehicleHandler

class VehicleHandlerImpl(
  scenario: Scenario,
  scheduler: SimulationScheduler,
  fillingStationHandler: FillingStationHandler
) extends VehicleHandler:
  def initialEvents(scenario: Scenario): UIO[IndexedSeq[SimEvent[VehicleContinueTraveling, Unit]]] = ZIO.succeed(
    scenario.tripPlans.values.map(initialEventFromPlan).toIndexedSeq
  )

  def scheduleInitialEvents(scenario: Scenario): UIO[Unit] = for {
    _ <- ZIO.foreachParDiscard(scenario.tripPlans.values) { plan =>
      val event = initialEventFromPlan(plan)
      scheduler.schedule(event)
    }
  } yield ()

  private def initialEventFromPlan(plan: TripPlan) = {
    SimEvent(
      plan.startTime,
      VehicleContinueTraveling(Vehicle(plan.id, plan.vehicleType, plan.initialFuelLevelInJoule, positionInM = 0.0),
        entersRoad = true)
    )(handleContinueTraveling)
  }

  private def handleContinueTraveling(time: Double, continueTraveling: VehicleContinueTraveling): UIO[Unit] =
    val vehicle = continueTraveling.vehicle
    val currentPosition = vehicle.positionInM
    if (currentPosition >= scenario.roadLengthInM)
    // we reached the destination, end up here
      return zio.Console.printLine(s"${vehicle.id} is finished").orDie //todo replace with the EndTravel event
    val nextPosition = calculatePositionToStartSearchingForFuelStation(vehicle,
      scenario.tripPlans(vehicle.id).startSearchingForFillingStationThresholdInM)
    if (nextPosition <= currentPosition)
      fillingStationHandler.findNearestStationAfter(currentPosition) match
        case Some(station) =>
          goToPositionWithObject(Some(station),
            station.fillingStation.positionInM,
            time,
            vehicle,
            scenario.speedLimitInMPerS)
        case None =>
          goToPositionWithObject(None, scenario.roadLengthInM, time,
            vehicle,
            scenario.speedLimitInMPerS)
    else
      goToPositionWithObject(None,
        math.min(nextPosition, scenario.roadLengthInM),
        time,
        vehicle,
        scenario.speedLimitInMPerS).unit

  private def handleVehicleAtPosition(time: Double, vehicleAtPosition: VehicleAtPosition[FillingStationObject]): UIO[Unit] = {
    vehicleAtPosition.possibleObject match
      case Some(fillingStationObject) =>
        val vehicle = vehicleAtPosition.vehicle
        for {
          exit <- fillingStationObject.enter(vehicle, time)
          _ <- handleContinueTraveling(exit.time, VehicleContinueTraveling(exit.vehicle, entersRoad = true))
        } yield ()
      case None =>
        val vehicle = vehicleAtPosition.vehicle
        handleContinueTraveling(time, VehicleContinueTraveling(vehicle, entersRoad = false))
  }

  private def goToPositionWithObject(
    obj: Option[FillingStationObject],
    positionInM: Double,
    currentTime: Double,
    vehicle: Vehicle,
    averageSpeedInMPerS: Double,
  ): UIO[Unit] =
    val distanceToTravel = positionInM - vehicle.positionInM
    val nextEvent = if (vehicle.remainingRange < distanceToTravel)
      SimEvent(currentTime + vehicle.remainingRange / averageSpeedInMPerS,
        RunOutOfGas(vehicle.drive(vehicle.remainingRange)))((time, runOutOfGas) =>
        zio.Console.printLine(s"${runOutOfGas.vehicle.id} is finished with runOutOfGas").orDie)
    else
      SimEvent(currentTime + distanceToTravel / averageSpeedInMPerS,
        VehicleAtPosition(vehicle.drive(distanceToTravel), obj))(handleVehicleAtPosition)
    scheduler.schedule(nextEvent)

object VehicleHandlerImpl {
  def calculatePositionToStartSearchingForFuelStation(
    vehicle: Vehicle,
    startSearchingForFillingStationThresholdInM: Double,
  ): Double =
    if (vehicle.remainingRange <= startSearchingForFillingStationThresholdInM)
      vehicle.positionInM
    else
      val travelDistance = vehicle.remainingRange - startSearchingForFillingStationThresholdInM
      vehicle.positionInM + travelDistance + 0.001
}



