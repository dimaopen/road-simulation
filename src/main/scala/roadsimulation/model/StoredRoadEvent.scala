package roadsimulation.model


/**
 * @author Dmitry Openkov
 */

trait StoredRoadEvent:

  // eventType, time, positionInM, personId, vehicleId
  def eventValues: (String, Double, Double, Id[Person], Id[Vehicle], Seq[Id[Person]], Int)
end StoredRoadEvent

object StoredRoadEvent:
  def header: Seq[String] = Seq("eventType", "time", "positionInM", "personId", "vehicleId")
end StoredRoadEvent


case class PersonGotToRoad(person: Person) extends StoredRoadEvent:
  override def eventValues = (
    this.getClass.getSimpleName,
    person.time,
    person.positionInM,
    person.id,
    Id.empty,
    Seq.empty,
    0
  )
end PersonGotToRoad

case class PersonBoardedVehicle(person: Person, vehicle: Vehicle) extends StoredRoadEvent:
  override def eventValues = (
    this.getClass.getSimpleName,
    person.time,
    person.positionInM,
    person.id, vehicle.id,
    vehicle.passengers.map(_.id),
    vehicle.passengers.length
  )
end PersonBoardedVehicle

case class VehicleRunOutOfGas(vehicle: Vehicle) extends StoredRoadEvent:
  override def eventValues = (this.getClass.getSimpleName,
    vehicle.time,
    vehicle.positionInM,
    Id.empty,
    vehicle.id,
    vehicle.passengers.map(_.id),
    vehicle.passengers.length
  )
end VehicleRunOutOfGas

case class VehicleReachedDestination(vehicle: Vehicle) extends StoredRoadEvent:
  override def eventValues = (this.getClass.getSimpleName,
    vehicle.time,
    vehicle.positionInM,
    Id.empty,
    vehicle.id,
    vehicle.passengers.map(_.id),
    vehicle.passengers.length
  )
end VehicleReachedDestination


