package roadsimulation.actor

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * @author Dmitry Openkov
 */
class CommonActorSpec extends AnyWordSpec with Matchers {
  "Movement" should {
    "calculate right intersection time" in {
      Movement(1, 2, 5, 4).intersectionTime(2, 3) shouldBe 3
      Movement(1, 2, 5, 4).intersectionTime(4, 3).isNaN shouldBe true
    }
  }
}
