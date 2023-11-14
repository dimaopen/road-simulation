package roadsimulation.actor

import roadsimulation.model.{Id, Scenario, SpaceTime, TripPlan, Vehicle}
import roadsimulation.actor.RoadEventType.*
import roadsimulation.actor.VehicleHandlerImpl.calculatePositionToStartSearchingForFuelStation
import roadsimulation.simulation.SimulationScheduler
import roadsimulation.simulation.SimulationScheduler.{EventReference, SimEvent}
import zio.UIO
import zio.ZIO

import java.util.concurrent.ConcurrentHashMap

/**
 * @author Dmitry Openkov
 */
trait VehicleHandler

class VehicleHandlerImpl(
  scenario: Scenario,
  scheduler: SimulationScheduler,
  fillingStationHandler: FillingStationHandler
) extends VehicleHandler:

  private val vehicleSpatialIndex = new VehicleSpatialIndex

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
      VehicleContinueTraveling(Vehicle(plan.id, plan.vehicleType, plan.initialFuelLevelInJoule, passengers = Set.empty, positionInM = 0.0, time = plan.startTime),
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

  private def handleVehicleAtPosition(
    time: Double,
    vehicleAtPosition: VehicleAtPosition[FillingStationObject]
  ): UIO[Unit] = {
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
    speedLimitInMPerS: Double,
  ): UIO[Unit] =
    val nextVehicle = vehicle.drive(positionInM, speedLimitInMPerS)
    val nextEvent = if (nextVehicle.remainingRange <= 0) {
      SimEvent(nextVehicle.time,
        RunOutOfGas(nextVehicle))((time, runOutOfGas) =>
        zio.Console.printLine(s"${runOutOfGas.vehicle.id} is finished with runOutOfGas").orDie)
    }
    else
      SimEvent(nextVehicle.time, VehicleAtPosition(nextVehicle, obj))(handleVehicleAtPosition)
    for {
      _ <- scheduler.schedule(nextEvent)
      _ <- vehicleSpatialIndex.putVehicleChange(vehicle, currentTime, nextEvent.eventType.vehicle, nextEvent.time)
    } yield ()


object VehicleHandlerImpl {
  def calculatePositionToStartSearchingForFuelStation(
    vehicle: Vehicle,
    startSearchingForFillingStationThresholdInM: Double,
  ): Double =
    if (vehicle.remainingRange <= startSearchingForFillingStationThresholdInM)
      vehicle.positionInM
    else
      val travelDistance = vehicle.remainingRange - startSearchingForFillingStationThresholdInM
      vehicle.positionInM + travelDistance
}

case class Movement(t1: Double, p1: Double, t2: Double, p2: Double):
  def intersectionTime(t: Double, p: Double): Double =
    if (p < p1 || p2 < p || t < t1 || t > t2)
      Double.NaN
    else {
      val result = (p - p1) * (t2 - t1) / (p2 - p1) + t1
      if result >= t then result else Double.NaN
    }

end Movement

class VehicleSpatialIndex:
  private val vehicleToPosition = new ConcurrentHashMap[Id[TripPlan], Movement]()

  def putVehicleChange(initialState: Vehicle, initialTime: Double, finalState: Vehicle, finalTime: Double): UIO[Unit] =
    ZIO.succeed(vehicleToPosition.put(initialState.id,
      Movement(initialTime, initialState.positionInM, finalTime, finalState.positionInM)))

  def getAllApproachingVehicles(time: Double, position: Double): UIO[Seq[Id[TripPlan]]] = {
    ZIO.succeed(vehicleToPosition.reduce(32, (id, movement) => if (movement.intersectionTime(time, position).isFinite) Seq(id) else null, _ ++ _))
  }

end VehicleSpatialIndex

