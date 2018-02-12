package io.heimdallr.modules

import akka.http.scaladsl.model._
import io.circe.Json
import io.heimdallr.models._

trait Module {
  def id: String
  def config: Option[Json] = None
  def state: Option[Json]  = None
}

// can handle construction mode, maintenance mode
trait PreconditionModule extends Module {
  def validatePreconditions(reqId: String, service: Service, request: HttpRequest): Either[HttpResponse, Unit]
}
object PreconditionModule {
  def validatePreconditions(modules: Seq[PreconditionModule],
                            reqId: String,
                            service: Service,
                            request: HttpRequest): Either[HttpResponse, Unit] = {
    var index                                     = 0
    var found: Option[Either[HttpResponse, Unit]] = None
    while (found.isEmpty && index < modules.size) {
      val module = modules(index)
      index = index + 1
      module.validatePreconditions(reqId, service, request) match {
        case a @ Left(_) => found = Some(a)
        case _           =>
      }
    }
    Right(())
  }
}

// can handle pass by api, pass by auth0, throttling, gobal throtthling, etc ...
trait ServiceAccessModule extends Module {
  def access(reqId: String, service: Service, request: HttpRequest): WithApiKeyOrNot
}
object ServiceAccessModule {
  def access(modules: Seq[ServiceAccessModule],
             reqId: String,
             service: Service,
             request: HttpRequest): WithApiKeyOrNot = {
    var index                          = 0
    var found: Option[WithApiKeyOrNot] = None
    while (found.isEmpty && index < modules.size) {
      val module = modules(index)
      index = index + 1
      module.access(reqId, service, request) match {
        case a @ BadApiKey     => found = Some(a)
        case a @ WithApiKey(_) => found = Some(a)
        case _                 =>
      }
    }
    NoApiKey
  }
}

// can handle headers additions, like JWT header, request id, API quotas, etc ...
trait HeadersInTransformationModule extends Module {
  def transform(reqId: String,
                host: String,
                service: Service,
                target: Target,
                request: HttpRequest,
                waon: WithApiKeyOrNot,
                headers: List[HttpHeader]): List[HttpHeader]
}
object HeadersInTransformationModule {
  def transform(modules: Seq[HeadersInTransformationModule],
                reqId: String,
                host: String,
                service: Service,
                target: Target,
                request: HttpRequest,
                waon: WithApiKeyOrNot,
                headers: List[HttpHeader]): List[HttpHeader] = {
    modules.foldLeft(List.empty[HttpHeader])(
      (seq, module) => seq ++ module.transform(reqId, host, service, target, request, waon, headers)
    )
  }
}

// can handle headers additions, like JWT header, request id, API quotas, etc ...
trait HeadersOutTransformationModule extends Module {
  def transform(reqId: String,
                host: String,
                service: Service,
                target: Target,
                request: HttpRequest,
                waon: WithApiKeyOrNot,
                proxyLatency: Long,
                targetLatency: Long,
                headers: List[HttpHeader]): List[HttpHeader]
}
object HeadersOutTransformationModule {
  def transform(modules: Seq[HeadersOutTransformationModule],
                reqId: String,
                host: String,
                service: Service,
                target: Target,
                request: HttpRequest,
                waon: WithApiKeyOrNot,
                proxyLatency: Long,
                targetLatency: Long,
                headers: List[HttpHeader]): List[HttpHeader] = {
    modules.foldLeft(List.empty[HttpHeader])(
      (seq, module) =>
        seq ++ module.transform(reqId, host, service, target, request, waon, proxyLatency, targetLatency, headers)
    )
  }
}

// can handle custom template errors
trait ErrorRendererModule extends Module {
  def render(reqId: String, status: Int, message: String, service: Option[Service], request: HttpRequest): HttpResponse
}
object ErrorRendererModule {
  def render(modules: Seq[ErrorRendererModule],
             reqId: String,
             status: Int,
             message: String,
             service: Option[Service],
             request: HttpRequest): HttpResponse = {
    modules.last.render(reqId, status, message, service, request)
  }
}

// can handle canary mode
trait TargetSetChooserModule extends Module {
  def choose(reqId: String, service: Service, request: HttpRequest): Seq[Target]
}
object TargetSetChooserModule {
  def choose(modules: Seq[TargetSetChooserModule],
             reqId: String,
             service: Service,
             request: HttpRequest): Seq[Target] = {
    modules.last.choose(reqId, service, request)
  }
}
