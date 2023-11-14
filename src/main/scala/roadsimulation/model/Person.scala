package roadsimulation.model

import roadsimulation.model.Person.Gender

/**
 * @author Dmitry Openkov
 */
case class Person(
  id: Id[Person],
  age: Int,
  gender: Gender,
  positionInM: Double,
  time: Double,
  money: Double,
  plan: PersonPlan
)

object Person:
  enum Gender:
    case Male, Female, Unknown

end Person

case class PersonPlan(originInM: Double, destinationInM: Double, startTime: Double)