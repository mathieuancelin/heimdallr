package store

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.util.ByteString
import com.codahale.metrics.MetricRegistry
import io.circe.{Decoder, Encoder}
import models._
import org.slf4j.LoggerFactory
import util.{Startable, Stoppable}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure

case class UpdateStoreFile(path: String, state: Map[String, Seq[Service]])

class Store(initialState: Map[String, Seq[Service]] = Map.empty[String, Seq[Service]],
            stateConfig: Option[StateConfig],
            metrics: MetricRegistry)
    extends Startable[Store]
    with Stoppable[Store] {

  private implicit val system   = ActorSystem()
  private implicit val executor = system.dispatcher
  private implicit val materializer = ActorMaterializer.create(system)
  private implicit val http = Http(system)

  private val actor = system.actorOf(FileWriter.props())

  private val readCounter  = metrics.counter("store-reads")
  private val writeCounter = metrics.counter("store-writes")

  lazy val logger = LoggerFactory.getLogger("proxy")

  private val ref: AtomicReference[Map[String, Seq[Service]]] = {
    if (stateConfig.isDefined) {
      if (stateConfig.get.isRemote) {
        new AtomicReference[Map[String, Seq[Service]]](initialState)
      } else {
        val config = stateConfig.get.local
        new AtomicReference[Map[String, Seq[Service]]](
          config.map(c => new File(c.path)).filter(_.exists()).map { file =>
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
          }
        )
      }
    } else {
      new AtomicReference[Map[String, Seq[Service]]](initialState)
    }
  }

  private def fetchRemoteState(config: RemoteStateConfig): Future[Map[String, Seq[Service]]] = {
    val headers: List[HttpHeader] = config.headers.toList.map(t => RawHeader(t._1, t._2))
    http.singleRequest(HttpRequest(
      uri = Uri(config.url),
      method = HttpMethods.GET,
      headers = headers
    )).flatMap { response =>
      response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _)
    }.flatMap { bs =>
      val body = bs.utf8String
      io.circe.parser.parse(body) match {
        case Left(e) =>
          logger.error(s"Error while parsing state from http body: ${e.message}")
          FastFuture.failed(e)
        case Right(json) =>
          json.as[Seq[Service]](Decoder.decodeSeq(Decoders.ServiceDecoder)) match {
            case Left(e) =>
              logger.error(s"Error while parsing state from http body: ${e.message}")
              FastFuture.failed(e)
            case Right(services) =>
              FastFuture.successful(services.groupBy(_.domain))
          }
      }
    }
  }

  def modify(f: Map[String, Seq[Service]] => Map[String, Seq[Service]]): Map[String, Seq[Service]] = {
    readCounter.inc()
    val modifiedState = ref.updateAndGet(services => f(services))
    stateConfig.flatMap(_.local).map(_.path).foreach { path =>
      actor ! UpdateStoreFile(path, modifiedState)
    }
    modifiedState
  }

  def get(): Map[String, Seq[Service]] = {
    writeCounter.inc()
    ref.get()
  }

  override def start(): Stoppable[Store] = {
    stateConfig.flatMap(_.local).foreach { config =>
      system.scheduler.schedule(0.seconds, config.writeEvery) {
        actor ! UpdateStoreFile(config.path, get())
      }
    }
    stateConfig.flatMap(_.remote).foreach { config =>
      system.scheduler.schedule(0.seconds, config.pollEvery) {
        fetchRemoteState(config).map(s => modify(_ => s)).andThen {
          case Failure(e) => logger.error(s"Error while fetching remote state", e)
        }
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
