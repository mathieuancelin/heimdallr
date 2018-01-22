package api

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.ActorMaterializer
import com.codahale.metrics.MetricRegistry
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
    Future.successful(BadRequest("Not Implemented Yet !"))
    //request.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map(_.utf8String).map { body =>
    //  parse(body) match {
    //    case Left(e) => BadRequest(e.message)
    //    case Right(json) =>
    //      json.as(Command.decoder) match {
    //        case Left(e)                               => BadRequest(e.message)
    //        case Right(Command("ADD", domain, target)) =>
    //          //store.modify { services =>
    //          //  services.get(domain) match {
    //          //    case Some(seq) => services + (domain -> (seq :+ Target(target)))
    //          //    case None      => services + (domain -> Seq(Target(target)))
    //          //  }
    //          //}
    //          ???
    //          Ok(Json.obj("done" -> Json.fromBoolean(true)))
    //        case Right(Command("REM", domain, target)) =>
    //          store.modify { services =>
    //            // services.get(domain) match {
    //            //   case Some(Service(_, _, seq, _)) if seq.size == 1 && seq(0).url == target => services - domain
    //            //   case Some(Service(_, _, seq, _))                                          => services + (domain -> seq.filterNot(_ == Target(target)))
    //            //   case _                                                  => services
    //            // }
    //            ???
    //          }
    //          Ok(Json.obj("done" -> Json.fromBoolean(true)))
    //        case _ => BadRequest("Unrecognized command")
    //      }
    //  }
    //}
  }

  def start(): Stoppable[AdminApi] = {
    logger.info(s"Listening for admin calls on http://${config.api.listenOn}:${config.api.httpPort}")
    http.bindAndHandleAsync(handler, config.api.listenOn, config.api.httpPort)
    config.api.certPath.foreach { path =>
      val httpsContext = HttpsSupport.context(path, config.api.certPass.get, config.api.keyStoreType)
      logger.info(s"Listening for admin calls on https://${config.api.listenOn}:${config.api.httpsPort}")
      http.bindAndHandleAsync(handler, config.api.listenOn, config.api.httpsPort, connectionContext = httpsContext)
    }
    this
  }

  def stop(): Unit = {
    http.shutdownAllConnectionPools()
    system.terminate()
  }

}
