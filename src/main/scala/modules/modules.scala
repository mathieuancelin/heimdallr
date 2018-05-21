package io.heimdallr.modules

import java.util.Base64

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.heimdallr.models._
import io.heimdallr.store.Store
import io.heimdallr.util.Implicits._
import io.heimdallr.util.RegexPool

import scala.util.Try

case class NoExtension()

object NoExtension extends Extensions[NoExtension, NoExtension] {

  val singleton = NoExtension()

  implicit val NoExtensionDecoder: Decoder[NoExtension] = (c: HCursor) => Right(NoExtension())

  implicit val NoExtensionEncoder: Encoder[NoExtension] = (a: NoExtension) => Json.Null

  override def serviceExtensionEncoder: Encoder[NoExtension] = NoExtensionEncoder

  override def serviceExtensionDecoder: Decoder[NoExtension] = NoExtensionDecoder

  override def apiKeyExtensionEncoder: Encoder[NoExtension] = NoExtensionEncoder

  override def apiKeyExtensionDecoder: Decoder[NoExtension] = NoExtensionDecoder
}

object DefaultModules extends Modules[NoExtension, NoExtension] {
  val modules: ModulesConfig[NoExtension, NoExtension] = new ModulesConfig[NoExtension, NoExtension] {
    override def PreconditionModules: Seq[PreconditionModule[NoExtension, NoExtension]] =
      Seq(new DefaultPreconditionModule())
    override def ServiceAccessModules: Seq[ServiceAccessModule[NoExtension, NoExtension]] =
      Seq(new DefaultServiceAccessModule())
    override def HeadersInTransformationModules: Seq[HeadersInTransformationModule[NoExtension, NoExtension]] =
      Seq(new DefaultHeadersInTransformationModule())
    override def HeadersOutTransformationModules: Seq[HeadersOutTransformationModule[NoExtension, NoExtension]] =
      Seq(new DefaultHeadersOutTransformationModule())
    override def ErrorRendererModule: ErrorRendererModule[NoExtension, NoExtension] = new DefaultErrorRendererModule()
    override def TargetSetChooserModule: TargetSetChooserModule[NoExtension, NoExtension] =
      new DefaultTargetSetChooserModule()
    override def ServiceFinderModule: ServiceFinderModule[NoExtension, NoExtension]   = new DefaultServiceFinderModule()
    override def BeforeAfterModules: Seq[BeforeAfterModule[NoExtension, NoExtension]] = Seq.empty
  }
  val extensions: Extensions[NoExtension, NoExtension] = NoExtension
}

class DefaultPreconditionModule extends PreconditionModule[NoExtension, NoExtension] {

  override def id: String = "DefaultPreconditionModule"

  override def validatePreconditions(ctx: ReqContext,
                                     service: Service[NoExtension, NoExtension]): Either[HttpResponse, Unit] = {
    if (service.enabled) {
      Right(())
    } else {
      Left(
        HttpResponse(
          404,
          entity = HttpEntity(
            ContentTypes.`application/json`,
            Json.obj("error" -> Json.fromString("service not found"), "reqId" -> Json.fromString(ctx.reqId)).noSpaces
          )
        )
      )
    }
  }
}

class DefaultServiceAccessModule extends ServiceAccessModule[NoExtension, NoExtension] {

  val authHeaderName = "Proxy-Authorization"

  val decoder = Base64.getUrlDecoder

  override def id: String = "DefaultServiceAccessModule"

  override def access(ctx: ReqContext, service: Service[NoExtension, NoExtension]): WithApiKeyOrNot = {
    ctx.request.getHeader(authHeaderName).asOption.flatMap { header =>
      Try {
        val value = header.value()
        if (value.startsWith("Basic")) {
          val token = value.replace("Basic ", "")
          new String(decoder.decode(token), "UTF-8").split(":").toList match {
            case clientId :: clientSecret :: Nil =>
              service.apiKeys
                .filter(a => a.enabled && a.clientId == clientId && a.clientSecret == clientSecret)
                .lastOption match {
                case Some(apiKey) => WithApiKey(apiKey)
                case None         => BadApiKey
              }
            case _ => NoApiKey
          }
        } else if (value.startsWith("Bearer")) {
          val token    = value.replace("Bearer ", "")
          val JWTToken = JWT.decode(token)
          val issuer   = JWTToken.getIssuer
          service.apiKeys.find(apk => apk.enabled && apk.clientId == issuer) match {
            case Some(key) =>
              val algorithm = Algorithm.HMAC512(key.clientSecret)
              val verifier  = JWT.require(algorithm).withIssuer(JWTToken.getIssuer).build
              verifier.verify(token)
              WithApiKey(key)
            case None => BadApiKey
          }
        } else {
          NoApiKey
        }
      }.toOption
    } match {
      case Some(withApiKeyOrNot) => withApiKeyOrNot
      case None                  => NoApiKey
    }
  }
}

class DefaultHeadersInTransformationModule extends HeadersInTransformationModule[NoExtension, NoExtension] {

  val authHeaderName = "Proxy-Authorization"

  override def id: String = "DefaultHeadersInTransformationModule"

  override def transform(ctx: ReqContext,
                         host: String,
                         service: Service[NoExtension, NoExtension],
                         target: Target,
                         waon: WithApiKeyOrNot,
                         headers: List[HttpHeader]): List[HttpHeader] = {
    headers.filterNot(_.name() == authHeaderName) ++
    service.additionalHeaders.toList.map(t => RawHeader(t._1, t._2)) :+
    RawHeader("X-Request-Id", ctx.reqId) :+
    RawHeader("X-Fowarded-Host", host) :+
    RawHeader("X-Fowarded-Scheme", ctx.request.uri.scheme)
  }
}

class DefaultHeadersOutTransformationModule extends HeadersOutTransformationModule[NoExtension, NoExtension] {

  override def id: String = "DefaultHeadersOutTransformationModule"

  override def transform(ctx: ReqContext,
                         host: String,
                         service: Service[NoExtension, NoExtension],
                         target: Target,
                         waon: WithApiKeyOrNot,
                         proxyLatency: Long,
                         targetLatency: Long,
                         headers: List[HttpHeader]): List[HttpHeader] = {
    headers :+
    RawHeader("X-Proxy-Latency", proxyLatency.toString) :+
    RawHeader("X-Target-Latency", targetLatency.toString)
  }
}

class DefaultErrorRendererModule extends ErrorRendererModule[NoExtension, NoExtension] {

  override def id: String = "DefaultErrorRendererModule"

  override def render(ctx: ReqContext,
                      status: Int,
                      message: String,
                      service: Option[Service[NoExtension, NoExtension]]): HttpResponse = HttpResponse(
    status,
    entity = HttpEntity(ContentTypes.`application/json`,
                        Json.obj("error" -> Json.fromString(message), "reqId" -> Json.fromString(ctx.reqId)).noSpaces)
  )
}

class DefaultTargetSetChooserModule extends TargetSetChooserModule[NoExtension, NoExtension] {

  override def id: String = "DefaultTargetSetChooserModule"

  override def choose(ctx: ReqContext, service: Service[NoExtension, NoExtension]): Seq[Target] =
    service.targets
}

class DefaultServiceFinderModule extends ServiceFinderModule[NoExtension, NoExtension] {

  override def id: String = "DefaultServiceFinderModule"

  override def findService(ctx: ReqContext,
                           store: Store[NoExtension, NoExtension],
                           host: String,
                           path: Uri.Path,
                           headers: Map[String, HttpHeader]): Option[Service[NoExtension, NoExtension]] = {
    val uri = path.toString()

    @inline
    def findSubService(services: Seq[Service[NoExtension, NoExtension]]): Option[Service[NoExtension, NoExtension]] = {
      val sortedServices = services
        .filter(_.enabled)
        .sortWith(
          (a, _) =>
            if (a.root.isDefined && a.matchingHeaders.nonEmpty) true
            else if (a.root.isEmpty && a.matchingHeaders.nonEmpty) true
            else a.root.isDefined
        )
      var found: Option[Service[NoExtension, NoExtension]] = None
      var index                                            = 0
      while (found.isEmpty && index < sortedServices.size) {
        val s = sortedServices(index)
        index = index + 1
        if (s.root.isDefined) {
          if (uri.startsWith(s.root.get)) {
            if (s.matchingHeaders.nonEmpty) {
              if (s.matchingHeaders.toSeq.forall(t => headers.get(t._1).exists(_.value() == t._2))) {
                found = Some(s)
              }
            } else {
              found = Some(s)
            }
          }
        } else {
          if (s.matchingHeaders.nonEmpty) {
            if (s.matchingHeaders.toSeq.forall(t => headers.get(t._1).exists(_.value() == t._2))) {
              found = Some(s)
            }
          } else {
            found = Some(s)
          }
        }
      }
      found
    }

    store.get().get(host).flatMap(findSubService) match {
      case s @ Some(_) => s
      case None => {
        val state = store.get()
        state.keys.find(k => RegexPool(k).matches(host)).flatMap(k => state.get(k)) match {
          case Some(services) => findSubService(services)
          case None           => None
        }
      }
    }
  }
}
