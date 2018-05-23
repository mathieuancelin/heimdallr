package io.heimdallr.modules

import akka.http.scaladsl.model._
import io.circe.{Decoder, Encoder, Json}
import io.heimdallr.models._

import scala.concurrent.{ExecutionContext, Future}

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
}

trait ModulesConfig[A, K] {
  def BeforeAfterModule: BeforeAfterModule[A, K]
  def PreconditionModule: PreconditionModule[A, K]
  def ServiceAccessModule: ServiceAccessModule[A, K]
  def HeadersInTransformationModule: HeadersInTransformationModule[A, K]
  def HeadersOutTransformationModule: HeadersOutTransformationModule[A, K]
  def ErrorRendererModule: ErrorRendererModule[A, K]
  def TargetSetChooserModule: TargetSetChooserModule[A, K]
  def ServiceFinderModule: ServiceFinderModule[A, K]
  def ServiceStore: ServiceStoreModule[A, K]
}

trait ServiceStoreModule[A, K] extends Module[A, K] {
  def getAllServices()(implicit ec: ExecutionContext): Future[Map[String, Seq[Service[A, K]]]]
  def setAllServices(services: Map[String, Seq[Service[A, K]]])(
      implicit ec: ExecutionContext
  ): Future[Map[String, Seq[Service[A, K]]]]
  def findServiceById(id: String)(implicit ec: ExecutionContext): Future[Option[Service[A, K]]]
  def addService(service: Service[A, K])(implicit ec: ExecutionContext): Future[Unit]
  def updateService(id: String, service: Service[A, K])(implicit ec: ExecutionContext): Future[Unit]
  def removeService(id: String)(implicit ec: ExecutionContext): Future[Unit]
  def changeDomain(id: String, domain: String)(implicit ec: ExecutionContext): Future[Unit]
  def addTarget(id: String, target: Target)(implicit ec: ExecutionContext): Future[Unit]
  def removeTarget(id: String, target: Target)(implicit ec: ExecutionContext): Future[Unit]
  def addApiKey(serviceId: String, apiKey: ApiKey[K])(implicit ec: ExecutionContext): Future[Unit]
  def updateApiKey(serviceId: String, apiKey: ApiKey[K])(implicit ec: ExecutionContext): Future[Unit]
  def removeApiKey(serviceId: String, clientId: String)(implicit ec: ExecutionContext): Future[Unit]
  def enableApiKey(serviceId: String, clientId: String)(implicit ec: ExecutionContext): Future[Unit]
  def disabledApiKey(serviceId: String, clientId: String)(implicit ec: ExecutionContext): Future[Unit]
  def toggleApiKey(serviceId: String, clientId: String)(implicit ec: ExecutionContext): Future[Unit]
  def resetApiKey(serviceId: String, clientId: String)(implicit ec: ExecutionContext): Future[Unit]
  def updateClientConfig(serviceId: String, config: ClientConfig)(implicit ec: ExecutionContext): Future[Unit]
  def addAdditionalHeader(serviceId: String, name: String, value: String)(implicit ec: ExecutionContext): Future[Unit]
  def removeAdditionalHeader(serviceId: String, name: String)(implicit ec: ExecutionContext): Future[Unit]
  def updateAdditionalHeader(serviceId: String, name: String, value: String)(
      implicit ec: ExecutionContext
  ): Future[Unit]
  def addMatchingHeader(serviceId: String, name: String, value: String)(implicit ec: ExecutionContext): Future[Unit]
  def removeMatchingHeader(serviceId: String, name: String)(implicit ec: ExecutionContext): Future[Unit]
  def updateMatchingHeader(serviceId: String, name: String, value: String)(implicit ec: ExecutionContext): Future[Unit]
  def updateTargetRoot(serviceId: String, root: String)(implicit ec: ExecutionContext): Future[Unit]
  def addPublicPattern(serviceId: String, pattern: String)(implicit ec: ExecutionContext): Future[Unit]
  def removePublicPattern(serviceId: String, pattern: String)(implicit ec: ExecutionContext): Future[Unit]
  def addPrivatePattern(serviceId: String, pattern: String)(implicit ec: ExecutionContext): Future[Unit]
  def removePrivatePattern(serviceId: String, pattern: String)(implicit ec: ExecutionContext): Future[Unit]
  def updateRoot(serviceId: String, root: String)(implicit ec: ExecutionContext): Future[Unit]
  def removeRoot(serviceId: String)(implicit ec: ExecutionContext): Future[Unit]
}

// can handle construction mode, maintenance mode
trait PreconditionModule[A, K] extends Module[A, K] {
  def validatePreconditions(ctx: ReqContext, service: Service[A, K])(
      implicit ec: ExecutionContext
  ): Future[Either[HttpResponse, Unit]]
}

class CombinedPreconditionModule[A, K](modules: Seq[PreconditionModule[A, K]]) extends PreconditionModule[A, K] {

  override def id: String = "CombinedPreconditionModule"

  def validatePreconditions(ctx: ReqContext, service: Service[A, K])(
      implicit ec: ExecutionContext
  ): Future[Either[HttpResponse, Unit]] = {
    Future.sequence(modules.map(_.validatePreconditions(ctx, service))).map { results =>
      results.find(_.isLeft).getOrElse(results.find(_.isRight).get)
    }
  }
}

trait BeforeAfterModule[A, K] extends Module[A, K] {
  def beforeRequest(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit]
  def afterRequestSuccess(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit]
  def afterRequestWebSocketSuccess(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit]
  def afterRequestError(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit]
  def afterRequestEnd(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit]
}

class CombinedBeforeAfterModule[A, K](modules: Seq[BeforeAfterModule[A, K]]) extends BeforeAfterModule[A, K] {

  override def id: String = "CombinedBeforeAfterModule"

  def beforeRequest(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit] =
    Future.sequence(modules.map(m => m.beforeRequest(ctx))).map(_ => ())
  def afterRequestSuccess(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit] =
    Future.sequence(modules.map(m => m.afterRequestSuccess(ctx))).map(_ => ())
  def afterRequestWebSocketSuccess(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit] =
    Future.sequence(modules.map(m => m.afterRequestWebSocketSuccess(ctx))).map(_ => ())
  def afterRequestError(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit] =
    Future.sequence(modules.map(m => m.afterRequestError(ctx))).map(_ => ())
  def afterRequestEnd(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit] =
    Future.sequence(modules.map(m => m.afterRequestEnd(ctx))).map(_ => ())
}

trait ServiceFinderModule[A, K] extends Module[A, K] {
  def findService(ctx: ReqContext, host: String, path: Uri.Path, headers: Map[String, HttpHeader])(
      implicit ec: ExecutionContext
  ): Future[Option[Service[A, K]]]
}

// can handle pass by api, pass by auth0, throttling, gobal throtthling, etc ...
trait ServiceAccessModule[A, K] extends Module[A, K] {
  def access(ctx: ReqContext, service: Service[A, K])(implicit ec: ExecutionContext): Future[WithApiKeyOrNot]
}

class CombinedServiceAccessModule[A, K](modules: Seq[ServiceAccessModule[A, K]]) extends ServiceAccessModule[A, K] {

  override def id: String = "CombinedServiceAccessModule"

  def access(ctx: ReqContext, service: Service[A, K])(implicit ec: ExecutionContext): Future[WithApiKeyOrNot] = {
    Future.sequence(modules.map(_.access(ctx, service))).map { results =>
      results.find(_.isNoApiKey).getOrElse(results.find(!_.isNoApiKey).get)
    }
  }
}

// can handle headers additions, like JWT header, request id, API quotas, etc ...
trait HeadersInTransformationModule[A, K] extends Module[A, K] {
  def transform(ctx: ReqContext,
                host: String,
                service: Service[A, K],
                target: Target,
                waon: WithApiKeyOrNot,
                headers: List[HttpHeader])(implicit ec: ExecutionContext): Future[List[HttpHeader]]
}

class CombinedHeadersInTransformationModule[A, K](modules: Seq[HeadersInTransformationModule[A, K]])
    extends HeadersInTransformationModule[A, K] {

  override def id: String = "CombinedHeadersInTransformationModule"

  def transform(ctx: ReqContext,
                host: String,
                service: Service[A, K],
                target: Target,
                waon: WithApiKeyOrNot,
                headers: List[HttpHeader])(implicit ec: ExecutionContext): Future[List[HttpHeader]] = {
    Future.sequence(modules.map(_.transform(ctx, host, service, target, waon, headers))).map { results =>
      results.foldLeft(List.empty[HttpHeader])(
        (seq, part) => seq ++ part
      )
    }
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
                headers: List[HttpHeader])(implicit ec: ExecutionContext): Future[List[HttpHeader]]
}

class CombinedHeadersOutTransformationModule[A, K](modules: Seq[HeadersOutTransformationModule[A, K]])
    extends HeadersOutTransformationModule[A, K] {

  override def id: String = "CombinedHeadersOutTransformationModule"

  def transform(ctx: ReqContext,
                host: String,
                service: Service[A, K],
                target: Target,
                waon: WithApiKeyOrNot,
                proxyLatency: Long,
                targetLatency: Long,
                headers: List[HttpHeader])(implicit ec: ExecutionContext): Future[List[HttpHeader]] = {
    Future
      .sequence(modules.map(_.transform(ctx, host, service, target, waon, proxyLatency, targetLatency, headers)))
      .map { results =>
        results.foldLeft(List.empty[HttpHeader])(
          (seq, part) => seq ++ part
        )
      }
  }
}

// can handle custom template errors
trait ErrorRendererModule[A, K] extends Module[A, K] {
  def render(ctx: ReqContext, status: Int, message: String, service: Option[Service[A, K]])(
      implicit ec: ExecutionContext
  ): Future[HttpResponse]
}

// can handle canary mode
trait TargetSetChooserModule[A, K] extends Module[A, K] {
  def choose(ctx: ReqContext, service: Service[A, K])(implicit ec: ExecutionContext): Future[Seq[Target]]
}
