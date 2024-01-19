package roadsimulation.model


/**
 * @author Dmitry Openkov
 */
object event:
  type StoredRoadEvent =
    PersonGotToRoad
      | PersonBoardedVehicle
      | VehicleRunOutOfGas
      | VehicleReachedDestination

  case class PersonGotToRoad(person: Person)

  case class PersonBoardedVehicle(person: Person, vehicle: Vehicle)

  case class VehicleRunOutOfGas(vehicle: Vehicle)

  case class VehicleReachedDestination(vehicle: Vehicle)

end event