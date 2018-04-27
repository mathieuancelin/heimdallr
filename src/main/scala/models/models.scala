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

case class ApiKey(clientId: String,
                  clientSecret: String,
                  name: String,
                  enabled: Boolean,
                  metadata: Map[String, String] = Map.empty)

case class Service[A](id: String,
                      domain: String,
                      enabled: Boolean = true,
                      targets: Seq[Target] = Seq.empty,
                      apiKeys: Seq[ApiKey] = Seq.empty,
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

case class ModulesConfig[A](modules: Seq[_ <: Module[A]] = Seq.empty) {
  lazy val PreconditionModules: Seq[PreconditionModule[A]] = modules.collect {
    case m: PreconditionModule[A] => m
  }
  lazy val ServiceAccessModules: Seq[ServiceAccessModule[A]] = modules.collect {
    case m: ServiceAccessModule[A] => m
  }
  lazy val HeadersInTransformationModules: Seq[HeadersInTransformationModule[A]] = modules.collect {
    case m: HeadersInTransformationModule[A] => m
  }
  lazy val HeadersOutTransformationModules: Seq[HeadersOutTransformationModule[A]] = modules.collect {
    case m: HeadersOutTransformationModule[A] => m
  }
  lazy val ErrorRendererModules: Seq[ErrorRendererModule[A]] = modules.collect {
    case m: ErrorRendererModule[A] => m
  }
  lazy val TargetSetChooserModules: Seq[TargetSetChooserModule[A]] = modules.collect {
    case m: TargetSetChooserModule[A] => m
  }
}

case class LoggersConfig(level: String, configPath: Option[String] = None)

case class ProxyConfig[A](
    http: HttpConfig = HttpConfig(),
    api: ApiConfig = ApiConfig(),
    services: Seq[Service[A]] = Seq.empty,
    state: Option[StateConfig] = None,
    loggers: LoggersConfig = LoggersConfig("INFO"),
    statsd: Option[StatsdConfig] = None
) {
  def pretty(implicit encoders: Encoders[A]): String = encoders.ProxyConfigEncoder.apply(this).spaces2
}

class Decoders[A](decoder: Decoder[A]) {

  implicit val extensionDecoder = decoder

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
  implicit val ApiKeyDecoder: Decoder[ApiKey]                           = deriveDecoder[ApiKey]
  implicit val ServiceDecoder: Decoder[Service[A]]                      = deriveDecoder[Service[A]]
  implicit val SeqOfServiceDecoder: Decoder[Seq[Service[A]]]            = Decoder.decodeSeq(ServiceDecoder)
  implicit val HttpConfigDecoder: Decoder[HttpConfig]                   = deriveDecoder[HttpConfig]
  implicit val ApiConfigDecoder: Decoder[ApiConfig]                     = deriveDecoder[ApiConfig]
  implicit val LoggersConfigDecoder: Decoder[LoggersConfig]             = deriveDecoder[LoggersConfig]
  implicit val ProxyConfigDecoder: Decoder[ProxyConfig[A]]              = deriveDecoder[ProxyConfig[A]]
}

class Encoders[A](encoder: Encoder[A]) {

  implicit val extensionEncoder = encoder

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
  implicit val ApiKeyEncoder: Encoder[ApiKey]                           = deriveEncoder[ApiKey]
  implicit val ServiceEncoder: Encoder[Service[A]]                      = deriveEncoder[Service[A]]
  implicit val SeqOfServiceEncoder: Encoder[Seq[Service[A]]]            = Encoder.encodeSeq(ServiceEncoder)
  implicit val HttpConfigEncoder: Encoder[HttpConfig]                   = deriveEncoder[HttpConfig]
  implicit val ApiConfigEncoder: Encoder[ApiConfig]                     = deriveEncoder[ApiConfig]
  implicit val LoggersConfigEncoder: Encoder[LoggersConfig]             = deriveEncoder[LoggersConfig]
  implicit val ProxyConfigEncoder: Encoder[ProxyConfig[A]]              = deriveEncoder[ProxyConfig[A]]
}

sealed trait WithApiKeyOrNot
case object NoApiKey                  extends WithApiKeyOrNot
case object BadApiKey                 extends WithApiKeyOrNot
case class WithApiKey(apiKey: ApiKey) extends WithApiKeyOrNot

sealed trait CallRestriction
case object PublicCall  extends CallRestriction
case object PrivateCall extends CallRestriction

case class ConfigError(message: String)

trait Command[A] {
  def command: String
  def modify(state: Seq[Service[A]]): Seq[Service[A]]
  def applyModification(store: Store[A], encoders: Encoders[A]): Json = {
    store.modify(s => modify(s.values.flatten.toSeq).groupBy(_.domain))
    Json.obj("result" -> Json.fromString("command applied"))
  }
}

case class NothingCommand[A](command: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = state
}

case class GetStateCommand[A](command: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = state
  override def applyModification(store: Store[A], encoders: Encoders[A]): Json = {
    val seq = store.get().values.flatten.toSeq
    Json.obj("state" -> encoders.SeqOfServiceEncoder(seq))
  }
}

case class GetServiceCommand[A](command: String, serviceId: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = state
  override def applyModification(store: Store[A], encoders: Encoders[A]): Json = {
    store.get().values.flatten.toSeq.find(_.id == serviceId) match {
      case Some(service) => encoders.ServiceEncoder(service)
      case None          => Json.obj("error" -> Json.fromString("not found"))
    }
  }
}

case class GetMetricsCommand[A](command: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = state
  override def applyModification(store: Store[A], encoders: Encoders[A]): Json = {
    Json.obj("metrics" -> Json.obj())
  }
}

case class LoadStateCommand[A](command: String, serviceId: String, services: Seq[Service[A]]) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = services
}

case class AddServiceCommand[A](command: String, service: Service[A]) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = state :+ service
}

case class UpdateServiceCommand[A](command: String, serviceId: String, updatedService: Service[A]) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class RemoveServiceCommand[A](command: String, serviceId: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = state.filterNot(_.id == serviceId)
}

case class ChangeDomainCommand[A](command: String, serviceId: String, domain: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class AddTargetCommand[A](command: String, serviceId: String, target: Target) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class RemoveTargetCommand[A](command: String, serviceId: String, target: Target) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class AddApiKeyCommand[A](command: String, serviceId: String, apiKey: ApiKey) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class UpdateApiKeyCommand[A](command: String, serviceId: String, apiKey: ApiKey) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class RemoveApiKeyCommand[A](command: String, serviceId: String, apiKey: ApiKey) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class EnableApiKeyCommand[A](command: String, serviceId: String, clientId: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class DisabledApiKeyCommand[A](command: String, serviceId: String, clientId: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class ToggleApiKeyCommand[A](command: String, serviceId: String, clientId: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class ResetApiKeyCommand[A](command: String, serviceId: String, clientId: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class UpdateClientConfigCommand[A](command: String, serviceId: String, config: ClientConfig) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class AddAdditionalHeaderCommand[A](command: String, serviceId: String, name: String, value: String)
    extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class RemoveAdditionalHeaderCommand[A](command: String, serviceId: String, name: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class UpdateAdditionalHeaderCommand[A](command: String, serviceId: String, name: String, value: String)
    extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class AddMatchingHeaderCommand[A](command: String, serviceId: String, name: String, value: String)
    extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class RemoveMatchingHeaderCommand[A](command: String, serviceId: String, name: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class UpdateMatchingHeaderCommand[A](command: String, serviceId: String, name: String, value: String)
    extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class UpdateTargetRootCommand[A](command: String, serviceId: String, root: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class AddPublicPatternCommand[A](command: String, serviceId: String, pattern: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class RemovePublicPatternCommand[A](command: String, serviceId: String, pattern: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class AddPrivatePatternCommand[A](command: String, serviceId: String, pattern: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class RemovePrivatePatternCommand[A](command: String, serviceId: String, pattern: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class UpdateRootCommand[A](command: String, serviceId: String, root: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

case class RemoveRootCommand[A](command: String, serviceId: String) extends Command[A] {
  def modify(state: Seq[Service[A]]): Seq[Service[A]] = {
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

class Commands[A](decoders: Decoders[A]) {

  import decoders._

  val AddServiceCommandDecoder: Decoder[AddServiceCommand[A]]         = deriveDecoder[AddServiceCommand[A]]
  val UpdateServiceCommandDecoder: Decoder[UpdateServiceCommand[A]]   = deriveDecoder[UpdateServiceCommand[A]]
  val RemoveServiceCommandDecoder: Decoder[RemoveServiceCommand[A]]   = deriveDecoder[RemoveServiceCommand[A]]
  val NothingCommandDecoder: Decoder[NothingCommand[A]]               = deriveDecoder[NothingCommand[A]]
  val LoadStateCommandDecoder: Decoder[LoadStateCommand[A]]           = deriveDecoder[LoadStateCommand[A]]
  val GetStateCommandDecoder: Decoder[GetStateCommand[A]]             = deriveDecoder[GetStateCommand[A]]
  val GetServiceCommand: Decoder[GetServiceCommand[A]]                = deriveDecoder[GetServiceCommand[A]]
  val GetMetricsCommandDecoder: Decoder[GetMetricsCommand[A]]         = deriveDecoder[GetMetricsCommand[A]]
  val ChangeDomainCommandDecoder: Decoder[ChangeDomainCommand[A]]     = deriveDecoder[ChangeDomainCommand[A]]
  val AddTargetCommandDecoder: Decoder[AddTargetCommand[A]]           = deriveDecoder[AddTargetCommand[A]]
  val RemoveTargetCommandDecoder: Decoder[RemoveTargetCommand[A]]     = deriveDecoder[RemoveTargetCommand[A]]
  val AddApiKeyCommandDecoder: Decoder[AddApiKeyCommand[A]]           = deriveDecoder[AddApiKeyCommand[A]]
  val UpdateApiKeyCommandDecoder: Decoder[UpdateApiKeyCommand[A]]     = deriveDecoder[UpdateApiKeyCommand[A]]
  val RemoveApiKeyCommandDecoder: Decoder[RemoveApiKeyCommand[A]]     = deriveDecoder[RemoveApiKeyCommand[A]]
  val EnableApiKeyCommandDecoder: Decoder[EnableApiKeyCommand[A]]     = deriveDecoder[EnableApiKeyCommand[A]]
  val DisabledApiKeyCommandDecoder: Decoder[DisabledApiKeyCommand[A]] = deriveDecoder[DisabledApiKeyCommand[A]]
  val ToggleApiKeyCommandDecoder: Decoder[ToggleApiKeyCommand[A]]     = deriveDecoder[ToggleApiKeyCommand[A]]
  val ResetApiKeyCommandDecoder: Decoder[ResetApiKeyCommand[A]]       = deriveDecoder[ResetApiKeyCommand[A]]
  val UpdateClientConfigCommandDecoder: Decoder[UpdateClientConfigCommand[A]] =
    deriveDecoder[UpdateClientConfigCommand[A]]
  val AddAdditionalHeaderCommandDecoder: Decoder[AddAdditionalHeaderCommand[A]] =
    deriveDecoder[AddAdditionalHeaderCommand[A]]
  val RemoveAdditionalHeaderCommandDecoder: Decoder[RemoveAdditionalHeaderCommand[A]] =
    deriveDecoder[RemoveAdditionalHeaderCommand[A]]
  val UpdateAdditionalHeaderCommandDecoder: Decoder[UpdateAdditionalHeaderCommand[A]] =
    deriveDecoder[UpdateAdditionalHeaderCommand[A]]
  val AddMatchingHeaderCommandDecoder: Decoder[AddMatchingHeaderCommand[A]] = deriveDecoder[AddMatchingHeaderCommand[A]]
  val RemoveMatchingHeaderCommandDecoder: Decoder[RemoveMatchingHeaderCommand[A]] =
    deriveDecoder[RemoveMatchingHeaderCommand[A]]
  val UpdateMatchingHeaderCommandDecoder: Decoder[UpdateMatchingHeaderCommand[A]] =
    deriveDecoder[UpdateMatchingHeaderCommand[A]]
  val UpdateTargetRootCommandDecoder: Decoder[UpdateTargetRootCommand[A]] = deriveDecoder[UpdateTargetRootCommand[A]]
  val AddPublicPatternCommandDecoder: Decoder[AddPublicPatternCommand[A]] = deriveDecoder[AddPublicPatternCommand[A]]
  val RemovePublicPatternCommandDecoder: Decoder[RemovePublicPatternCommand[A]] =
    deriveDecoder[RemovePublicPatternCommand[A]]
  val AddPrivatePatternCommandDecoder: Decoder[AddPrivatePatternCommand[A]] = deriveDecoder[AddPrivatePatternCommand[A]]
  val RemovePrivatePatternCommandDecoder: Decoder[RemovePrivatePatternCommand[A]] =
    deriveDecoder[RemovePrivatePatternCommand[A]]
  val UpdateRootCommandDecoder: Decoder[UpdateRootCommand[A]] = deriveDecoder[UpdateRootCommand[A]]
  val RemoveRootCommandDecoder: Decoder[RemoveRootCommand[A]] = deriveDecoder[RemoveRootCommand[A]]

  def decode(command: String, json: Json): Decoder.Result[Command[A]] = {
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
