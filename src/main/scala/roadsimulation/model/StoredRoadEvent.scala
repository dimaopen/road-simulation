package roadsimulation.model


/**
 * @author Dmitry Openkov
 */
object event:
  type StoredRoadEvent =
    PersonGotToRoad
      | VehicleTripStarted
      | PersonBoardedVehicle
      | VehicleRunOutOfGas
      | VehicleReachedDestination

  trait VehicleEvent:
    def vehicle: Vehicle
  end VehicleEvent
  

  trait PersonEvent:
    def person: Person
  end PersonEvent
  
  case class VehicleTripStarted(vehicle: Vehicle) extends VehicleEvent

  case class PersonGotToRoad(person: Person) extends PersonEvent

  case class PersonBoardedVehicle(person: Person, vehicle: Vehicle) extends VehicleEvent with PersonEvent

  case class VehicleRunOutOfGas(vehicle: Vehicle) extends VehicleEvent

  case class VehicleReachedDestination(vehicle: Vehicle) extends VehicleEvent

end event