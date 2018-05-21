package io.heimdallr.models

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model._
import io.circe.Decoder.Result
import io.circe._
import io.circe.generic.semiauto._
import io.heimdallr.modules._
import io.heimdallr.store.Store
import io.heimdallr.util.IdGenerator

import scala.concurrent.duration._

case class Target(url: String, weight: Int = 1, protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`) {
  lazy val (scheme, host, port) = {
    url.split("://|:").toList match {
      case scheme :: host :: port :: Nil => (scheme, host, port.toInt)
      case _                             => throw new RuntimeException(s"Bad target: $url")
    }
  }
}

case class ClientConfig(retry: Int = 3,
                        maxFailures: Int = 5,
                        callTimeout: FiniteDuration = 30.seconds,
                        resetTimeout: FiniteDuration = 10.seconds)

case class ApiKey[K](clientId: String,
                     clientSecret: String,
                     name: String,
                     enabled: Boolean,
                     metadata: Map[String, String] = Map.empty,
                     extension: Option[K] = None)

case class Service[A, K](id: String,
                         domain: String,
                         enabled: Boolean = true,
                         targets: Seq[Target] = Seq.empty,
                         apiKeys: Seq[ApiKey[K]] = Seq.empty,
                         clientConfig: ClientConfig = ClientConfig(),
                         additionalHeaders: Map[String, String] = Map.empty,
                         matchingHeaders: Map[String, String] = Map.empty,
                         targetRoot: String = "",
                         root: Option[String] = None,
                         publicPatterns: Set[String] = Set.empty,
                         privatePatterns: Set[String] = Set.empty,
                         metadata: Map[String, String] = Map.empty,
                         extension: Option[A] = None)

case class OtoroshiStateConfig(url: String, headers: Map[String, String], pollEvery: FiniteDuration = 10.seconds)
case class LocalStateConfig(path: String, writeEvery: FiniteDuration = 10.seconds)
case class RemoteStateConfig(url: String, headers: Map[String, String], pollEvery: FiniteDuration = 10.seconds)

case class StatsdConfig(datadog: Boolean, host: String, port: Int)
case class StatsdEventClose()
case class StatsdEvent(action: String,
                       name: String,
                       value: Double,
                       strValue: String,
                       sampleRate: Double,
                       bypassSampler: Boolean,
                       config: StatsdConfig)

case class StateConfig(
    local: Option[LocalStateConfig] = None,
    remote: Option[RemoteStateConfig] = None,
    otoroshi: Option[OtoroshiStateConfig] = None,
) {
  def isRemote   = remote.isDefined && local.isEmpty && otoroshi.isEmpty
  def isLocal    = local.isDefined && remote.isEmpty && otoroshi.isEmpty
  def isOtoroshi = otoroshi.isDefined && local.isEmpty && remote.isEmpty
}

case class HttpConfig(
    httpPort: Int = 8080,
    httpsPort: Int = 8443,
    listenOn: String = "0.0.0.0",
    keyStoreType: String = "PKCS12",
    certPath: Option[String] = None,
    keyPath: Option[String] = None,
    certPass: Option[String] = None
)

case class ApiConfig(
    httpPort: Int = 9080,
    httpsPort: Int = 9443,
    listenOn: String = "127.0.0.1",
    keyStoreType: String = "PKCS12",
    certPath: Option[String] = None,
    keyPath: Option[String] = None,
    certPass: Option[String] = None,
    enabled: Boolean = true
)

case class ModulesConfig[A, K](modules: Seq[_ <: Module[A, K]] = Seq.empty) {
  lazy val PreconditionModules: Seq[PreconditionModule[A, K]] = modules.collect {
    case m: PreconditionModule[A, K] => m
  }
  lazy val ServiceAccessModules: Seq[ServiceAccessModule[A, K]] = modules.collect {
    case m: ServiceAccessModule[A, K] => m
  }
  lazy val HeadersInTransformationModules: Seq[HeadersInTransformationModule[A, K]] = modules.collect {
    case m: HeadersInTransformationModule[A, K] => m
  }
  lazy val HeadersOutTransformationModules: Seq[HeadersOutTransformationModule[A, K]] = modules.collect {
    case m: HeadersOutTransformationModule[A, K] => m
  }
  lazy val ErrorRendererModules: Seq[ErrorRendererModule[A, K]] = modules.collect {
    case m: ErrorRendererModule[A, K] => m
  }
  lazy val TargetSetChooserModules: Seq[TargetSetChooserModule[A, K]] = modules.collect {
    case m: TargetSetChooserModule[A, K] => m
  }
}

case class LoggersConfig(level: String, configPath: Option[String] = None)

case class ProxyConfig[A, K](
    http: HttpConfig = HttpConfig(),
    api: ApiConfig = ApiConfig(),
    services: Seq[Service[A, K]] = Seq.empty,
    state: Option[StateConfig] = None,
    loggers: LoggersConfig = LoggersConfig("INFO"),
    statsd: Option[StatsdConfig] = None
) {
  def pretty(implicit encoders: Encoders[A, K]): String = encoders.ProxyConfigEncoder.apply(this).spaces2
}

class Decoders[A, K](extensions: Extensions[A, K]) {

  implicit val serviceExtensionDecoder = extensions.serviceExtensionDecoder
  implicit val apiKeyExtensionDecoder  = extensions.apiKeyExtensionDecoder

  implicit val FiniteDurationDecoder: Decoder[FiniteDuration] = new Decoder[FiniteDuration] {
    override def apply(c: HCursor): Result[FiniteDuration] =
      c.as[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS))
  }
  implicit val HttpProtocolDecoder: Decoder[HttpProtocol] = new Decoder[HttpProtocol] {
    override def apply(c: HCursor): Result[HttpProtocol] = c.as[String].map(v => HttpProtocol(v))
  }
  implicit val StatsdConfigDecoder: Decoder[StatsdConfig]               = deriveDecoder[StatsdConfig]
  implicit val OtoroshiStateConfigDecoder: Decoder[OtoroshiStateConfig] = deriveDecoder[OtoroshiStateConfig]
  implicit val LocalStateConfigDecoder: Decoder[LocalStateConfig]       = deriveDecoder[LocalStateConfig]
  implicit val RemoteStateConfigDecoder: Decoder[RemoteStateConfig]     = deriveDecoder[RemoteStateConfig]
  implicit val StateConfigDecoder: Decoder[StateConfig]                 = deriveDecoder[StateConfig]
  implicit val TargetDecoder: Decoder[Target]                           = deriveDecoder[Target]
  implicit val ClientConfigDecoder: Decoder[ClientConfig]               = deriveDecoder[ClientConfig]
  implicit val ApiKeyDecoder: Decoder[ApiKey[K]]                        = deriveDecoder[ApiKey[K]]
  implicit val ServiceDecoder: Decoder[Service[A, K]]                   = deriveDecoder[Service[A, K]]
  implicit val SeqOfServiceDecoder: Decoder[Seq[Service[A, K]]]         = Decoder.decodeSeq(ServiceDecoder)
  implicit val HttpConfigDecoder: Decoder[HttpConfig]                   = deriveDecoder[HttpConfig]
  implicit val ApiConfigDecoder: Decoder[ApiConfig]                     = deriveDecoder[ApiConfig]
  implicit val LoggersConfigDecoder: Decoder[LoggersConfig]             = deriveDecoder[LoggersConfig]
  implicit val ProxyConfigDecoder: Decoder[ProxyConfig[A, K]]           = deriveDecoder[ProxyConfig[A, K]]
}

class Encoders[A, K](extensions: Extensions[A, K]) {

  implicit val serviceExtensionEncoder = extensions.serviceExtensionEncoder
  implicit val apiKeyExtensionEncoder  = extensions.apiKeyExtensionEncoder

  implicit val FiniteDurationEncoder: Encoder[FiniteDuration] = new Encoder[FiniteDuration] {
    override def apply(a: FiniteDuration): Json = Json.fromLong(a.toMillis)
  }
  implicit val HttpProtocolEncoder: Encoder[HttpProtocol] = new Encoder[HttpProtocol] {
    override def apply(a: HttpProtocol): Json = Json.fromString(a.value)
  }
  implicit val StatsdConfigEncoder: Encoder[StatsdConfig]               = deriveEncoder[StatsdConfig]
  implicit val OtoroshiStateConfigEncoder: Encoder[OtoroshiStateConfig] = deriveEncoder[OtoroshiStateConfig]
  implicit val LocalStateConfigEncoder: Encoder[LocalStateConfig]       = deriveEncoder[LocalStateConfig]
  implicit val RemoteStateConfigEncoder: Encoder[RemoteStateConfig]     = deriveEncoder[RemoteStateConfig]
  implicit val StateConfigEncoder: Encoder[StateConfig]                 = deriveEncoder[StateConfig]
  implicit val TargetEncoder: Encoder[Target]                           = deriveEncoder[Target]
  implicit val ClientConfigEncoder: Encoder[ClientConfig]               = deriveEncoder[ClientConfig]
  implicit val ApiKeyEncoder: Encoder[ApiKey[K]]                        = deriveEncoder[ApiKey[K]]
  implicit val ServiceEncoder: Encoder[Service[A, K]]                   = deriveEncoder[Service[A, K]]
  implicit val SeqOfServiceEncoder: Encoder[Seq[Service[A, K]]]         = Encoder.encodeSeq(ServiceEncoder)
  implicit val HttpConfigEncoder: Encoder[HttpConfig]                   = deriveEncoder[HttpConfig]
  implicit val ApiConfigEncoder: Encoder[ApiConfig]                     = deriveEncoder[ApiConfig]
  implicit val LoggersConfigEncoder: Encoder[LoggersConfig]             = deriveEncoder[LoggersConfig]
  implicit val ProxyConfigEncoder: Encoder[ProxyConfig[A, K]]           = deriveEncoder[ProxyConfig[A, K]]
}

sealed trait WithApiKeyOrNot
case object NoApiKey                        extends WithApiKeyOrNot
case object BadApiKey                       extends WithApiKeyOrNot
case class WithApiKey[K](apiKey: ApiKey[K]) extends WithApiKeyOrNot

sealed trait CallRestriction
case object PublicCall  extends CallRestriction
case object PrivateCall extends CallRestriction

case class ConfigError(message: String)

trait Command[A, K] {
  def command: String
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]]
  def applyModification(store: Store[A, K], encoders: Encoders[A, K]): Json = {
    store.modify(s => modify(s.values.flatten.toSeq).groupBy(_.domain))
    Json.obj("result" -> Json.fromString("command applied"))
  }
}

case class NothingCommand[A, K](command: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = state
}

case class GetStateCommand[A, K](command: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = state
  override def applyModification(store: Store[A, K], encoders: Encoders[A, K]): Json = {
    val seq = store.get().values.flatten.toSeq
    Json.obj("state" -> encoders.SeqOfServiceEncoder(seq))
  }
}

case class GetServiceCommand[A, K](command: String, serviceId: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = state
  override def applyModification(store: Store[A, K], encoders: Encoders[A, K]): Json = {
    store.get().values.flatten.toSeq.find(_.id == serviceId) match {
      case Some(service) => encoders.ServiceEncoder(service)
      case None          => Json.obj("error" -> Json.fromString("not found"))
    }
  }
}

case class GetMetricsCommand[A, K](command: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = state
  override def applyModification(store: Store[A, K], encoders: Encoders[A, K]): Json = {
    Json.obj("metrics" -> Json.obj())
  }
}

case class LoadStateCommand[A, K](command: String, serviceId: String, services: Seq[Service[A, K]])
    extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = services
}

case class AddServiceCommand[A, K](command: String, service: Service[A, K]) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = state :+ service
}

case class UpdateServiceCommand[A, K](command: String, serviceId: String, updatedService: Service[A, K])
    extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        updatedService.copy(id = serviceId)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class RemoveServiceCommand[A, K](command: String, serviceId: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = state.filterNot(_.id == serviceId)
}

case class ChangeDomainCommand[A, K](command: String, serviceId: String, domain: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(domain = domain)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class AddTargetCommand[A, K](command: String, serviceId: String, target: Target) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(targets = service.targets :+ target)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class RemoveTargetCommand[A, K](command: String, serviceId: String, target: Target) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(targets = service.targets.filterNot(_ == target))
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class AddApiKeyCommand[A, K](command: String, serviceId: String, apiKey: ApiKey[K]) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(apiKeys = service.apiKeys :+ apiKey)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class UpdateApiKeyCommand[A, K](command: String, serviceId: String, apiKey: ApiKey[K]) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(apiKeys = service.apiKeys.filterNot(_.clientId == apiKey.clientId) :+ apiKey)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class RemoveApiKeyCommand[A, K](command: String, serviceId: String, apiKey: ApiKey[K]) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(apiKeys = service.apiKeys.filterNot(_.clientId == apiKey.clientId))
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class EnableApiKeyCommand[A, K](command: String, serviceId: String, clientId: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        val opt = service.apiKeys.find(_.clientId == clientId).map(ak => ak.copy(enabled = true))
        service.copy(apiKeys = service.apiKeys.filterNot(_.clientId == clientId) ++ opt)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class DisabledApiKeyCommand[A, K](command: String, serviceId: String, clientId: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        val opt = service.apiKeys.find(_.clientId == clientId).map(ak => ak.copy(enabled = false))
        service.copy(apiKeys = service.apiKeys.filterNot(_.clientId == clientId) ++ opt)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class ToggleApiKeyCommand[A, K](command: String, serviceId: String, clientId: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        val opt = service.apiKeys.find(_.clientId == clientId).map(ak => ak.copy(enabled = !ak.enabled))
        service.copy(apiKeys = service.apiKeys.filterNot(_.clientId == clientId) ++ opt)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class ResetApiKeyCommand[A, K](command: String, serviceId: String, clientId: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        val opt = service.apiKeys.find(_.clientId == clientId).map(ak => ak.copy(clientSecret = IdGenerator.token))
        service.copy(apiKeys = service.apiKeys.filterNot(_.clientId == clientId) ++ opt)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class UpdateClientConfigCommand[A, K](command: String, serviceId: String, config: ClientConfig)
    extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(clientConfig = config)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class AddAdditionalHeaderCommand[A, K](command: String, serviceId: String, name: String, value: String)
    extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(additionalHeaders = service.additionalHeaders + (name -> value))
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class RemoveAdditionalHeaderCommand[A, K](command: String, serviceId: String, name: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(additionalHeaders = service.additionalHeaders - name)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class UpdateAdditionalHeaderCommand[A, K](command: String, serviceId: String, name: String, value: String)
    extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(additionalHeaders = service.additionalHeaders + (name -> value))
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class AddMatchingHeaderCommand[A, K](command: String, serviceId: String, name: String, value: String)
    extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(matchingHeaders = service.matchingHeaders + (name -> value))
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class RemoveMatchingHeaderCommand[A, K](command: String, serviceId: String, name: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(matchingHeaders = service.matchingHeaders - name)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class UpdateMatchingHeaderCommand[A, K](command: String, serviceId: String, name: String, value: String)
    extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(matchingHeaders = service.matchingHeaders + (name -> value))
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class UpdateTargetRootCommand[A, K](command: String, serviceId: String, root: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(targetRoot = root)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class AddPublicPatternCommand[A, K](command: String, serviceId: String, pattern: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(publicPatterns = service.publicPatterns + pattern)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class RemovePublicPatternCommand[A, K](command: String, serviceId: String, pattern: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(publicPatterns = service.publicPatterns.filterNot(_ == pattern))
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class AddPrivatePatternCommand[A, K](command: String, serviceId: String, pattern: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(privatePatterns = service.privatePatterns + pattern)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class RemovePrivatePatternCommand[A, K](command: String, serviceId: String, pattern: String)
    extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(privatePatterns = service.privatePatterns.filterNot(_ == pattern))
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class UpdateRootCommand[A, K](command: String, serviceId: String, root: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(root = Some(root))
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

case class RemoveRootCommand[A, K](command: String, serviceId: String) extends Command[A, K] {
  def modify(state: Seq[Service[A, K]]): Seq[Service[A, K]] = {
    state
      .find(_.id == serviceId)
      .map { service =>
        // Update here
        service.copy(root = None)
      }
      .map { newService =>
        state.filterNot(_.id == serviceId) :+ newService
      } getOrElse state
  }
}

class Commands[A, K](decoders: Decoders[A, K]) {

  import decoders._

  val AddServiceCommandDecoder: Decoder[AddServiceCommand[A, K]]         = deriveDecoder[AddServiceCommand[A, K]]
  val UpdateServiceCommandDecoder: Decoder[UpdateServiceCommand[A, K]]   = deriveDecoder[UpdateServiceCommand[A, K]]
  val RemoveServiceCommandDecoder: Decoder[RemoveServiceCommand[A, K]]   = deriveDecoder[RemoveServiceCommand[A, K]]
  val NothingCommandDecoder: Decoder[NothingCommand[A, K]]               = deriveDecoder[NothingCommand[A, K]]
  val LoadStateCommandDecoder: Decoder[LoadStateCommand[A, K]]           = deriveDecoder[LoadStateCommand[A, K]]
  val GetStateCommandDecoder: Decoder[GetStateCommand[A, K]]             = deriveDecoder[GetStateCommand[A, K]]
  val GetServiceCommand: Decoder[GetServiceCommand[A, K]]                = deriveDecoder[GetServiceCommand[A, K]]
  val GetMetricsCommandDecoder: Decoder[GetMetricsCommand[A, K]]         = deriveDecoder[GetMetricsCommand[A, K]]
  val ChangeDomainCommandDecoder: Decoder[ChangeDomainCommand[A, K]]     = deriveDecoder[ChangeDomainCommand[A, K]]
  val AddTargetCommandDecoder: Decoder[AddTargetCommand[A, K]]           = deriveDecoder[AddTargetCommand[A, K]]
  val RemoveTargetCommandDecoder: Decoder[RemoveTargetCommand[A, K]]     = deriveDecoder[RemoveTargetCommand[A, K]]
  val AddApiKeyCommandDecoder: Decoder[AddApiKeyCommand[A, K]]           = deriveDecoder[AddApiKeyCommand[A, K]]
  val UpdateApiKeyCommandDecoder: Decoder[UpdateApiKeyCommand[A, K]]     = deriveDecoder[UpdateApiKeyCommand[A, K]]
  val RemoveApiKeyCommandDecoder: Decoder[RemoveApiKeyCommand[A, K]]     = deriveDecoder[RemoveApiKeyCommand[A, K]]
  val EnableApiKeyCommandDecoder: Decoder[EnableApiKeyCommand[A, K]]     = deriveDecoder[EnableApiKeyCommand[A, K]]
  val DisabledApiKeyCommandDecoder: Decoder[DisabledApiKeyCommand[A, K]] = deriveDecoder[DisabledApiKeyCommand[A, K]]
  val ToggleApiKeyCommandDecoder: Decoder[ToggleApiKeyCommand[A, K]]     = deriveDecoder[ToggleApiKeyCommand[A, K]]
  val ResetApiKeyCommandDecoder: Decoder[ResetApiKeyCommand[A, K]]       = deriveDecoder[ResetApiKeyCommand[A, K]]
  val UpdateClientConfigCommandDecoder: Decoder[UpdateClientConfigCommand[A, K]] =
    deriveDecoder[UpdateClientConfigCommand[A, K]]
  val AddAdditionalHeaderCommandDecoder: Decoder[AddAdditionalHeaderCommand[A, K]] =
    deriveDecoder[AddAdditionalHeaderCommand[A, K]]
  val RemoveAdditionalHeaderCommandDecoder: Decoder[RemoveAdditionalHeaderCommand[A, K]] =
    deriveDecoder[RemoveAdditionalHeaderCommand[A, K]]
  val UpdateAdditionalHeaderCommandDecoder: Decoder[UpdateAdditionalHeaderCommand[A, K]] =
    deriveDecoder[UpdateAdditionalHeaderCommand[A, K]]
  val AddMatchingHeaderCommandDecoder: Decoder[AddMatchingHeaderCommand[A, K]] =
    deriveDecoder[AddMatchingHeaderCommand[A, K]]
  val RemoveMatchingHeaderCommandDecoder: Decoder[RemoveMatchingHeaderCommand[A, K]] =
    deriveDecoder[RemoveMatchingHeaderCommand[A, K]]
  val UpdateMatchingHeaderCommandDecoder: Decoder[UpdateMatchingHeaderCommand[A, K]] =
    deriveDecoder[UpdateMatchingHeaderCommand[A, K]]
  val UpdateTargetRootCommandDecoder: Decoder[UpdateTargetRootCommand[A, K]] =
    deriveDecoder[UpdateTargetRootCommand[A, K]]
  val AddPublicPatternCommandDecoder: Decoder[AddPublicPatternCommand[A, K]] =
    deriveDecoder[AddPublicPatternCommand[A, K]]
  val RemovePublicPatternCommandDecoder: Decoder[RemovePublicPatternCommand[A, K]] =
    deriveDecoder[RemovePublicPatternCommand[A, K]]
  val AddPrivatePatternCommandDecoder: Decoder[AddPrivatePatternCommand[A, K]] =
    deriveDecoder[AddPrivatePatternCommand[A, K]]
  val RemovePrivatePatternCommandDecoder: Decoder[RemovePrivatePatternCommand[A, K]] =
    deriveDecoder[RemovePrivatePatternCommand[A, K]]
  val UpdateRootCommandDecoder: Decoder[UpdateRootCommand[A, K]] = deriveDecoder[UpdateRootCommand[A, K]]
  val RemoveRootCommandDecoder: Decoder[RemoveRootCommand[A, K]] = deriveDecoder[RemoveRootCommand[A, K]]

  def decode(command: String, json: Json): Decoder.Result[Command[A, K]] = {
    command match {
      case "NothingCommand"                => NothingCommandDecoder.decodeJson(json)
      case "AddServiceCommand"             => AddServiceCommandDecoder.decodeJson(json)
      case "UpdateServiceCommand"          => UpdateServiceCommandDecoder.decodeJson(json)
      case "RemoveServiceCommand"          => RemoveServiceCommandDecoder.decodeJson(json)
      case "LoadStateCommand"              => LoadStateCommandDecoder.decodeJson(json)
      case "GetStateCommand"               => GetStateCommandDecoder.decodeJson(json)
      case "GetServiceCommand"             => GetServiceCommand.decodeJson(json)
      case "GetMetricsCommand"             => GetMetricsCommandDecoder.decodeJson(json)
      case "ChangeDomainCommand"           => ChangeDomainCommandDecoder.decodeJson(json)
      case "AddTargetCommand"              => AddTargetCommandDecoder.decodeJson(json)
      case "RemoveTargetCommand"           => RemoveTargetCommandDecoder.decodeJson(json)
      case "AddApiKeyCommand"              => AddApiKeyCommandDecoder.decodeJson(json)
      case "UpdateApiKeyCommand"           => UpdateApiKeyCommandDecoder.decodeJson(json)
      case "RemoveApiKeyCommand"           => RemoveApiKeyCommandDecoder.decodeJson(json)
      case "EnableApiKeyCommand"           => EnableApiKeyCommandDecoder.decodeJson(json)
      case "DisabledApiKeyCommand"         => DisabledApiKeyCommandDecoder.decodeJson(json)
      case "ToggleApiKeyCommand"           => ToggleApiKeyCommandDecoder.decodeJson(json)
      case "ResetApiKeyCommand"            => ResetApiKeyCommandDecoder.decodeJson(json)
      case "UpdateClientConfigCommand"     => UpdateClientConfigCommandDecoder.decodeJson(json)
      case "AddAdditionalHeaderCommand"    => AddAdditionalHeaderCommandDecoder.decodeJson(json)
      case "RemoveAdditionalHeaderCommand" => RemoveAdditionalHeaderCommandDecoder.decodeJson(json)
      case "UpdateAdditionalHeaderCommand" => UpdateAdditionalHeaderCommandDecoder.decodeJson(json)
      case "AddMatchingHeaderCommand"      => AddMatchingHeaderCommandDecoder.decodeJson(json)
      case "RemoveMatchingHeaderCommand"   => RemoveMatchingHeaderCommandDecoder.decodeJson(json)
      case "UpdateMatchingHeaderCommand"   => UpdateMatchingHeaderCommandDecoder.decodeJson(json)
      case "UpdateTargetRootCommand"       => UpdateTargetRootCommandDecoder.decodeJson(json)
      case "AddPublicPatternCommand"       => AddPublicPatternCommandDecoder.decodeJson(json)
      case "RemovePublicPatternCommand"    => RemovePublicPatternCommandDecoder.decodeJson(json)
      case "AddPrivatePatternCommand"      => AddPrivatePatternCommandDecoder.decodeJson(json)
      case "RemovePrivatePatternCommand"   => RemovePrivatePatternCommandDecoder.decodeJson(json)
      case "UpdateRootCommand"             => UpdateRootCommandDecoder.decodeJson(json)
      case "RemoveRootCommand"             => RemoveRootCommandDecoder.decodeJson(json)
      case _                               => Left(DecodingFailure.apply("Bad command", List.empty[CursorOp]))
    }
  }
}
