package io.heimdallr.modules

import java.util.Base64

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.heimdallr.models._
import io.heimdallr.util.Implicits._

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
  val modules: ModulesConfig[NoExtension, NoExtension] = ModulesConfig(
    Seq(
      new DefaultPreconditionModule(),
      new DefaultServiceAccessModule(),
      new DefaultHeadersInTransformationModule(),
      new DefaultHeadersOutTransformationModule(),
      new DefaultErrorRendererModule(),
      new DefaultTargetSetChooserModule(),
    )
  )
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
