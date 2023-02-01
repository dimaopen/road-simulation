package roadsimulation.model

/**
 * @author Dmitry Openkov
 */
enum FuelType(energyInPriceUnitInJoules: Double) {
  case Gasoline extends FuelType(44000000)
  case Diesel extends FuelType(43120000)
  case Electricity extends FuelType(3600000)
  case Propane extends FuelType(45030000)
  case Methane extends FuelType(50300000)
}

case class VehicleType(
                        id: Id[VehicleType],
                        seatingCapacity: Int,
                        standingRoomCapacity: Int,
                        lengthInMeter: Double,
                        fuelType: FuelType,
                        fuelConsumptionInJoulePerMeter: Double,
                        fuelCapacityInJoule: Double,
                        automationLevel: Int = 1,
                        maxVelocity: Option[Double] = None,
                        chargingCapability: Option[Double] = None,
                        payloadCapacityInKg: Option[Double] = None,
                      )

case class TripPlan(
                     id: Id[TripPlan],
                     vehicleType: VehicleType,
                     initialFuelLevelInJoule: Double,
                     startTime: Int,
                     startSearchingForFillingStationThresholdInM: Double,
                   )

case class Vehicle(
                    id: Id[TripPlan],
                    vehicleType: VehicleType,
                    fuelLevelInJoule: Double,
                    positionInM: Double,
                  ) {
  val remainingRange: Double = fuelLevelInJoule / vehicleType.fuelConsumptionInJoulePerMeter
  def fuelLevelAfterTraveling(distanceInM: Double): Double =
    fuelLevelInJoule - distanceInM * vehicleType.fuelConsumptionInJoulePerMeter
  def drive(distanceInM: Double): Vehicle =
    val travelDistance = Math.min(distanceInM, remainingRange)
    this.copy(positionInM = positionInM + travelDistance, fuelLevelInJoule = fuelLevelAfterTraveling(travelDistance))

  override def toString: String = "%s(%s; %,.2f; range = %,.0f; %s=%,.0f)".format(vehicleType.id.value, id, positionInM, remainingRange, vehicleType.fuelType, fuelLevelInJoule)
}

case class SpaceTime(positionInM: Double, time: Double)