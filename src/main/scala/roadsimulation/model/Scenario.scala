package roadsimulation.model

import roadsimulation.model.FuelType.*
import roadsimulation.model.*
import roadsimulation.model.Person.Gender
import roadsimulation.util.CsvReader
import zio.*

import java.nio.file.{Path, Paths}

/**
 * @author Dmitry Openkov
 */
case class Scenario(
  roadLengthInM: Double,
  speedLimitInKmPerHour: Double,
  endTime: Int,
  fillingStations: Map[Id[FillingStation], FillingStation],
  persons: Map[Id[Person], Person],
  tripPlans: Map[Id[TripPlan], TripPlan]
) {
  val speedLimitInMPerS: Double = speedLimitInKmPerHour / 3.6
}

object Scenario {
  def loadScenario(): Task[Scenario] = {
    for {
      fillingStations <- loadFillingStations(Paths.get("input/msk-spb/filling-stations.csv"))
      vehicleTypes <- loadVehicleTypes(Paths.get("input/vehicle-types.csv"))
      desiredVehicleTypeIds = Set[Id[VehicleType]](
        Id("Car"),
        Id("DieselCar"),
        Id("RH_Car"),
        Id("BUS-DEFAULT"),
      )
      desiredTypes = vehicleTypes.filter(vehicleType => desiredVehicleTypeIds.contains(vehicleType.id))
      _ <- ZIO.when(desiredTypes.isEmpty) {
        ZIO.fail(new IllegalArgumentException("No desired vehicle types presented"))
      }
      roadLength = 600_000.0
      persons <- generatePersons(2000, 3 * 3600, 18 * 3600, roadLength, -33472)
      tripPlans <- generateTripPlans(9000,
        NonEmptyChunk(desiredTypes.head, desiredTypes.tail: _*),
        4 * 3600,
        8 * 3600 + 2,
        777)
    } yield {
      Scenario(roadLength, 100, 86400 * 2, fillingStations, persons, tripPlans)
    }
  }

  def generateTripPlans(
    n: Int,
    vehicleTypes: NonEmptyChunk[VehicleType],
    startTime: Int,
    endTime: Int,
    seed: Int
  ): UIO[Map[Id[TripPlan], TripPlan]] = {
    def createTripPlan(idNum: Int): UIO[TripPlan] = for {
      typeNum <- Random.nextIntBounded(vehicleTypes.size)
      vehicleType = vehicleTypes(typeNum)
      startTime <- Random.nextDoubleBetween(startTime, endTime)
      initialFuelLevel <- Random.nextDoubleBetween(0.3, 1.0)
      searchStationThreshold <- Random.nextDoubleBetween(20_000, 200_000)
    } yield TripPlan(
      Id(idNum.toString),
      vehicleType,
      vehicleType.fuelCapacityInJoule * initialFuelLevel,
      startTime,
      searchStationThreshold
    )

    Random.setSeed(seed) *> ZIO.foreach(1 to n)(createTripPlan).map(_.groupMapReduce(_.id)(identity)((a, _) => a))
  }

  def generatePersons(
    n: Int,
    startTime: Int,
    endTime: Int,
    roadLength: Double,
    seed: Int
  ): UIO[Map[Id[Person], Person]] = {
    def createPersonPlan(): UIO[PersonPlan] = for {
      origin <- Random.nextDoubleBetween(0, roadLength - 1000)
      rnd <- Random.nextDouble
      destination <- if (rnd < 0.3) ZIO.succeed(roadLength) else Random.nextDoubleBetween(origin + 500, roadLength)
      startTime <- Random.nextDoubleBetween(startTime, endTime)
    } yield PersonPlan(origin, destination, startTime)

    def createPerson(idNum: Int): UIO[Person] = for {
      personPlan <- createPersonPlan()
      age <- Random.nextGaussian.map(_ * 15 + 30).map(Math.max(18, _))
      gender <- Random.nextIntBounded(Gender.values.length).map(Gender.values)
      money <- Random.nextGaussian.map(_ * 50 + 50).map(Math.max(10, _))
    } yield Person(
      Id(idNum.toString),
      math.round(age).toInt,
      gender,
      personPlan.originInM,
      personPlan.startTime,
      money,
      personPlan
    )

    Random.setSeed(seed) *> ZIO.foreach(1 to n)(createPerson).map(_.groupMapReduce(_.id)(identity)((a, _) => a))
  }

  def loadFillingStations(path: Path): Task[Map[Id[FillingStation], FillingStation]] = {
    def createFillingStation(row: Map[String, String]): FillingStation = {
      val id: Id[FillingStation] = Id(row("station_id"))
      val positionInM = row("position_in_km").toDouble * 1000
      val numGas = row(Gasoline.toString).toInt
      val numDiesel = row(Diesel.toString).toInt
      val places = IndexedSeq.fill(numDiesel)(Set(Gasoline,
        Diesel)) ++ IndexedSeq.fill(numGas - numDiesel)(Set(Gasoline))
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



