package roadsimulation.actor

import roadsimulation.model.{Scenario, SpaceTime, TripPlan, Vehicle}
import roadsimulation.actor.RoadEventType.*
import roadsimulation.simulation.SimulationScheduler
import roadsimulation.simulation.SimulationScheduler.{NoHandler, SimEvent}
import zio.UIO
import zio.ZIO

/**
 * @author Dmitry Openkov
 */
trait VehicleHandler

class VehicleHandlerImpl(
  scenario: Scenario,
  scheduler: SimulationScheduler[RoadEventType],
  fillingStationHandler: FillingStationHandler
) extends VehicleHandler:
  def initialEvents(scenario: Scenario): UIO[IndexedSeq[SimEvent[VehicleContinueTraveling]]] = ZIO.succeed(
    scenario.tripPlans.values.map { plan =>
      SimEvent(
        plan.startTime,
        VehicleContinueTraveling(Vehicle(plan.id, plan.vehicleType, plan.initialFuelLevelInJoule, positionInM = 0.0), entersRoad = true)
      )(handleContinueTraveling)
    }.toIndexedSeq
  )

  private def handleContinueTraveling(event: SimEvent[VehicleContinueTraveling]): UIO[Unit] =
    val vehicle = event.eventType.vehicle
    val currentPosition = vehicle.positionInM
    if (currentPosition >= scenario.roadLengthInM)
      // we reached the destination, end up here
      return ZIO.unit
    val nextPosition = calculatePositionToStartSearchingForFuelStation(vehicle, scenario.tripPlans(vehicle.id).startSearchingForFillingStationThresholdInM)
    if (nextPosition <= currentPosition)
      fillingStationHandler.findNearestStationAfter(currentPosition) match
        case Some(station) =>
          goToPositionWithObject(Some(station),
            station.fillingStation.positionInM,
            event.time,
            vehicle,
            scenario.speedLimitInMPerS)
        case None =>
          goToPositionWithObject(None, scenario.roadLengthInM, event.time,
            vehicle,
            scenario.speedLimitInMPerS)
    else
      goToPositionWithObject(None, math.min(nextPosition, scenario.roadLengthInM), event.time, vehicle, scenario.speedLimitInMPerS).unit
          
  private def handleVehicleAtPosition(event: SimEvent[VehicleAtPosition[FillingStationObject]]): UIO[Unit] = {
    event.eventType.possibleObject match
      case Some(fillingStationObject) =>
        val vehicle = event.eventType.vehicle
        for {
          exit <- fillingStationObject.enter(vehicle, event.time)
          _ <- handleContinueTraveling(SimEvent(exit.time, VehicleContinueTraveling(exit.vehicle, true)))
        } yield ()
      case None =>
        val vehicle = event.eventType.vehicle
        handleContinueTraveling(SimEvent(event.time, VehicleContinueTraveling(vehicle, false)))
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
        RunOutOfGas(vehicle.drive(vehicle.remainingRange)))
    else
      SimEvent(currentTime + distanceToTravel / averageSpeedInMPerS,
        VehicleAtPosition(vehicle.drive(distanceToTravel), obj))(handleVehicleAtPosition)
    scheduler.schedule(nextEvent)


  private def calculatePositionToStartSearchingForFuelStation(
    vehicle: Vehicle,
    startSearchingForFillingStationThresholdInM: Double,
  ): Double =
    if (vehicle.remainingRange <= startSearchingForFillingStationThresholdInM)
      vehicle.positionInM
    else
      val travelDistance = vehicle.remainingRange - startSearchingForFillingStationThresholdInM
      vehicle.positionInM + travelDistance



