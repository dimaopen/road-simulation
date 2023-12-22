package roadsimulation.model


/**
 * @author Dmitry Openkov
 */

trait StoredRoadEvent:

  // eventType, time, positionInM, personId, vehicleId
  def eventValues: (String, Double, Double, Id[Person], Id[Vehicle])
end StoredRoadEvent

object StoredRoadEvent:
  def header: Seq[String] = Seq("eventType", "time", "positionInM", "personId", "vehicleId")
end StoredRoadEvent


case class PersonGotToRoad(person: Person) extends StoredRoadEvent:
  override def eventValues = (this.getClass.getSimpleName, person.time, person.positionInM, person.id, Id.empty)
end PersonGotToRoad

case class PersonBoardedVehicle(person: Person, vehicle: Vehicle) extends StoredRoadEvent:
  override def eventValues = (this.getClass.getSimpleName, person.time, person.positionInM, person.id, vehicle.id)
end PersonBoardedVehicle
