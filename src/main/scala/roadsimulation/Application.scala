package roadsimulation

import roadsimulation.model.Scenario
import roadsimulation.simulation.Simulation
import zio.Console.*
import zio.{Ref, ZIO, ZIOAppDefault}

import java.io.IOException

object Application extends ZIOAppDefault {

  def run: ZIO[Any, AnyRef, Unit] = for {
    scenario <- Scenario.loadScenario()
    queue <- Simulation.simulate(scenario)
  } yield ()

}