package roadsimulation.actor

import roadsimulation.model.{Id, Person, Scenario, TripPlan, Vehicle}
import roadsimulation.actor.RoadEventType.*
import roadsimulation.actor.VehicleHandlerImpl.calculatePositionToStartSearchingForFuelStation
import roadsimulation.simulation.SimulationScheduler
import roadsimulation.simulation.SimulationScheduler.{EventReference, SimEvent}
import zio.UIO
import zio.ZIO

import java.util.concurrent.ConcurrentHashMap
import scala.collection.concurrent.TrieMap

/**
 * @author Dmitry Openkov
 */
trait VehicleHandler:
  def getAllApproachingVehicles(time: Double, position: Double): UIO[Seq[(Movement, Double)]]

  def takePassenger(vehicleId: Id[TripPlan], personId: Id[Person]): UIO[Seq[Id[Person]]]

end VehicleHandler

class VehicleHandlerImpl(
  scenario: Scenario,
  scheduler: SimulationScheduler,
  fillingStationHandler: FillingStationHandler
) extends VehicleHandler:

  private val vehicleSpatialIndex = new VehicleSpatialIndex

  override def getAllApproachingVehicles(
    time: Double,
    position: Double
  ): UIO[Seq[(Movement, Double)]] = vehicleSpatialIndex.getAllApproachingVehicles(time, position)

  private val passengers = TrieMap.empty[Id[TripPlan], Seq[Id[Person]]]

  override def takePassenger(vehicleId: Id[TripPlan], personId: Id[Person]): UIO[Seq[Id[Person]]] = {
    ZIO.succeed(passengers.updateWith(vehicleId) {
      case Some(passengerList) if passengerList.contains(personId) => throw new IllegalArgumentException()
      case Some(passengerList) => Some(passengerList :+ personId)
      case None => Some(Seq(personId))
    }.get)
  }

  def initialEvents(scenario: Scenario): UIO[IndexedSeq[SimEvent[VehicleContinueTraveling, Nothing]]] = ZIO.succeed(
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
      VehicleContinueTraveling(Vehicle(plan.id,
        plan.vehicleType,
        plan.initialFuelLevelInJoule,
        passengers = Seq.empty,
        positionInM = 0.0,
        time = plan.startTime),
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
          exitVehicle <- fillingStationObject.enter(vehicle, time)
          _ <- handleContinueTraveling(exitVehicle.time, VehicleContinueTraveling(exitVehicle, entersRoad = true))
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
      eventReference <- scheduler.schedule(nextEvent)
      _ <- vehicleSpatialIndex.putVehicleChange(vehicle, nextEvent.eventType.vehicle, eventReference)
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

case class Movement(vehicle1: Vehicle, vehicle2: Vehicle, eventReference: EventReference[Person]):
  def intersectionTime(t: Double, p: Double): Double =
    if (p < vehicle1.positionInM || vehicle2.positionInM < p || t < vehicle1.time || t > vehicle2.time)
      Double.NaN
    else {
      val result = (p - vehicle1.positionInM) * (vehicle2.time - vehicle1.time) /
        (vehicle2.positionInM - vehicle1.positionInM) + vehicle1.time
      if result >= t then result else Double.NaN
    }

end Movement

class VehicleSpatialIndex:
  // todo: we should use a navigable map here to reduce the number of vehicles being considered
  private val vehicleToPosition = new ConcurrentHashMap[Id[TripPlan], Movement]()

  def putVehicleChange(initialState: Vehicle, finalState: Vehicle, eventReference: EventReference[Person]): UIO[Unit] =
    ZIO.succeed(vehicleToPosition.put(initialState.id,
      Movement(initialState, finalState, eventReference)))

  def removeVehicleMovement(vehicleId: Id[TripPlan]): UIO[Unit] =
    ZIO.succeed(vehicleToPosition.remove(vehicleId))

  def getAllApproachingVehicles(time: Double, position: Double): UIO[Seq[(Movement, Double)]] = {
    ZIO.succeed {
      val result: Seq[(Movement, Double)] = vehicleToPosition.reduceValues(32,
        movement => {
          val intersectionTime = movement.intersectionTime(time, position)
          if (intersectionTime.isFinite) Seq((movement, intersectionTime)) else null
        },
        _ ++ _)
      if (result == null)
        Seq.empty
      else
        result
    }
  }

end VehicleSpatialIndex

