package store

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{Actor, ActorSystem, Props}
import com.codahale.metrics.MetricRegistry
import io.circe.{Decoder, Encoder}
import models.{Decoders, Encoders, Service}
import org.slf4j.LoggerFactory
import util.{Startable, Stoppable}

import scala.concurrent.duration._

case class UpdateStoreFile(path: String, state: Map[String, Seq[Service]])

class Store(initialState: Map[String, Seq[Service]] = Map.empty[String, Seq[Service]],
            statePath: Option[String],
            metrics: MetricRegistry)
    extends Startable[Store]
    with Stoppable[Store] {

  private implicit val system   = ActorSystem()
  private implicit val executor = system.dispatcher

  private val actor = system.actorOf(FileWriter.props())

  private val readCounter  = metrics.counter("store-reads")
  private val writeCounter = metrics.counter("store-writes")

  lazy val logger = LoggerFactory.getLogger("proxy")

  private val ref: AtomicReference[Map[String, Seq[Service]]] = {
    new AtomicReference[Map[String, Seq[Service]]](statePath.map(new File(_)).filter(_.exists()).map { file =>
      io.circe.parser.parse(new String(Files.readAllBytes(file.toPath))) match {
        case Left(e) =>
          logger.error(s"Error while parsing state file: ${e.message}")
          initialState
        case Right(json) =>
          json.as[Seq[Service]](Decoder.decodeSeq(Decoders.ServiceDecoder)) match {
            case Left(e) =>
              logger.error(s"Error while parsing state file: ${e.message}")
              initialState
            case Right(services) =>
              logger.info(s"Loading state from ${file.toPath.toString}")
              services.groupBy(_.domain)
          }
      }
    } getOrElse {
      initialState
    })
  }

  def modify(f: Map[String, Seq[Service]] => Map[String, Seq[Service]]): Map[String, Seq[Service]] = {
    readCounter.inc()
    val modifiedState = ref.updateAndGet(services => f(services))
    statePath.foreach { path =>
      actor ! UpdateStoreFile(path, modifiedState)
    }
    modifiedState
  }

  def get(): Map[String, Seq[Service]] = {
    writeCounter.inc()
    ref.get()
  }

  override def start(): Stoppable[Store] = {
    statePath.foreach { path =>
      system.scheduler.schedule(0.seconds, 10.seconds) {
        actor ! UpdateStoreFile(path, get())
      }
    }
    this
  }

  override def stop(): Unit = {
    system.terminate()
  }
}

class FileWriter extends Actor {

  import io.circe.syntax._

  override def receive: Receive = {
    case UpdateStoreFile(path, state) => {
      val content = state.values.flatten.toSeq.asJson(Encoder.encodeSeq(Encoders.ServiceEncoder)).noSpaces
      Files.write(Paths.get(path), content.getBytes)
    }
  }
}

object FileWriter {
  def props(): Props = Props(new FileWriter())
}
