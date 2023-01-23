package roadsimulation.model

/**
 * @author Dmitry Openkov
 */
case class FillingStation(id: Id[FillingStation], positionInM: Double, places: IndexedSeq[Set[FuelType]], price: Map[FuelType, Double])