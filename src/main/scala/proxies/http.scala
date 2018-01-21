package proxies

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model.headers.{Host, RawHeader}
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.util.FastFuture
import akka.pattern.CircuitBreaker
import akka.stream.ActorMaterializer
import com.codahale.metrics.MetricRegistry
import models.{WithApiKeyOrNot, _}
import org.slf4j.LoggerFactory
import store.Store
import util.HttpResponses._
import util.Implicits._
import util._

import scala.concurrent.{Future, TimeoutException}
import scala.util.{Success, Try}

class HttpProxy(config: ProxyConfig, store: Store, metrics: MetricRegistry)
    extends Startable[HttpProxy]
    with Stoppable[HttpProxy] {

  implicit val system       = ActorSystem()
  implicit val executor     = system.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val http         = Http(system)

  lazy val logger = LoggerFactory.getLogger("proxy")

  val decoder = Base64.getUrlDecoder

  val AbsoluteUri = """(?is)^(https?)://([^/]+)(/.*|$)""".r

  val counter = new AtomicInteger(0)

  val circuitBreakers = new ConcurrentHashMap[String, CircuitBreaker]()

  val authHeaderName = "Proxy-Authorization"

  def extractHost(request: HttpRequest): String = request.uri.toString() match {
    case AbsoluteUri(_, hostPort, _) => hostPort
    case _                           => request.header[Host].map(_.host.address()).getOrElse("--")
  }

  def findService(host: String, path: Uri.Path): Option[Service] = {
    val uri = path.toString()
    store.get().get(host).flatMap { services =>
      services.sortWith((a, _) => a.root.isDefined).find { s =>
        if (s.root.isDefined) {
          uri.startsWith(s.root.get)
        } else {
          true
        }
      }
    }
    // store.get().get(host).flatMap(_.lastOption)
  }

  def extractApiKey(request: HttpRequest, service: Service): WithApiKeyOrNot = {
    request.getHeader(authHeaderName).asOption.flatMap { value =>
      Try {
        val token = value.value().replace("Basic ", "")
        new String(decoder.decode(token), "UTF-8").split(":").toList match {
          case clientId :: clientSecret :: Nil =>
            service.apiKeys.filter(a => a.clientId == clientId && a.clientSecret == clientSecret).lastOption match {
              case Some(apiKey) => WithApiKey(apiKey)
              case None         => BadApiKey
            }
          case _ => NoApiKey
        }
      }.toOption
    } match {
      case Some(withApiKeyOrNot) => withApiKeyOrNot
      case None                  => NoApiKey
    }
  }

  def extractCallRestriction(service: Service, path: Uri.Path): CallRestriction = {
    val uri                 = path.toString()
    val privatePatternMatch = service.privatePatterns.exists(p => RegexPool(p).matches(uri))
    val publicPatternMatch  = service.publicPatterns.exists(p => RegexPool(p).matches(uri))
    if (!privatePatternMatch && publicPatternMatch) {
      PublicCall
    } else {
      PrivateCall
    }
  }

  def handler(request: HttpRequest): Future[HttpResponse] = {
    val start = metrics.timer("proxy-request").time()
    val host  = extractHost(request)
    val fu = findService(host, request.uri.path) match {
      case Some(service) => {
        val rawSeq          = service.targets
        val seq             = rawSeq.flatMap(t => (1 to t.weight).map(_ => t))
        val withApiKeyOrNot = extractApiKey(request, service)
        val callRestriction = extractCallRestriction(service, request.uri.path)

        @inline
        def makeTheCall(): Future[HttpResponse] = {
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
              request.headers.filterNot(t => t.name() == "Host" || t.name() == authHeaderName) ++
              service.headers.toSeq.map(t => RawHeader(t._1, t._2)) :+
              Host(target.host) :+
              RawHeader("X-Fowarded-Host", host) :+
              RawHeader("X-Fowarded-Scheme", request.uri.scheme)
              val proxyRequest = request.copy(
                uri = request.uri.copy(
                  path = Uri.Path(service.targetRoot) ++ request.uri.path,
                  scheme = target.scheme,
                  authority = Authority(host = Uri.NamedHost(target.host), port = target.port)
                ),
                headers = headersIn.toList,
                protocol = target.protocol
              )
              val top = System.currentTimeMillis()
              circuitBreaker.withCircuitBreaker(http.singleRequest(proxyRequest)).andThen {
                case Success(resp) =>
                  logger.info(s"${counter.get()} - ${service.id} ${request.method.value} ${request.uri.scheme}://$host${request.uri.path
                    .toString()} -> ${target.url} ${resp.status.value} ${System.currentTimeMillis() - top} ms.")
              }
            }
            .recover {
              case _: akka.pattern.CircuitBreakerOpenException =>
                request.discardEntityBytes()
                BadGateway("Circuit breaker is open")
              case _: TimeoutException                         =>
                request.discardEntityBytes()
                GatewayTimeout()
              case e                                           =>
                request.discardEntityBytes()
                BadGateway(e.getMessage)
            }
        }
        (callRestriction, withApiKeyOrNot) match {
          case (PublicCall, _)                   => makeTheCall()
          case (PrivateCall, NoApiKey)           =>
            request.discardEntityBytes()
            FastFuture.successful(Unauthorized("No ApiKey provided"))
          case (PrivateCall, WithApiKey(apiKey)) => makeTheCall()
          case (PrivateCall, BadApiKey)          =>
            request.discardEntityBytes()
            FastFuture.successful(Unauthorized("Bad ApiKey provided"))
          case _                                 =>
            request.discardEntityBytes()
            FastFuture.successful(Unauthorized("No ApiKey provided"))
        }
      }
      case None =>
        request.discardEntityBytes()
        FastFuture.successful(NotFound(host))
    }
    fu.andThen { case _ => start.close() }
  }

  def start(): Stoppable[HttpProxy] = {
    val httpsContext = HttpsSupport.context(config.http.certPass, config.http.certPath)
    http.bindAndHandleAsync(handler, "0.0.0.0", config.http.httpPort)
    http.bindAndHandleAsync(handler, "0.0.0.0", config.http.httpsPort, connectionContext = httpsContext)
    logger.info(s"Listening on http://0.0.0.0:${config.http.httpPort} and https://0.0.0.0:${config.http.httpsPort}")
    this
  }

  def stop(): Unit = {
    http.shutdownAllConnectionPools()
    system.terminate()
  }
}
