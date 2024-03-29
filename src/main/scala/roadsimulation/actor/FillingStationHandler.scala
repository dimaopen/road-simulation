package roadsimulation.actor

import roadsimulation.model.FuelType.*
import roadsimulation.model.{FillingStation, FuelType, Id, Scenario, Vehicle}
import roadsimulation.simulation.SimulationScheduler
import roadsimulation.simulation.SimulationScheduler.{Continuation, SimEvent}
import zio.{Queue, Ref, UIO, ZIO}

import scala.collection.immutable.{TreeMap, TreeSet}

/**
 * @author Dmitry Openkov
 */
trait FillingStationHandler:
  def findNearestStationAfter(position: Double): Option[FillingStationObject]

class FillingStationHandlerImpl(
  scenario: Scenario,
  stationPositions: TreeMap[Double, FillingStationObject]
) extends FillingStationHandler:

  override def findNearestStationAfter(position: Double): Option[FillingStationObject] =
    stationPositions.minAfter(position).map(_._2)


object FillingStationHandler:
  def make(scenario: Scenario, scheduler: SimulationScheduler): UIO[FillingStationHandler] = ZIO.collectAll(
    scenario.fillingStations.values
      .map(station => for {
        counter <- Ref.make(0)
        queue <- Ref.make((TreeSet.empty[QueueEntry], TreeSet.empty[QueueEntry]))
      } yield station.positionInM -> new FillingStationObject(station, scheduler, counter, queue)
      )
  ).map(stations => new FillingStationHandlerImpl(scenario, TreeMap.from(stations)))

case class QueueEntry(vehicle: Vehicle, enterTime: Double, number: Int)

object QueueEntry:
  given containerOrdering[T]: Ordering[QueueEntry] = Ordering.by(entry => entry.enterTime -> entry.number)

final case class FillingFinished(queueEntry: QueueEntry) extends RoadEventType:
  override def vehicle: Vehicle = queueEntry.vehicle
end FillingFinished

class FillingStationObject(
  val fillingStation: FillingStation,
  scheduler: SimulationScheduler,
  counter: Ref[Int],
  queue: Ref[(TreeSet[QueueEntry], TreeSet[QueueEntry])]
):
  def enter(vehicle: Vehicle, enterTime: Double): UIO[Vehicle] = {
    val ttf = timeToFill(vehicle)
    scheduler.continueWhen(enterTime + ttf)
      .map { case Continuation(time, _) =>
        val addedFuel = caclAddedFuel(vehicle, time - enterTime)
        vehicle.copy(fuelLevelInJoule = vehicle.fuelLevelInJoule + addedFuel, time = time)
      }
  }

  private def handleQueue() = {
    for {
      entries <- queue.get
    } yield ()

  }

  /*private def fillingFinished(event: SimEvent[FillingFinished]): UIO[IndexedSeq[SimEvent[FillingFinished]]] =
    for {
      value <- queue.modify{ entries =>
        val newEntries = entries - event.eventType.queueEntry
        
        
      }
    } yield ()*/


  private def timeToFill(vehicle: Vehicle): Double =
    import FuelType.*
    vehicle.vehicleType.fuelType match
      case Gasoline | Diesel => 450
      case Propane | Methane => 900
      case Electricity => math.max(0,
        vehicle.vehicleType.fuelCapacityInJoule - vehicle.fuelLevelInJoule) / vehicle.vehicleType
        .chargingCapability
        .getOrElse(100.0)

  private def caclAddedFuel(vehicle: Vehicle, fuelingTime: Double): Double =
    import FuelType.*
    val ttf = timeToFill(vehicle)
    val fuelDiff = vehicle.vehicleType.fuelCapacityInJoule - vehicle.fuelLevelInJoule
    vehicle.vehicleType.fuelType match
      case Gasoline | Diesel => if (ttf > fuelingTime) 0 else fuelDiff
      case Propane | Methane | Electricity => math.min(1.0, fuelingTime / ttf) * fuelDiff
