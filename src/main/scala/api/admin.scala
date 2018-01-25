package api

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.util.ByteString
import com.codahale.metrics.MetricRegistry
import io.circe.Json
import models._
import org.slf4j.LoggerFactory
import store.Store
import util.HttpResponses._
import util.{HttpsSupport, Startable, Stoppable}

import scala.concurrent.Future

class AdminApi(config: ProxyConfig, store: Store, metrics: MetricRegistry)
    extends Startable[AdminApi]
    with Stoppable[AdminApi] {

  implicit val system       = ActorSystem()
  implicit val executor     = system.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val http         = Http(system)

  lazy val logger = LoggerFactory.getLogger("proxy")

  def handler(request: HttpRequest): Future[HttpResponse] = {
    (request.method, request.uri.path) match {
      case (HttpMethods.POST, Uri.Path("/command")) =>
        request.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { bs =>
          val body = bs.utf8String
          io.circe.parser.parse(body) match {
            case Left(_) => BadRequest("Error while parsing body")
            case Right(json) =>
              logger.info(s"received command: ${json.noSpaces}")
              Command.decode(json.hcursor.downField("command").as[String].getOrElse("none"), json) match {
                case Left(_)        => BadRequest("Error while parsing command")
                case Right(command) => Ok(command.applyModification(store))
              }
          }
        }
      case (_, path) => FastFuture.successful(NotFound(path.toString()))
    }
  }

  def start(): Stoppable[AdminApi] = {
    logger.info(s"Listening for api commands on http://${config.api.listenOn}:${config.api.httpPort}")
    http.bindAndHandleAsync(handler, config.api.listenOn, config.api.httpPort)
    config.api.certPath.foreach { path =>
      val httpsContext =
        HttpsSupport.context(path, config.api.keyPath, config.api.certPass.get, config.api.keyStoreType)
      logger.info(s"Listening for admin commands on https://${config.api.listenOn}:${config.api.httpsPort}")
      http.bindAndHandleAsync(handler, config.api.listenOn, config.api.httpsPort, connectionContext = httpsContext)
    }
    this
  }

  def stop(): Unit = {
    http.shutdownAllConnectionPools()
    system.terminate()
  }
}
