package proxies

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model.headers.{Host, RawHeader}
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse, Uri}
import akka.pattern.CircuitBreaker
import akka.stream.ActorMaterializer
import models._
import org.slf4j.LoggerFactory
import store.Store
import util.HttpResponses._
import util.{HttpsSupport, Retry}

import scala.concurrent.{Future, TimeoutException}

class HttpProxy(config: ProxyConfig, store: Store) {

  implicit val system       = ActorSystem()
  implicit val executor     = system.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val http         = Http(system)

  lazy val logger = LoggerFactory.getLogger("http-proxy")

  val AbsoluteUri = """(?is)^(https?)://([^/]+)(/.*|$)""".r

  val counter = new AtomicInteger(0)

  val circuitBreakers = new ConcurrentHashMap[String, CircuitBreaker]()

  def extractHost(request: HttpRequest): String = request.uri.toString() match {
    case AbsoluteUri(_, hostPort, _) => hostPort
    case _                           => request.header[Host].map(_.host.address()).getOrElse("--")
  }

  def handler(request: HttpRequest): Future[HttpResponse] = {
    val host = extractHost(request)
    store.get().get(host) match {
      case Some(service) => {
        val rawSeq = service.targets
        val seq    = rawSeq.flatMap(t => (1 to t.weight).map(_ => t))
        Retry
          .retry(service.clientConfig.retry) {
            val index  = counter.incrementAndGet() % (if (seq.nonEmpty) seq.size else 1)
            val target = seq.apply(index)
            val circuitBreaker = circuitBreakers.computeIfAbsent(
              target.url,
              _ =>
                new CircuitBreaker(
                  system.scheduler,
                  maxFailures = service.clientConfig.maxFailures,
                  callTimeout = service.clientConfig.callTimeout,
                  resetTimeout = service.clientConfig.resetTimeout
              )
            )
            val headersIn: Seq[HttpHeader] =
            request.headers.filterNot(t => t.name() == "Host") :+
            Host(target.host) :+
            RawHeader("X-Fowarded-Host", host) :+
            RawHeader("X-Fowarded-Scheme", request.uri.scheme)
            val proxyRequest = request.copy(
              uri = request.uri.copy(
                scheme = target.scheme,
                authority = Authority(host = Uri.NamedHost(target.host), port = target.port)
              ),
              headers = headersIn.toList,
              protocol = target.protocol
            )
            circuitBreaker.withCircuitBreaker(http.singleRequest(proxyRequest))
          }
          .recover {
            case _: akka.pattern.CircuitBreakerOpenException => BadGateway("Circuit breaker opened")
            case _: TimeoutException                         => GatewayTimeout()
            case e                                           => BadGateway(e.getMessage)
          }
      }
      case None => Future.successful(NotFound(host))
    }
  }

  def start(): Unit = {
    val httpsContext = HttpsSupport.context(config.http.certPass, config.http.certPath)
    http.bindAndHandleAsync(handler, "0.0.0.0", config.http.httpPort)
    http.bindAndHandleAsync(handler, "0.0.0.0", config.http.httpsPort, connectionContext = httpsContext)
    logger.info(s"Listening on http://0.0.0.0:${config.http.httpPort} and https://0.0.0.0:${config.http.httpsPort}")
  }
}
