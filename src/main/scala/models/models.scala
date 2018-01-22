package models

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.{HttpProtocol, HttpProtocols}
import io.circe.Decoder.Result
import io.circe._
import io.circe.generic.semiauto._

import scala.concurrent.duration._

case class Target(url: String, weight: Int = 1, protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`) {
  lazy val (scheme, host, port) = {
    url.split("://|:").toList match {
      case scheme :: host :: port :: Nil => (scheme, host, port.toInt)
      case _                             => throw new RuntimeException(s"Bad target: $url")
    }
  }
}

case class Command(action: String, domain: String, target: String)

object Command {
  val decoder: Decoder[Command] = deriveDecoder[Command]
}

case class ClientConfig(retry: Int = 3,
                        maxFailures: Int = 5,
                        callTimeout: FiniteDuration = 30.seconds,
                        resetTimeout: FiniteDuration = 10.seconds)

case class ApiKey(clientId: String, clientSecret: String, name: String, enabled: Boolean)

case class Service(id: String,
                   domain: String,
                   targets: Seq[Target] = Seq.empty,
                   apiKeys: Seq[ApiKey] = Seq.empty,
                   clientConfig: ClientConfig = ClientConfig(),
                   headers: Map[String, String] = Map.empty,
                   targetRoot: String = "",
                   root: Option[String] = None,
                   publicPatterns: Seq[String] = Seq.empty,
                   privatePatterns: Seq[String] = Seq.empty)

case class HttpConfig(
    httpPort: Int = 8080,
    httpsPort: Int = 8443,
    listenOn: String = "0.0.0.0",
    keyStoreType: String = "PKCS12",
    certPath: Option[String] = None,
    certPass: Option[String] = None
)
case class ApiConfig(
    httpPort: Int = 9080,
    httpsPort: Int = 9443,
    listenOn: String = "127.0.0.1",
    keyStoreType: String = "PKCS12",
    certPath: Option[String] = None,
    certPass: Option[String] = None
)

case class ProxyConfig(
    http: HttpConfig = HttpConfig(),
    api: ApiConfig = ApiConfig(),
    services: Seq[Service] = Seq.empty,
    logConfigPath: Option[String] = None,
    statePath: Option[String] = None
) {
  def pretty: String = Encoders.ProxyConfigEncoder.apply(this).spaces2
}

object Decoders {
  implicit val FiniteDurationDecoder: Decoder[FiniteDuration] = new Decoder[FiniteDuration] {
    override def apply(c: HCursor): Result[FiniteDuration] =
      c.as[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS))
  }
  implicit val HttpProtocolDecoder: Decoder[HttpProtocol] = new Decoder[HttpProtocol] {
    override def apply(c: HCursor): Result[HttpProtocol] = c.as[String].map(v => HttpProtocol(v))
  }
  implicit val TargetDecoder: Decoder[Target]             = deriveDecoder[Target]
  implicit val ClientConfigDecoder: Decoder[ClientConfig] = deriveDecoder[ClientConfig]
  implicit val ApiKeyDecoder: Decoder[ApiKey]             = deriveDecoder[ApiKey]
  implicit val ServiceDecoder: Decoder[Service]           = deriveDecoder[Service]
  implicit val HttpConfigDecoder: Decoder[HttpConfig]     = deriveDecoder[HttpConfig]
  implicit val ApiConfigDecoder: Decoder[ApiConfig]       = deriveDecoder[ApiConfig]
  implicit val ProxyConfigDecoder: Decoder[ProxyConfig]   = deriveDecoder[ProxyConfig]
}

object Encoders {
  implicit val FiniteDurationEncoder: Encoder[FiniteDuration] = new Encoder[FiniteDuration] {
    override def apply(a: FiniteDuration): Json = Json.fromLong(a.toMillis)
  }
  implicit val HttpProtocolEncoder: Encoder[HttpProtocol] = new Encoder[HttpProtocol] {
    override def apply(a: HttpProtocol): Json = Json.fromString(a.value)
  }
  implicit val TargetEncoder: Encoder[Target]             = deriveEncoder[Target]
  implicit val ClientConfigEncoder: Encoder[ClientConfig] = deriveEncoder[ClientConfig]
  implicit val ApiKeyEncoder: Encoder[ApiKey]             = deriveEncoder[ApiKey]
  implicit val ServiceEncoder: Encoder[Service]           = deriveEncoder[Service]
  implicit val HttpConfigEncoder: Encoder[HttpConfig]     = deriveEncoder[HttpConfig]
  implicit val ApiConfigEncoder: Encoder[ApiConfig]       = deriveEncoder[ApiConfig]
  implicit val ProxyConfigEncoder: Encoder[ProxyConfig]   = deriveEncoder[ProxyConfig]
}

trait WithApiKeyOrNot
case object NoApiKey                  extends WithApiKeyOrNot
case object BadApiKey                 extends WithApiKeyOrNot
case class WithApiKey(apiKey: ApiKey) extends WithApiKeyOrNot

trait CallRestriction
case object PublicCall  extends CallRestriction
case object PrivateCall extends CallRestriction

case class ConfigError(message: String)
