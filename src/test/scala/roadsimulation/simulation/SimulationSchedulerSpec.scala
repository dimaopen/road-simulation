package roadsimulation.simulation

import org.junit.runner.RunWith
import roadsimulation.actor.{FillingStationHandler, RoadEventType, VehicleHandlerImpl}
import roadsimulation.model.FuelType.{Diesel, Gasoline}
import roadsimulation.model.*
import roadsimulation.simulation.SimulationSchedulerSpec.scenarioWithOneCar
import zio.ZIO
import zio.test.*
import zio.test.junit.JUnitRunnableSpec

/**
 * @author Dmitry Openkov
 */
class SimulationSchedulerSpec extends JUnitRunnableSpec {
  def spec = suite("SimulationSchedulerSpec")(
    test("simulation happens correctly") {
      for {
        scheduler <- SimulationScheduler.make(30, Double.PositiveInfinity)
        scenario = scenarioWithOneCar
        fillingStationHandler <- FillingStationHandler.make(scenario, scheduler)
        vehicleHandler = new VehicleHandlerImpl(scenario, scheduler, fillingStationHandler)
        initialEvents <- vehicleHandler.initialEvents(scenario)
        _ <- ZIO.foreach(initialEvents)(event => scheduler.schedule(event))
        _ <- scheduler.startScheduling()
      } yield assertTrue(1 == 1)

    }
  )
}

object SimulationSchedulerSpec:
  def scenarioWithOneCar: Scenario = {
    val plans = Seq(TripPlan(Id("140"), vehicleTypeCar(),
      initialFuelLevelInJoule = 1.0203336632572643E10,
      startTime = 16197,
      startSearchingForFillingStationThresholdInM = 172866.45809750466
    ))
    Scenario(600_000.0, 100, 86400 * 2, fillingStations(), Map.empty, plans.groupMapReduce(_.id)(identity)((a, _) => a))
  }

  def vehicleTypeCar(): VehicleType = {
    VehicleType(
      Id("Car"),
      seatingCapacity = 4,
      standingRoomCapacity = 0,
      lengthInMeter = 4.5,
      fuelType = Gasoline,
      fuelConsumptionInJoulePerMeter = 3655.98,
      fuelCapacityInJoule = 3000_000_000.0,
    )
  }

  def fillingStations(): Map[Id[FillingStation], FillingStation] = {
    Seq(
      FillingStation(Id("Sibneft-1"), 100, IndexedSeq.fill(6)(Set(Gasoline, Diesel)), Map.empty),
      FillingStation(Id("Lukoil-1"), 200, IndexedSeq.fill(6)(Set(Gasoline, Diesel)), Map.empty),
      FillingStation(Id("Rosneft-1"), 220, IndexedSeq.fill(6)(Set(Gasoline, Diesel)), Map.empty),
      FillingStation(Id("Magnum"), 345, IndexedSeq.fill(6)(Set(Gasoline, Diesel)), Map.empty),
      FillingStation(Id("Rosneft-3"), 450, IndexedSeq.fill(6)(Set(Gasoline, Diesel)), Map.empty),
      FillingStation(Id("Lukoil-2"), 550, IndexedSeq.fill(6)(Set(Gasoline, Diesel)), Map.empty)
    ).groupMapReduce(_.id)(identity)((a, _) => a)
  }
end SimulationSchedulerSpec
