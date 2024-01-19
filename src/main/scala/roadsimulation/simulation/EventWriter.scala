package roadsimulation.simulation

import com.github.tototoshi.csv.CSVWriter
import com.github.tototoshi.csv.defaultCSVFormat
import roadsimulation.model.{Person, Vehicle, Id}
import roadsimulation.model.event.*
import zio.stream.{Sink, ZPipeline, ZSink, ZStream}
import zio.{Chunk, Hub, ZIO}

import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.collection.Seq
import scala.compiletime.constValue
import scala.deriving.Mirror
import scala.reflect.ClassTag

/**
 * @author Dmitry Openkov
 */
object EventWriter:
  def fromHub(hub: Hub[StoredRoadEvent]): ZIO[Any, Throwable, Long] =
    val stream = ZStream.fromHub(hub)
    val pipe = ZPipeline.map[Chunk[StoredRoadEvent], Chunk[Byte]] { roadEvents =>
      val byteOutput = new ByteArrayOutputStream(256)
      val csvWriter = new CSVWriter(new OutputStreamWriter(byteOutput, StandardCharsets.UTF_8))
      csvWriter.writeAll(roadEvents.map(event => rowToArray(eventToRow(event))))
      Chunk.fromArray(byteOutput.toByteArray)
    }
    val sink: Sink[Throwable, Byte, Byte, Long] = ZSink.fromPath(Path.of("events.csv"))
    stream.chunks
      .via(pipe)
      .via(ZPipeline.prepend(Chunk(Chunk.fromArray(header.mkString("", ",", "\n").getBytes(StandardCharsets.UTF_8)))))
      .via(ZPipeline.flattenChunks)
      .run(sink)

  private def header: Seq[String] = Seq("type", "time", "position", "person", "vehicle", "passengers", "num_passengers")

  private type EventRow = (String, Double, Double, Id[Person], Id[Vehicle], Seq[Id[Person]], Int)

  private def rowToArray(row: EventRow): Array[AnyRef] =
    val array = row.toArray
    array(5) = row._6.mkString(":")
    if row._7 < 0 then array(6) = ""
    array


  private def eventToRow(event: StoredRoadEvent): EventRow =
    event match
      case e: VehicleEvent with PersonEvent =>
        (
          event.getClass.getSimpleName,
          e.vehicle.time,
          e.vehicle.positionInM,
          e.person.id,
          e.vehicle.id,
          e.vehicle.passengers.map(_.id),
          e.vehicle.passengers.length
        )
      case e: PersonEvent =>
        (
          event.getClass.getSimpleName,
          e.person.time,
          e.person.positionInM,
          e.person.id,
          Id.empty,
          Seq.empty,
          -1
        )
      case e: VehicleEvent =>
        (event.getClass.getSimpleName,
          e.vehicle.time,
          e.vehicle.positionInM,
          Id.empty,
          e.vehicle.id,
          e.vehicle.passengers.map(_.id),
          e.vehicle.passengers.length)


end EventWriter


