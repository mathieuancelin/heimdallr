package io.heimdallr.modules

import akka.http.scaladsl.model._
import io.circe.Json
import io.heimdallr.models._

trait Module[A] {
  def id: String
  def config: Option[Json] = None
  def state: Option[Json]  = None
}

// can handle construction mode, maintenance mode
trait PreconditionModule[A] extends Module[A] {
  def validatePreconditions(reqId: String, service: Service[A], request: HttpRequest): Either[HttpResponse, Unit]
}
object PreconditionModule {
  def validatePreconditions[A](modules: Seq[PreconditionModule[A]],
                               reqId: String,
                               service: Service[A],
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
trait ServiceAccessModule[A] extends Module[A] {
  def access(reqId: String, service: Service[A], request: HttpRequest): WithApiKeyOrNot
}
object ServiceAccessModule {
  def access[A](modules: Seq[ServiceAccessModule[A]],
                reqId: String,
                service: Service[A],
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
trait HeadersInTransformationModule[A] extends Module[A] {
  def transform(reqId: String,
                host: String,
                service: Service[A],
                target: Target,
                request: HttpRequest,
                waon: WithApiKeyOrNot,
                headers: List[HttpHeader]): List[HttpHeader]
}
object HeadersInTransformationModule {
  def transform[A](modules: Seq[HeadersInTransformationModule[A]],
                   reqId: String,
                   host: String,
                   service: Service[A],
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
trait HeadersOutTransformationModule[A] extends Module[A] {
  def transform(reqId: String,
                host: String,
                service: Service[A],
                target: Target,
                request: HttpRequest,
                waon: WithApiKeyOrNot,
                proxyLatency: Long,
                targetLatency: Long,
                headers: List[HttpHeader]): List[HttpHeader]
}
object HeadersOutTransformationModule {
  def transform[A](modules: Seq[HeadersOutTransformationModule[A]],
                   reqId: String,
                   host: String,
                   service: Service[A],
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
trait ErrorRendererModule[A] extends Module[A] {
  def render(reqId: String,
             status: Int,
             message: String,
             service: Option[Service[A]],
             request: HttpRequest): HttpResponse
}
object ErrorRendererModule {
  def render[A](modules: Seq[ErrorRendererModule[A]],
                reqId: String,
                status: Int,
                message: String,
                service: Option[Service[A]],
                request: HttpRequest): HttpResponse = {
    modules.last.render(reqId, status, message, service, request)
  }
}

// can handle canary mode
trait TargetSetChooserModule[A] extends Module[A] {
  def choose(reqId: String, service: Service[A], request: HttpRequest): Seq[Target]
}
object TargetSetChooserModule {
  def choose[A](modules: Seq[TargetSetChooserModule[A]],
                reqId: String,
                service: Service[A],
                request: HttpRequest): Seq[Target] = {
    modules.last.choose(reqId, service, request)
  }
}
