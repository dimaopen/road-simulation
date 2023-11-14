package roadsimulation.actor

import roadsimulation.model.{FillingStation, Vehicle}

/**
 * @author Dmitry Openkov
 */

trait RoadEventType:
  def vehicle: Vehicle
object RoadEventType {
  final case class VehicleContinueTraveling(vehicle: Vehicle, entersRoad: Boolean) extends RoadEventType
  final case class VehicleAtPosition[+T](vehicle: Vehicle, possibleObject: Option[T]) extends RoadEventType
  final case class ArrivedAtFillingStation(vehicle: Vehicle, station: FillingStation) extends RoadEventType
  final case class RunOutOfGas(vehicle: Vehicle) extends RoadEventType
}