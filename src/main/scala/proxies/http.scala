package io.heimdallr.proxies

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.util.FastFuture
import akka.pattern.CircuitBreaker
import akka.stream.ActorMaterializer
import io.heimdallr.models.{WithApiKeyOrNot, _}
import io.heimdallr.modules._
import io.heimdallr.statsd._
import io.heimdallr.store.Store
import io.heimdallr.util._
import org.slf4j.LoggerFactory

import scala.concurrent.{Future, Promise, TimeoutException}
import scala.util.{Failure, Success}

class HttpProxy[A, K](config: ProxyConfig[A, K], store: Store[A, K], modules: Modules[A, K], statsd: Statsd[A, K])
    extends Startable[HttpProxy[A, K]]
    with Stoppable[HttpProxy[A, K]] {

  implicit val system       = ActorSystem()
  implicit val executor     = system.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val http         = Http(system)

  lazy val logger = LoggerFactory.getLogger("heimdallr")

  val boundHttp  = Promise[ServerBinding]
  val boundHttps = Promise[ServerBinding]

  val AbsoluteUri = """(?is)^(https?)://([^/]+)(/.*|$)""".r

  val counter = new AtomicInteger(0)

  val circuitBreakers = new ConcurrentHashMap[String, CircuitBreaker]()

  val authHeaderName = "Proxy-Authorization"

  def extractHost(request: HttpRequest): String = request.uri.toString() match {
    case AbsoluteUri(_, hostPort, _) if hostPort.contains(":") => hostPort.split(":")(0)
    case AbsoluteUri(_, hostPort, _)                           => hostPort
    case _                                                     => request.header[Host].map(_.host.address()).getOrElse("--")
  }

  def extractCallRestriction(service: Service[A, K], path: Uri.Path): CallRestriction = {
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
    val start     = System.currentTimeMillis()
    val startCtx  = statsd.timeCtx("proxy-request")
    val requestId = UUID.randomUUID().toString
    val ctx       = ReqContext(requestId, TypedMap.empty, request)
    val host      = extractHost(request)
    val fu = ServiceFinderModule.findService(modules.modules.ServiceFinderModule,
                                             store,
                                             ctx,
                                             host,
                                             request.uri.path,
                                             request.headers.groupBy(_.name()).mapValues(_.last)) match {
      case Some(service) => {
        val rawSeq = TargetSetChooserModule.choose(modules.modules.TargetSetChooserModule, ctx, service)
        val seq    = rawSeq.flatMap(t => (1 to t.weight).map(_ => t))
        val withApiKeyOrNot =
          ServiceAccessModule.access(modules.modules.ServiceAccessModules, ctx, service)
        val callRestriction = extractCallRestriction(service, request.uri.path)

        @inline
        def makeTheCall(waon: WithApiKeyOrNot): Future[HttpResponse] = {
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
              val headersWithoutHost = request.headers.filterNot(t => t.name() == "Host")
              val headersIn: Seq[HttpHeader] = headersWithoutHost ++
              HeadersInTransformationModule.transform(modules.modules.HeadersInTransformationModules,
                                                      ctx,
                                                      host,
                                                      service,
                                                      target,
                                                      waon,
                                                      headersWithoutHost.toList) :+
              Host(target.host)
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
              request.header[UpgradeToWebSocket] match {
                case Some(upgrade) => {
                  val flow = ActorFlow.actorRef(
                    out =>
                      WebSocketProxyActor.props(
                        proxyRequest.uri.copy(scheme = if (target.scheme == "https") "wss" else "ws"),
                        materializer,
                        out,
                        http,
                        headersIn
                    )
                  )
                  FastFuture.successful(upgrade.handleMessages(flow)).andThen {
                    case Success(resp) =>
                      if (logger.isInfoEnabled) {
                        logger.info(
                          s"$requestId - ${service.id} - ${request.uri.scheme}://$host:${request.uri.effectivePort} -> ${target.url} - ${request.method.value} ${request.uri.path
                            .toString()} ${resp.status.value} - ${System.currentTimeMillis() - top} ms."
                        )
                      }
                  }
                }
                case None => {
                  val callStart = System.currentTimeMillis()
                  circuitBreaker.withCircuitBreaker(http.singleRequest(proxyRequest)).map { resp =>
                    resp.copy(
                      headers = HeadersOutTransformationModule.transform(
                        modules.modules.HeadersOutTransformationModules,
                        ctx,
                        host,
                        service,
                        target,
                        waon,
                        System.currentTimeMillis() - start,
                        System.currentTimeMillis() - callStart,
                        resp.headers.toList
                      )
                    )
                  } andThen {
                    case Success(resp) =>
                      if (logger.isInfoEnabled) {
                        logger.info(
                          s"$requestId - ${service.id} - ${request.uri.scheme}://$host:${request.uri.effectivePort} -> ${target.url} - ${request.method.value} ${request.uri.path
                            .toString()} ${resp.status.value} - ${System.currentTimeMillis() - top} ms."
                        )
                      }
                  }
                }
              }
            }
            .recover {
              case _: akka.pattern.CircuitBreakerOpenException =>
                request.discardEntityBytes()
                ErrorRendererModule.render(modules.modules.ErrorRendererModule,
                                           ctx,
                                           502,
                                           "Circuit breaker is open",
                                           Some(service))
              case _: TimeoutException =>
                request.discardEntityBytes()
                ErrorRendererModule.render(modules.modules.ErrorRendererModule,
                                           ctx,
                                           504,
                                           "Gateway Time-out",
                                           Some(service))
              case e =>
                request.discardEntityBytes()
                ErrorRendererModule.render(modules.modules.ErrorRendererModule, ctx, 502, e.getMessage, Some(service))
            }
        }

        PreconditionModule.validatePreconditions(modules.modules.PreconditionModules, ctx, service) match {
          case Left(resp) => FastFuture.successful(resp)
          case Right(_) =>
            (callRestriction, withApiKeyOrNot) match {
              case (PublicCall, waon) => makeTheCall(waon)
              case (PrivateCall, NoApiKey) =>
                request.discardEntityBytes()
                FastFuture.successful(
                  ErrorRendererModule
                    .render(modules.modules.ErrorRendererModule, ctx, 401, "No ApiKey provided", Some(service))
                )
              case (PrivateCall, waon @ WithApiKey(_)) => makeTheCall(waon)
              case (PrivateCall, BadApiKey) =>
                request.discardEntityBytes()
                FastFuture.successful(
                  ErrorRendererModule
                    .render(modules.modules.ErrorRendererModule, ctx, 401, "Bad ApiKey provided", Some(service))
                )
              case _ =>
                request.discardEntityBytes()
                FastFuture.successful(
                  ErrorRendererModule
                    .render(modules.modules.ErrorRendererModule, ctx, 401, "No ApiKey provided", Some(service))
                )
            }
        }
      }
      case None =>
        request.discardEntityBytes()
        FastFuture.successful(
          ErrorRendererModule.render(modules.modules.ErrorRendererModule, ctx, 404, "No ApiKey provided", None)
        )
    }
    fu.andThen { case _ => startCtx.close() }
  }

  def start(): Stoppable[HttpProxy[A, K]] = {
    logger.info(s"Listening for http call on http://${config.http.listenOn}:${config.http.httpPort}")
    http.bindAndHandleAsync(handler, config.http.listenOn, config.http.httpPort).andThen {
      case Success(sb) => boundHttp.trySuccess(sb)
      case Failure(e)  => boundHttp.tryFailure(e)
    }
    config.http.certPath.foreach { path =>
      val httpsContext =
        HttpsSupport.context(path, config.http.keyPath, config.http.certPass.get, config.http.keyStoreType)
      logger.info(s"Listening for https calls on https://${config.http.listenOn}:${config.http.httpsPort}")
      http
        .bindAndHandleAsync(handler, config.http.listenOn, config.http.httpsPort, connectionContext = httpsContext)
        .andThen {
          case Success(sb) => boundHttps.trySuccess(sb)
          case Failure(e)  => boundHttps.tryFailure(e)
        }
    }
    this
  }

  def stop(): Unit = {
    http.shutdownAllConnectionPools()
    system.terminate()
  }
}
