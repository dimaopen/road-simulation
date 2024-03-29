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
  id: Id[Vehicle],
  vehicleType: VehicleType,
  initialFuelLevelInJoule: Double,
  startTime: Double,
  startSearchingForFillingStationThresholdInM: Double,
)

case class Vehicle(
  id: Id[Vehicle],
  vehicleType: VehicleType,
  fuelLevelInJoule: Double,
  passengers: Seq[Person],
  positionInM: Double,
  time: Double
) {
  val remainingRange: Double = fuelLevelInJoule / vehicleType.fuelConsumptionInJoulePerMeter

  def isRunOutOfGas: Boolean = remainingRange <= 0

  def fuelLevelAfterTraveling(distanceInM: Double): Double =
    fuelLevelInJoule - distanceInM * vehicleType.fuelConsumptionInJoulePerMeter

  def drive(toPositionInM: Double, speedLimitInMPerS: Double): Vehicle =
    val travelDistance = math.min(toPositionInM - positionInM, remainingRange)
    val speed: Double = vehicleType.maxVelocity.fold(speedLimitInMPerS)(math.min(speedLimitInMPerS, _))
    val nextTime = time + travelDistance / speed
    this.copy(positionInM = positionInM + travelDistance,
      fuelLevelInJoule = fuelLevelAfterTraveling(travelDistance),
      time = nextTime)

  def driveUntilTime(toTime: Double, speedLimitInMPerS: Double): Vehicle =
    val speed: Double = vehicleType.maxVelocity.fold(speedLimitInMPerS)(math.min(speedLimitInMPerS, _))
    val toPositionInM = positionInM + (toTime - time) * speed
    drive(toPositionInM, speedLimitInMPerS)

  override def toString: String = s"%s(%s; %.2fm, %.2fs; range = %.0f; %s=%,.0f, ${passengers.map(_.id).mkString("|")})".format(vehicleType.id,
    id,
    positionInM,
    time,
    remainingRange,
    vehicleType.fuelType,
    fuelLevelInJoule)
}
