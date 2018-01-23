package models

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.{HttpProtocol, HttpProtocols}
import io.circe.Decoder.Result
import io.circe._
import io.circe.generic.semiauto._
import models.Decoders.ServiceDecoder
import store.Store
import util.IdGenerator

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

case class ApiKey(clientId: String, clientSecret: String, name: String, enabled: Boolean)

case class Service(id: String,
                   domain: String,
                   targets: Seq[Target] = Seq.empty,
                   apiKeys: Seq[ApiKey] = Seq.empty,
                   clientConfig: ClientConfig = ClientConfig(),
                   additionalHeaders: Map[String, String] = Map.empty,
                   matchingHeaders: Map[String, String] = Map.empty,
                   targetRoot: String = "",
                   root: Option[String] = None,
                   publicPatterns: Set[String] = Set.empty,
                   privatePatterns: Set[String] = Set.empty)

case class LocalStateConfig(path: String, writeEvery: FiniteDuration = 10.seconds)
case class RemoteStateConfig(url: String, headers: Map[String, String], pollEvery: FiniteDuration = 10.seconds)

case class StateConfig(
    local: Option[LocalStateConfig] = None,
    remote: Option[RemoteStateConfig] = None
) {
  def isRemote = remote.isDefined && local.isEmpty
  def isLocal  = local.isDefined && remote.isEmpty
}

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
    certPass: Option[String] = None,
    enabled: Boolean = true
)

case class ProxyConfig(
    http: HttpConfig = HttpConfig(),
    api: ApiConfig = ApiConfig(),
    services: Seq[Service] = Seq.empty,
    logConfigPath: Option[String] = None,
    state: Option[StateConfig] = None
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
  implicit val LocalStateConfigDecoder: Decoder[LocalStateConfig]   = deriveDecoder[LocalStateConfig]
  implicit val RemoteStateConfigDecoder: Decoder[RemoteStateConfig] = deriveDecoder[RemoteStateConfig]
  implicit val StateConfigDecoder: Decoder[StateConfig]             = deriveDecoder[StateConfig]
  implicit val TargetDecoder: Decoder[Target]                       = deriveDecoder[Target]
  implicit val ClientConfigDecoder: Decoder[ClientConfig]           = deriveDecoder[ClientConfig]
  implicit val ApiKeyDecoder: Decoder[ApiKey]                       = deriveDecoder[ApiKey]
  implicit val ServiceDecoder: Decoder[Service]                     = deriveDecoder[Service]
  implicit val SeqOfServiceDecoder: Decoder[Seq[Service]]           = Decoder.decodeSeq(ServiceDecoder)
  implicit val HttpConfigDecoder: Decoder[HttpConfig]               = deriveDecoder[HttpConfig]
  implicit val ApiConfigDecoder: Decoder[ApiConfig]                 = deriveDecoder[ApiConfig]
  implicit val ProxyConfigDecoder: Decoder[ProxyConfig]             = deriveDecoder[ProxyConfig]
}

object Encoders {
  implicit val FiniteDurationEncoder: Encoder[FiniteDuration] = new Encoder[FiniteDuration] {
    override def apply(a: FiniteDuration): Json = Json.fromLong(a.toMillis)
  }
  implicit val HttpProtocolEncoder: Encoder[HttpProtocol] = new Encoder[HttpProtocol] {
    override def apply(a: HttpProtocol): Json = Json.fromString(a.value)
  }
  implicit val LocalStateConfigEncoder: Encoder[LocalStateConfig]   = deriveEncoder[LocalStateConfig]
  implicit val RemoteStateConfigEncoder: Encoder[RemoteStateConfig] = deriveEncoder[RemoteStateConfig]
  implicit val StateConfigEncoder: Encoder[StateConfig]             = deriveEncoder[StateConfig]
  implicit val TargetEncoder: Encoder[Target]                       = deriveEncoder[Target]
  implicit val ClientConfigEncoder: Encoder[ClientConfig]           = deriveEncoder[ClientConfig]
  implicit val ApiKeyEncoder: Encoder[ApiKey]                       = deriveEncoder[ApiKey]
  implicit val ServiceEncoder: Encoder[Service]                     = deriveEncoder[Service]
  implicit val SeqOfServiceEncoder: Encoder[Seq[Service]]           = Encoder.encodeSeq(ServiceEncoder)
  implicit val HttpConfigEncoder: Encoder[HttpConfig]               = deriveEncoder[HttpConfig]
  implicit val ApiConfigEncoder: Encoder[ApiConfig]                 = deriveEncoder[ApiConfig]
  implicit val ProxyConfigEncoder: Encoder[ProxyConfig]             = deriveEncoder[ProxyConfig]
}

trait WithApiKeyOrNot
case object NoApiKey                  extends WithApiKeyOrNot
case object BadApiKey                 extends WithApiKeyOrNot
case class WithApiKey(apiKey: ApiKey) extends WithApiKeyOrNot

trait CallRestriction
case object PublicCall  extends CallRestriction
case object PrivateCall extends CallRestriction

case class ConfigError(message: String)

trait Command {
  def command: String
  def modify(state: Seq[Service]): Seq[Service]
  def applyModification(store: Store): Unit = store.modify(s => modify(s.values.flatten.toSeq).groupBy(_.domain))
}

case class NothingCommand(command: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = state
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class DumpStateCommand(command: String, serviceId: String, payload: Unit) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = state
}

case class DumpMetricsCommand(command: String, serviceId: String, payload: Unit) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = state
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class LoadStateCommand(command: String, serviceId: String, services: Seq[Service]) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = services
}

case class AddServiceCommand(command: String, service: Service) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = state :+ service
}

case class UpdateServiceCommand(command: String, serviceId: String, updatedService: Service) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class RemoveServiceCommand(command: String, serviceId: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = state.filterNot(_.id == serviceId)
}

case class ChangeDomainCommand(command: String, serviceId: String, domain: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class AddTargetCommand(command: String, serviceId: String, target: Target) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class RemoveTargetCommand(command: String, serviceId: String, target: Target) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class AddApiKeyCommand(command: String, serviceId: String, apiKey: ApiKey) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class UpdateApiKeyCommand(command: String, serviceId: String, apiKey: ApiKey) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class RemoveApiKeyCommand(command: String, serviceId: String, apiKey: ApiKey) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class EnableApiKeyCommand(command: String, serviceId: String, clientId: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class DisabledApiKeyCommand(command: String, serviceId: String, clientId: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class ToggleApiKeyCommand(command: String, serviceId: String, clientId: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class ResetApiKeyCommand(command: String, serviceId: String, clientId: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class UpdateClientConfigCommand(command: String, serviceId: String, config: ClientConfig) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class AddAdditionalHeaderCommand(command: String, serviceId: String, name: String, value: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class RemoveAdditionalHeaderCommand(command: String, serviceId: String, name: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class UpdateAdditionalHeaderCommand(command: String, serviceId: String, name: String, value: String)
    extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class AddMatchingHeaderCommand(command: String, serviceId: String, name: String, value: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class RemoveMatchingHeaderCommand(command: String, serviceId: String, name: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class UpdateMatchingHeaderCommand(command: String, serviceId: String, name: String, value: String)
    extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class UpdateTargetRootCommand(command: String, serviceId: String, root: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class AddPublicPatternCommand(command: String, serviceId: String, pattern: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class RemovePublicPatternCommand(command: String, serviceId: String, pattern: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class AddPrivatePatternCommand(command: String, serviceId: String, pattern: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class RemovePrivatePatternCommand(command: String, serviceId: String, pattern: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class UpdateRootCommand(command: String, serviceId: String, root: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

case class RemoveRootCommand(command: String, serviceId: String) extends Command {
  def modify(state: Seq[Service]): Seq[Service] = {
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

object Command {

  import models.Decoders._

  val AddServiceCommandDecoder: Decoder[AddServiceCommand]                   = deriveDecoder[AddServiceCommand]
  val UpdateServiceCommandDecoder: Decoder[UpdateServiceCommand]             = deriveDecoder[UpdateServiceCommand]
  val RemoveServiceCommandDecoder: Decoder[RemoveServiceCommand]             = deriveDecoder[RemoveServiceCommand]
  val NothingCommandDecoder: Decoder[NothingCommand]                         = deriveDecoder[NothingCommand]
  val LoadStateCommandDecoder: Decoder[LoadStateCommand]                     = deriveDecoder[LoadStateCommand]
  val DumpStateCommandDecoder: Decoder[DumpStateCommand]                     = deriveDecoder[DumpStateCommand]
  val DumpMetricsCommandDecoder: Decoder[DumpMetricsCommand]                 = deriveDecoder[DumpMetricsCommand]
  val ChangeDomainCommandDecoder: Decoder[ChangeDomainCommand]               = deriveDecoder[ChangeDomainCommand]
  val AddTargetCommandDecoder: Decoder[AddTargetCommand]                     = deriveDecoder[AddTargetCommand]
  val RemoveTargetCommandDecoder: Decoder[RemoveTargetCommand]               = deriveDecoder[RemoveTargetCommand]
  val AddApiKeyCommandDecoder: Decoder[AddApiKeyCommand]                     = deriveDecoder[AddApiKeyCommand]
  val UpdateApiKeyCommandDecoder: Decoder[UpdateApiKeyCommand]               = deriveDecoder[UpdateApiKeyCommand]
  val RemoveApiKeyCommandDecoder: Decoder[RemoveApiKeyCommand]               = deriveDecoder[RemoveApiKeyCommand]
  val EnableApiKeyCommandDecoder: Decoder[EnableApiKeyCommand]               = deriveDecoder[EnableApiKeyCommand]
  val DisabledApiKeyCommandDecoder: Decoder[DisabledApiKeyCommand]           = deriveDecoder[DisabledApiKeyCommand]
  val ToggleApiKeyCommandDecoder: Decoder[ToggleApiKeyCommand]               = deriveDecoder[ToggleApiKeyCommand]
  val ResetApiKeyCommandDecoder: Decoder[ResetApiKeyCommand]                 = deriveDecoder[ResetApiKeyCommand]
  val UpdateClientConfigCommandDecoder: Decoder[UpdateClientConfigCommand]   = deriveDecoder[UpdateClientConfigCommand]
  val AddAdditionalHeaderCommandDecoder: Decoder[AddAdditionalHeaderCommand] = deriveDecoder[AddAdditionalHeaderCommand]
  val RemoveAdditionalHeaderCommandDecoder: Decoder[RemoveAdditionalHeaderCommand] =
    deriveDecoder[RemoveAdditionalHeaderCommand]
  val UpdateAdditionalHeaderCommandDecoder: Decoder[UpdateAdditionalHeaderCommand] =
    deriveDecoder[UpdateAdditionalHeaderCommand]
  val AddMatchingHeaderCommandDecoder: Decoder[AddMatchingHeaderCommand] = deriveDecoder[AddMatchingHeaderCommand]
  val RemoveMatchingHeaderCommandDecoder: Decoder[RemoveMatchingHeaderCommand] =
    deriveDecoder[RemoveMatchingHeaderCommand]
  val UpdateMatchingHeaderCommandDecoder: Decoder[UpdateMatchingHeaderCommand] =
    deriveDecoder[UpdateMatchingHeaderCommand]
  val UpdateTargetRootCommandDecoder: Decoder[UpdateTargetRootCommand]       = deriveDecoder[UpdateTargetRootCommand]
  val AddPublicPatternCommandDecoder: Decoder[AddPublicPatternCommand]       = deriveDecoder[AddPublicPatternCommand]
  val RemovePublicPatternCommandDecoder: Decoder[RemovePublicPatternCommand] = deriveDecoder[RemovePublicPatternCommand]
  val AddPrivatePatternCommandDecoder: Decoder[AddPrivatePatternCommand]     = deriveDecoder[AddPrivatePatternCommand]
  val RemovePrivatePatternCommandDecoder: Decoder[RemovePrivatePatternCommand] =
    deriveDecoder[RemovePrivatePatternCommand]
  val UpdateRootCommandDecoder: Decoder[UpdateRootCommand] = deriveDecoder[UpdateRootCommand]
  val RemoveRootCommandDecoder: Decoder[RemoveRootCommand] = deriveDecoder[RemoveRootCommand]

  def decode(command: String, json: Json): Decoder.Result[Command] = {
    command match {
      case "NothingCommand"                => NothingCommandDecoder.decodeJson(json)
      case "AddServiceCommand"             => AddServiceCommandDecoder.decodeJson(json)
      case "UpdateServiceCommand"          => UpdateServiceCommandDecoder.decodeJson(json)
      case "RemoveServiceCommand"          => RemoveServiceCommandDecoder.decodeJson(json)
      case "LoadStateCommand"              => LoadStateCommandDecoder.decodeJson(json)
      case "DumpStateCommand"              => DumpStateCommandDecoder.decodeJson(json)
      case "DumpMetricsCommand"            => DumpMetricsCommandDecoder.decodeJson(json)
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
