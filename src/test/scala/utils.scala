package test

import java.net.ServerSocket

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.ActorMaterializer
import akka.util.ByteString
import io.heimdallr.models._
import io.heimdallr.modules.default.{DefaultModules, NoExtension}
import io.heimdallr.util.HttpResponses
import io.heimdallr.util.Implicits._
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Random, Try}

object FutureImplicits {
  implicit class BetterFuture[A](val fu: Future[A]) extends AnyVal {
    def await(): A = {
      Await.result(fu, 60.seconds)
    }
  }
}

trait HeimdallrTestCaseHelper {

  def freePort: Int = {
    Try {
      val serverSocket = new ServerSocket(0)
      val port         = serverSocket.getLocalPort
      serverSocket.close()
      port
    }.toOption.getOrElse(Random.nextInt(1000) + 7000)
  }

  val AbsoluteUri = """(?is)^(https?)://([^/]+)(/.*|$)""".r

  def extractHost(request: HttpRequest): String =
    request.getHeader("X-Fowarded-Host").asOption.map(_.value()).getOrElse("--")

  def HttpCall(http: HttpExt, port: Int, host: String, path: String, headers: Seq[HttpHeader] = Seq.empty)(
      implicit executionContext: ExecutionContext,
      mat: ActorMaterializer
  ): Future[(Int, String, Seq[HttpHeader])] = {
    http
      .singleRequest(
        HttpRequest(
          method = HttpMethods.GET,
          protocol = HttpProtocols.`HTTP/1.1`,
          headers = List(Host(host)) ++ headers,
          uri = Uri(s"http://127.0.0.1:$port$path")
        )
      )
      .flatMap { response =>
        response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { body =>
          (response.status.intValue(), body.utf8String, response.headers)
        }
      }
  }

  def HeimdallrInstance(
      httpPort: Int,
      services: Seq[Service[NoExtension, NoExtension]]
  ): io.heimdallr.Proxy[NoExtension, NoExtension] = {
    val c = ProxyConfig(api = ApiConfig(enabled = false), http = HttpConfig(httpPort = httpPort), services = services)
    io.heimdallr.Proxy
      .withConfig(
        c,
        DefaultModules(c)
      )
      .startAndWait()
  }

  class TargetService(host: Option[String], path: String, contentType: String, result: HttpRequest => String) {

    val port = freePort

    implicit val system = ActorSystem()
    implicit val ec     = system.dispatcher
    implicit val mat    = ActorMaterializer.create(system)
    implicit val http   = Http(system)

    val logger = LoggerFactory.getLogger("heimdallr-test")

    def handler(request: HttpRequest): Future[HttpResponse] = {
      (request.method, request.uri.path) match {
        case (HttpMethods.GET, p) if p.toString() == path && host.isEmpty => {
          FastFuture.successful(
            HttpResponse(
              200,
              entity = HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`),
                                  ByteString(result(request)))
            )
          )
        }
        case (HttpMethods.GET, p) if p.toString() == path && extractHost(request) == host.get => {
          FastFuture.successful(
            HttpResponse(
              200,
              entity = HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`),
                                  ByteString(result(request)))
            )
          )
        }
        case (_, p) => {
          FastFuture.successful(HttpResponses.NotFound(p.toString()))
        }
      }
    }

    val bound = http.bindAndHandleAsync(handler, "0.0.0.0", port)

    def await(): TargetService = {
      Await.result(bound, 60.seconds)
      this
    }

    def stop(): Unit = {
      Await.result(bound, 60.seconds).unbind()
      Await.result(http.shutdownAllConnectionPools(), 60.seconds)
      Await.result(system.terminate(), 60.seconds)
    }
  }

  object TargetService {
    def apply(host: Option[String], path: String, contentType: String, result: HttpRequest => String): TargetService = {
      new TargetService(host, path, contentType, result)
    }
  }
}
