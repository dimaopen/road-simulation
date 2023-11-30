package roadsimulation.model


/**
 * @author Dmitry Openkov
 */

opaque type Id[T] = String
object Id:
  def apply[T](id: String): Id[T] = id
end Id

case class PositionKey[T](position: Double, time: Double, id: Id[T])

object PositionKey:
  def ordering[T]: Ordering[PositionKey[T]] =
    Ordering.by(key => (key.position, key.time, key.id.asInstanceOf[String]))
    
  def minKeyForPosition[T](position: Double): PositionKey[T] = PositionKey(position, 0, Id.apply(""))  
end PositionKey


extension (d: Double)
  def >=~(o: Double): Boolean = d > o - 0.1
  def <=~(o: Double): Boolean = d < o + 0.1

