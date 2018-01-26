package modules

import java.util.Base64

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.circe.Json
import models._
import util.Implicits._

import scala.util.Try

object Modules {
  val defaultModules: ModulesConfig = ModulesConfig()
}

class DefaultPreconditionModule extends PreconditionModule {

  override def id: String = "DefaultPreconditionModule"

  override def validatePreconditions(reqId: String, service: Service, request: HttpRequest): Either[HttpResponse, Unit] = {
    if (service.enabled) {
      Right(())
    } else {
      Left(HttpResponse(
        404,
        entity = HttpEntity(ContentTypes.`application/json`, Json.obj("error" -> Json.fromString("service not found"), "reqId" -> Json.fromString(reqId)).noSpaces)
      ))
    }
  }
}

class DefaultServiceAccessModule extends ServiceAccessModule {

  val authHeaderName = "Proxy-Authorization"

  val decoder = Base64.getUrlDecoder

  override def id: String = "DefaultServiceAccessModule"

  override def access(reqId: String, service: Service, request: HttpRequest): WithApiKeyOrNot = {
    request.getHeader(authHeaderName).asOption.flatMap { header =>
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

class DefaultHeadersTransformationModule extends HeadersTransformationModule {

  override def id: String = "DefaultHeadersTransformationModule"

  override def transform(reqId: String, host: String, service: Service, target: Target, request: HttpRequest, waon: WithApiKeyOrNot, headers: Seq[HttpHeader]): Seq[HttpHeader] = {
    service.additionalHeaders.toSeq.map(t => RawHeader(t._1, t._2)) :+
      RawHeader("X-Request-Id", reqId) :+
      RawHeader("X-Fowarded-Host", host) :+
      RawHeader("X-Fowarded-Scheme", request.uri.scheme)
  }
}

class DefaultErrorRendererModule extends ErrorRendererModule {

  override def id: String = "DefaultErrorRendererModule"

  override def render(reqId: String, status: Int, message: String, service: Service, request: HttpRequest): HttpResponse = HttpResponse(
    status,
    entity = HttpEntity(ContentTypes.`application/json`, Json.obj("error" -> Json.fromString(message), "reqId" -> Json.fromString(reqId)).noSpaces)
  )
}

class DefaultTargetSetChooserModule extends TargetSetChooserModule {

  override def id: String = "DefaultTargetSetChooserModule"

  override def choose(reqId: String, service: Service, request: HttpRequest): Seq[Target] = service.targets
}

