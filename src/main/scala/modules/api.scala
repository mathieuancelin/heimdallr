package io.heimdallr.modules

import akka.http.scaladsl.model._
import io.circe.{Decoder, Encoder, Json}
import io.heimdallr.models._
import io.heimdallr.store.Store

trait Module[A, K] {
  def id: String
  def config: Option[Json] = None
  def state: Option[Json]  = None
}

trait Extensions[A, K] {
  def serviceExtensionEncoder: Encoder[A]
  def serviceExtensionDecoder: Decoder[A]
  def apiKeyExtensionEncoder: Encoder[K]
  def apiKeyExtensionDecoder: Decoder[K]
}

trait Modules[A, B] {
  def modules: ModulesConfig[A, B]
  def extensions: Extensions[A, B]
  def store: Option[Store[A, B]] = None
}

trait ModulesConfig[A, K] {
  def PreconditionModules: Seq[PreconditionModule[A, K]]
  def ServiceAccessModules: Seq[ServiceAccessModule[A, K]]
  def HeadersInTransformationModules: Seq[HeadersInTransformationModule[A, K]]
  def HeadersOutTransformationModules: Seq[HeadersOutTransformationModule[A, K]]
  def ErrorRendererModule: ErrorRendererModule[A, K]
  def TargetSetChooserModule: TargetSetChooserModule[A, K]
  def ServiceFinderModule: ServiceFinderModule[A, K]
}

// can handle construction mode, maintenance mode
trait PreconditionModule[A, K] extends Module[A, K] {
  def validatePreconditions(ctx: ReqContext, service: Service[A, K]): Either[HttpResponse, Unit]
}
object PreconditionModule {
  def validatePreconditions[A, K](modules: Seq[PreconditionModule[A, K]],
                                  ctx: ReqContext,
                                  service: Service[A, K]): Either[HttpResponse, Unit] = {
    var index                                     = 0
    var found: Option[Either[HttpResponse, Unit]] = None
    while (found.isEmpty && index < modules.size) {
      val module = modules(index)
      index = index + 1
      module.validatePreconditions(ctx, service) match {
        case a @ Left(_) => found = Some(a)
        case _           =>
      }
    }
    Right(())
  }
}

trait ServiceFinderModule[A, K] extends Module[A, K] {
  def findService(ctx: ReqContext,
                  store: Store[A, K],
                  host: String,
                  path: Uri.Path,
                  headers: Map[String, HttpHeader]): Option[Service[A, K]]
}
object ServiceFinderModule {
  def findService[A, K](module: ServiceFinderModule[A, K],
                        store: Store[A, K],
                        ctx: ReqContext,
                        host: String,
                        path: Uri.Path,
                        headers: Map[String, HttpHeader]): Option[Service[A, K]] = {
    module.findService(ctx, store, host, path, headers)
  }
}

// can handle pass by api, pass by auth0, throttling, gobal throtthling, etc ...
trait ServiceAccessModule[A, K] extends Module[A, K] {
  def access(ctx: ReqContext, service: Service[A, K]): WithApiKeyOrNot
}
object ServiceAccessModule {
  def access[A, K](modules: Seq[ServiceAccessModule[A, K]],
                   ctx: ReqContext,
                   service: Service[A, K]): WithApiKeyOrNot = {
    var index                          = 0
    var found: Option[WithApiKeyOrNot] = None
    while (found.isEmpty && index < modules.size) {
      val module = modules(index)
      index = index + 1
      module.access(ctx, service) match {
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
  def transform(ctx: ReqContext,
                host: String,
                service: Service[A, K],
                target: Target,
                waon: WithApiKeyOrNot,
                headers: List[HttpHeader]): List[HttpHeader]
}
object HeadersInTransformationModule {
  def transform[A, K](modules: Seq[HeadersInTransformationModule[A, K]],
                      ctx: ReqContext,
                      host: String,
                      service: Service[A, K],
                      target: Target,
                      waon: WithApiKeyOrNot,
                      headers: List[HttpHeader]): List[HttpHeader] = {
    modules.foldLeft(List.empty[HttpHeader])(
      (seq, module) => seq ++ module.transform(ctx, host, service, target, waon, headers)
    )
  }
}

// can handle headers additions, like JWT header, request id, API quotas, etc ...
trait HeadersOutTransformationModule[A, K] extends Module[A, K] {
  def transform(ctx: ReqContext,
                host: String,
                service: Service[A, K],
                target: Target,
                waon: WithApiKeyOrNot,
                proxyLatency: Long,
                targetLatency: Long,
                headers: List[HttpHeader]): List[HttpHeader]
}
object HeadersOutTransformationModule {
  def transform[A, K](modules: Seq[HeadersOutTransformationModule[A, K]],
                      ctx: ReqContext,
                      host: String,
                      service: Service[A, K],
                      target: Target,
                      waon: WithApiKeyOrNot,
                      proxyLatency: Long,
                      targetLatency: Long,
                      headers: List[HttpHeader]): List[HttpHeader] = {
    modules.foldLeft(List.empty[HttpHeader])(
      (seq, module) => seq ++ module.transform(ctx, host, service, target, waon, proxyLatency, targetLatency, headers)
    )
  }
}

// can handle custom template errors
trait ErrorRendererModule[A, K] extends Module[A, K] {
  def render(ctx: ReqContext, status: Int, message: String, service: Option[Service[A, K]]): HttpResponse
}
object ErrorRendererModule {
  def render[A, K](module: ErrorRendererModule[A, K],
                   ctx: ReqContext,
                   status: Int,
                   message: String,
                   service: Option[Service[A, K]]): HttpResponse = {
    module.render(ctx, status, message, service)
  }
}

// can handle canary mode
trait TargetSetChooserModule[A, K] extends Module[A, K] {
  def choose(ctx: ReqContext, service: Service[A, K]): Seq[Target]
}
object TargetSetChooserModule {
  def choose[A, K](module: TargetSetChooserModule[A, K], ctx: ReqContext, service: Service[A, K]): Seq[Target] = {
    module.choose(ctx, service)
  }
}
