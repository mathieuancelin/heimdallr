package io.heimdallr.api

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.util.ByteString
import io.circe.Json
import io.heimdallr.models._
import io.heimdallr.modules.Extensions
import io.heimdallr.store.Store
import io.heimdallr.statsd._
import io.heimdallr.util.HttpResponses._
import io.heimdallr.util.{HttpsSupport, Startable, Stoppable}
import io.heimdallr.util.Implicits._
import org.slf4j.LoggerFactory

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

class AdminApi[A, K](config: ProxyConfig[A, K],
                     store: Store[A, K],
                     metrics: Statsd[A, K],
                     commands: Commands[A, K],
                     encoders: Encoders[A, K])
    extends Startable[AdminApi[A, K]]
    with Stoppable[AdminApi[A, K]] {

  implicit val system       = ActorSystem()
  implicit val executor     = system.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val http         = Http(system)

  val boundHttp  = Promise[ServerBinding]
  val boundHttps = Promise[ServerBinding]

  lazy val logger = LoggerFactory.getLogger("heimdallr")

  def handler(request: HttpRequest): Future[HttpResponse] = {
    (request.method, request.uri.path) match {
      case (HttpMethods.GET, Uri.Path("/api/services")) => {
        store.get().map { map =>
          Ok(encoders.SeqOfServiceEncoder(map.values.flatten.toSeq))
        }
      }
      case (HttpMethods.GET, path) if path.startsWith(Uri.Path("/api/services/")) => {
        val serviceId = path.toString.replace("/api/services/", "")
        store.get().map { map =>
          map.values.flatten.toSeq.find(_.id == serviceId) match {
            case Some(service) => Ok(encoders.ServiceEncoder(service))
            case None          => NotFound(s"Service with id $serviceId does not exist")
          }
        }
      }
      case (HttpMethods.POST, Uri.Path("/api/_command")) =>
        request.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).flatMap { bs =>
          val body = bs.utf8String
          io.circe.parser.parse(body) match {
            case Left(_) => BadRequest("Error while parsing body").asFuture
            case Right(json) =>
              logger.info(s"received command: ${json.noSpaces}")
              commands.decode(json.hcursor.downField("command").as[String].getOrElse("none"), json) match {
                case Left(_)        => BadRequest("Error while parsing command").asFuture
                case Right(command) => command.applyModification(store, encoders).map(Ok(_))
              }
          }
        }
      case (_, path) => FastFuture.successful(NotFound(path.toString()))
    }
  }

  def start(): Stoppable[AdminApi[A, K]] = {
    logger.info(s"Listening for api commands on http://${config.api.listenOn}:${config.api.httpPort}")
    http.bindAndHandleAsync(handler, config.api.listenOn, config.api.httpPort).andThen {
      case Success(sb) => boundHttp.trySuccess(sb)
      case Failure(e)  => boundHttp.tryFailure(e)
    }
    config.api.certPath.foreach { path =>
      val httpsContext =
        HttpsSupport.context(path, config.api.keyPath, config.api.certPass.get, config.api.keyStoreType)
      logger.info(s"Listening for api commands on https://${config.api.listenOn}:${config.api.httpsPort}")
      http
        .bindAndHandleAsync(handler, config.api.listenOn, config.api.httpsPort, connectionContext = httpsContext)
        .andThen {
          case Success(sb) => boundHttp.trySuccess(sb)
          case Failure(e)  => boundHttp.tryFailure(e)
        }
    }
    this
  }

  def stop(): Unit = {
    http.shutdownAllConnectionPools()
    system.terminate()
  }
}
