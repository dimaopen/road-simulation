package roadsimulation.util

import java.nio.file.Path
import com.github.tototoshi.csv.*
import zio._

/**
 * @author Dmitry Openkov
 */
object CsvReader {
  def readCsv(path: Path): Task[List[Map[String, String]]] =
    ZIO.acquireReleaseWith(ZIO.attempt(CSVReader.open(path.toFile)))(reader => ZIO.succeed(reader.close())) { reader =>
      ZIO.attempt(reader.allWithHeaders())
    }

  def readCsv[T](path: Path, mapper: Map[String, String] => T): Task[IndexedSeq[T]] =
    ZIO.acquireReleaseWith(ZIO.attempt(CSVReader.open(path.toFile)))(reader => ZIO.succeed(reader.close())) { reader =>
      for {
        it <- ZIO.attempt(reader.iteratorWithHeaders)
      } yield it.map(mapper).toIndexedSeq

    }
}
