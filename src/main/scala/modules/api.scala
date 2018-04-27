package io.heimdallr.modules

import akka.http.scaladsl.model._
import io.circe.Json
import io.heimdallr.models._

trait Module[A, K] {
  def id: String
  def config: Option[Json] = None
  def state: Option[Json]  = None
}

// can handle construction mode, maintenance mode
trait PreconditionModule[A, K] extends Module[A, K] {
  def validatePreconditions(reqId: String, service: Service[A, K], request: HttpRequest): Either[HttpResponse, Unit]
}
object PreconditionModule {
  def validatePreconditions[A, K](modules: Seq[PreconditionModule[A, K]],
                               reqId: String,
                               service: Service[A, K],
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
trait ServiceAccessModule[A, K] extends Module[A, K] {
  def access(reqId: String, service: Service[A, K], request: HttpRequest): WithApiKeyOrNot
}
object ServiceAccessModule {
  def access[A, K](modules: Seq[ServiceAccessModule[A, K]],
                reqId: String,
                service: Service[A, K],
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
trait HeadersInTransformationModule[A, K] extends Module[A, K] {
  def transform(reqId: String,
                host: String,
                service: Service[A, K],
                target: Target,
                request: HttpRequest,
                waon: WithApiKeyOrNot,
                headers: List[HttpHeader]): List[HttpHeader]
}
object HeadersInTransformationModule {
  def transform[A, K](modules: Seq[HeadersInTransformationModule[A, K]],
                   reqId: String,
                   host: String,
                   service: Service[A, K],
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
trait HeadersOutTransformationModule[A, K] extends Module[A, K] {
  def transform(reqId: String,
                host: String,
                service: Service[A, K],
                target: Target,
                request: HttpRequest,
                waon: WithApiKeyOrNot,
                proxyLatency: Long,
                targetLatency: Long,
                headers: List[HttpHeader]): List[HttpHeader]
}
object HeadersOutTransformationModule {
  def transform[A, K](modules: Seq[HeadersOutTransformationModule[A, K]],
                   reqId: String,
                   host: String,
                   service: Service[A, K],
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
trait ErrorRendererModule[A, K] extends Module[A, K] {
  def render(reqId: String,
             status: Int,
             message: String,
             service: Option[Service[A, K]],
             request: HttpRequest): HttpResponse
}
object ErrorRendererModule {
  def render[A, K](modules: Seq[ErrorRendererModule[A, K]],
                reqId: String,
                status: Int,
                message: String,
                service: Option[Service[A, K]],
                request: HttpRequest): HttpResponse = {
    modules.last.render(reqId, status, message, service, request)
  }
}

// can handle canary mode
trait TargetSetChooserModule[A, K] extends Module[A, K] {
  def choose(reqId: String, service: Service[A, K], request: HttpRequest): Seq[Target]
}
object TargetSetChooserModule {
  def choose[A, K](modules: Seq[TargetSetChooserModule[A, K]],
                reqId: String,
                service: Service[A, K],
                request: HttpRequest): Seq[Target] = {
    modules.last.choose(reqId, service, request)
  }
}
