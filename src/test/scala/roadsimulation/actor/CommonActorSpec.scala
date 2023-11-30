package roadsimulation.actor

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import roadsimulation.simulation.SimulationSchedulerSpec.scenarioWithOneCar
import roadsimulation.model.{Id, Vehicle}
import roadsimulation.simulation.SimulationScheduler.NoEventReference

/**
 * @author Dmitry Openkov
 */
class CommonActorSpec extends AnyWordSpec with Matchers {
  "Movement" should {
    "calculate right intersection time" in {
      val scenario = scenarioWithOneCar
      val plan = scenario.tripPlans(Id("140"))
      val vehicle = Vehicle(plan.id,
        plan.vehicleType,
        plan.initialFuelLevelInJoule,
        passengers = Seq.empty,
        positionInM = 1.0,
        time = 2)
      val nextVehiclePosition = vehicle.copy(positionInM = 5, time = 4)
      Movement(vehicle, nextVehiclePosition, NoEventReference).intersectionTime(2, 3) shouldBe 3
      Movement(vehicle, nextVehiclePosition, NoEventReference).intersectionTime(4, 3).isNaN shouldBe true
    }
  }
}
