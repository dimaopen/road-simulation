package roadsimulation.model

import roadsimulation.model.FuelType._
import roadsimulation.util.CsvReader
import zio.*

import java.nio.file.{Path, Paths}

/**
 * @author Dmitry Openkov
 */
case class Id[T](value: String)

case class Scenario(roadLengthInM: Double,
                    speedLimitInKmPerHour: Double,
                    endTime: Int,
                    fillingStations: Map[Id[FillingStation], FillingStation],
                    tripPlans: Map[Id[TripPlan], TripPlan]) {
  val speedLimitInMPerS: Double = speedLimitInKmPerHour / 3.6
}

object Scenario {
  def loadScenario(): Task[Scenario] = {
    for {
      fillingStations <- loadFillingStations(Paths.get("input/msk-spb/filling-stations.csv"))
      vehicleTypes <- loadVehicleTypes(Paths.get("input/vehicle-types.csv"))
      desiredVehicleTypeIds = Set(
        "Car",
        "DieselCar",
        "RH_Car",
        "BUS-DEFAULT",
      ).map(Id.apply[VehicleType])
      desiredTypes = vehicleTypes.filter(vehicleType => desiredVehicleTypeIds.contains(vehicleType.id))
      _ <- if (desiredTypes.isEmpty) ZIO.fail(new IllegalArgumentException("No desired vehicle types presented")) else ZIO.unit
      tripPlans <- generateTripPlans(1, NonEmptyChunk(desiredTypes.head, desiredTypes.tail: _*), 4 * 3600, 22 * 3600, 777)
    } yield Scenario(600_000.0, 100, 86400 * 2, fillingStations, tripPlans)
  }

  def generateTripPlans(n: Int, vehicleTypes: NonEmptyChunk[VehicleType], startTime: Int, endTime: Int, seed: Int): UIO[Map[Id[TripPlan], TripPlan]] = {
    def createTripPlan(idNum: Int): UIO[TripPlan] = for {
      typeNum <- Random.nextIntBounded(vehicleTypes.size)
      vehicleType = vehicleTypes(typeNum)
      startTime <- Random.nextIntBetween(startTime, endTime)
      initialFuelLevel <- Random.nextDoubleBetween(0.3, 1.0)
      searchStationThreshold <- Random.nextDoubleBetween(20_000, 200_0000)
    } yield TripPlan(
      Id(idNum.toString),
      vehicleType,
      vehicleType.fuelCapacityInJoule * initialFuelLevel,
      startTime,
      searchStationThreshold
    )

    Random.setSeed(seed) *> ZIO.foreach(1 to n)(createTripPlan).map(_.groupMapReduce(_.id)(identity)((a, _) => a))


  }

  def loadFillingStations(path: Path): Task[Map[Id[FillingStation], FillingStation]] = {
    def createFillingStation(row: Map[String, String]): FillingStation = {
      val id: Id[FillingStation] = Id(row("station_id"))
      val positionInM = row("position_in_km").toDouble * 1000
      val numGas = row(Gasoline.toString).toInt
      val numDiesel = row(Diesel.toString).toInt
      val places = IndexedSeq.fill(numDiesel)(Set(Gasoline, Diesel)) ++ IndexedSeq.fill(numGas - numDiesel)(Set(Gasoline))
      FillingStation(id, positionInM, places, Map.empty)
    }

    CsvReader.readCsv(path, createFillingStation).map(_.groupMapReduce(_.id)(identity)((a, *) => a))
  }

  def loadVehicleTypes(path: Path): Task[IndexedSeq[VehicleType]] = {
    def createVehicleType(row: Map[String, String]): VehicleType = {
      VehicleType(
        Id(row("vehicle_type_id")),
        row("seating_capacity").toInt,
        row("standing_room_capacity").toInt,
        row("length_in_meter").toDouble,
        FuelType.valueOf(row("fuel_type")),
        row("fuel_consumption_in_joule_per_meter").toDouble,
        row("fuel_capacity_in_joule").toDouble,
        row.get("automation_level").filterNot(_.isEmpty).fold(0)(_.toInt),
        row.get("max_velocity").filterNot(_.isEmpty).map(_.toDouble),
        row.get("charging_capability").filterNot(_.isEmpty).map(_.toDouble),
        row.get("payload_capacity_in_kg").filterNot(_.isEmpty).map(_.toDouble),
      )
    }

    CsvReader.readCsv(path, createVehicleType)
  }

}



