package roadsimulation.simulation

import com.github.tototoshi.csv.CSVWriter
import com.github.tototoshi.csv.defaultCSVFormat
import roadsimulation.model.StoredRoadEvent
import zio.stream.{Sink, ZPipeline, ZSink, ZStream}
import zio.{Chunk, Hub, ZIO}

import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.compiletime.constValue
import scala.deriving.Mirror
import scala.reflect.ClassTag

/**
 * @author Dmitry Openkov
 */
object EventWriter:

  def fromHub(hub: Hub[StoredRoadEvent]): ZIO[Any, Throwable, Long] =
    val stream = ZStream.fromHub(hub)
    val pipe = ZPipeline.map[Chunk[StoredRoadEvent], Chunk[Byte]]{ roadEvents =>
      val byteOutput = new ByteArrayOutputStream(256)
      val csvWriter = new CSVWriter(new OutputStreamWriter(byteOutput, StandardCharsets.UTF_8))
      csvWriter.writeAll(roadEvents.map(e => e.eventValues.toArray))
      Chunk.fromArray(byteOutput.toByteArray)
    }
    val sink: Sink[Throwable, Byte, Byte, Long] = ZSink.fromPath(Path.of("events.csv"))
    stream.chunks
      .via(pipe)
      .via(ZPipeline.prepend(Chunk(Chunk.fromArray(StoredRoadEvent.header.mkString("", ",", "\n").getBytes(StandardCharsets.UTF_8)))))
      .via(ZPipeline.flattenChunks)
      .run(sink)

end EventWriter


