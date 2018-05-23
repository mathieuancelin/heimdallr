package io.heimdallr.modules.default

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.heimdallr.models._
import io.heimdallr.modules.{Extensions, _}
import io.heimdallr.util.Implicits._
import io.heimdallr.util.{IdGenerator, RegexPool, Startable, Stoppable}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

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

class DefaultModules(config: ProxyConfig[NoExtension, NoExtension]) extends Modules[NoExtension, NoExtension] {

  lazy val extensions: Extensions[NoExtension, NoExtension] = NoExtension
  lazy val store                                            = new DefaultAtomicStore(config.services.groupBy(_.domain), config.state, NoExtension)
  lazy val modules: ModulesConfig[NoExtension, NoExtension] = new ModulesConfig[NoExtension, NoExtension] {
    override def PreconditionModule: PreconditionModule[NoExtension, NoExtension]   = new DefaultPreconditionModule()
    override def ServiceAccessModule: ServiceAccessModule[NoExtension, NoExtension] = new DefaultServiceAccessModule()
    override def HeadersInTransformationModule: HeadersInTransformationModule[NoExtension, NoExtension] =
      new DefaultHeadersInTransformationModule()
    override def HeadersOutTransformationModule: HeadersOutTransformationModule[NoExtension, NoExtension] =
      new DefaultHeadersOutTransformationModule()
    override def ErrorRendererModule: ErrorRendererModule[NoExtension, NoExtension] = new DefaultErrorRendererModule()
    override def TargetSetChooserModule: TargetSetChooserModule[NoExtension, NoExtension] =
      new DefaultTargetSetChooserModule()
    override def ServiceFinderModule: ServiceFinderModule[NoExtension, NoExtension] =
      new DefaultServiceFinderModule(store)
    override def BeforeAfterModule: BeforeAfterModule[NoExtension, NoExtension] = new DefaultBeforeAfterModule()

    override def ServiceStore: ServiceStoreModule[NoExtension, NoExtension] = new DefaultServiceStore(store)
  }
}

object DefaultModules {
  def apply(config: ProxyConfig[NoExtension, NoExtension]): DefaultModules = new DefaultModules(config)
}

class DefaultServiceStore(store: DefaultAtomicStore) extends ServiceStoreModule[NoExtension, NoExtension] {

  override def id: String = "DefaultServiceStore"

  override def getAllServices()(
      implicit ec: ExecutionContext
  ): Future[Map[String, Seq[Service[NoExtension, NoExtension]]]] = store.get()

  override def findServiceById(id: String)(
      implicit ec: ExecutionContext
  ): Future[Option[Service[NoExtension, NoExtension]]] = store.get().map(_.values.flatten.toSeq.find(_.id == id))

  override def setAllServices(services: Map[String, Seq[Service[NoExtension, NoExtension]]])(
      implicit ec: ExecutionContext
  ): Future[Map[String, Seq[Service[NoExtension, NoExtension]]]] = store.modify(_ => services)

  def applyModification(
      modify: Seq[Service[NoExtension, NoExtension]] => Seq[Service[NoExtension, NoExtension]]
  )(implicit ec: ExecutionContext): Future[Unit] = {
    store.modify(s => modify(s.values.flatten.toSeq).groupBy(_.domain)).map(_ => ())
  }

  override def addService(service: Service[NoExtension, NoExtension])(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification(state => state :+ service)
  }

  override def updateService(serviceId: String, updatedService: Service[NoExtension, NoExtension])(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    applyModification { state =>
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

  override def removeService(serviceId: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
      state.filterNot(_.id == serviceId)
    }
  }

  override def changeDomain(serviceId: String, domain: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def addTarget(serviceId: String, target: Target)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def removeTarget(serviceId: String, target: Target)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def addApiKey(serviceId: String,
                         apiKey: ApiKey[NoExtension])(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def updateApiKey(serviceId: String,
                            apiKey: ApiKey[NoExtension])(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def removeApiKey(serviceId: String, clientId: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
      state
        .find(_.id == serviceId)
        .map { service =>
          // Update here
          service.copy(apiKeys = service.apiKeys.filterNot(_.clientId == clientId))
        }
        .map { newService =>
          state.filterNot(_.id == serviceId) :+ newService
        } getOrElse state
    }
  }

  override def enableApiKey(serviceId: String, clientId: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def disabledApiKey(serviceId: String, clientId: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def toggleApiKey(serviceId: String, clientId: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def resetApiKey(serviceId: String, clientId: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def updateClientConfig(serviceId: String,
                                  config: ClientConfig)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def addAdditionalHeader(serviceId: String, name: String, value: String)(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    applyModification { state =>
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

  override def removeAdditionalHeader(serviceId: String, name: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def updateAdditionalHeader(serviceId: String, name: String, value: String)(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    applyModification { state =>
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

  override def addMatchingHeader(serviceId: String, name: String, value: String)(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    applyModification { state =>
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

  override def removeMatchingHeader(serviceId: String, name: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def updateMatchingHeader(serviceId: String, name: String, value: String)(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    applyModification { state =>
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

  override def updateTargetRoot(serviceId: String, root: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def addPublicPattern(serviceId: String, pattern: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def removePublicPattern(serviceId: String, pattern: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def addPrivatePattern(serviceId: String, pattern: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def removePrivatePattern(serviceId: String, pattern: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def updateRoot(serviceId: String, root: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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

  override def removeRoot(serviceId: String)(implicit ec: ExecutionContext): Future[Unit] = {
    applyModification { state =>
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
}

class DefaultPreconditionModule extends PreconditionModule[NoExtension, NoExtension] {

  override def id: String = "DefaultPreconditionModule"

  override def validatePreconditions(ctx: ReqContext, service: Service[NoExtension, NoExtension])(
      implicit ec: ExecutionContext
  ): Future[Either[HttpResponse, Unit]] = {
    if (service.enabled) {
      Right(()).asFuture
    } else {
      Left(
        HttpResponse(
          404,
          entity = HttpEntity(
            ContentTypes.`application/json`,
            Json.obj("error" -> Json.fromString("service not found"), "reqId" -> Json.fromString(ctx.reqId)).noSpaces
          )
        )
      ).asFuture
    }
  }
}

class DefaultServiceAccessModule extends ServiceAccessModule[NoExtension, NoExtension] {

  val authHeaderName = "Proxy-Authorization"

  val decoder = Base64.getUrlDecoder

  override def id: String = "DefaultServiceAccessModule"

  override def access(ctx: ReqContext, service: Service[NoExtension, NoExtension])(
      implicit ec: ExecutionContext
  ): Future[WithApiKeyOrNot] = {
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
      case Some(withApiKeyOrNot) => withApiKeyOrNot.asFuture
      case None                  => NoApiKey.asFuture
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
                         headers: List[HttpHeader])(implicit ec: ExecutionContext): Future[List[HttpHeader]] = {
    (
      headers.filterNot(_.name() == authHeaderName) ++
      service.additionalHeaders.toList.map(t => RawHeader(t._1, t._2)) :+
      RawHeader("X-Request-Id", ctx.reqId) :+
      RawHeader("X-Fowarded-Host", host) :+
      RawHeader("X-Fowarded-Scheme", ctx.request.uri.scheme)
    ).asFuture
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
                         headers: List[HttpHeader])(implicit ec: ExecutionContext): Future[List[HttpHeader]] = {
    (headers :+
    RawHeader("X-Proxy-Latency", proxyLatency.toString) :+
    RawHeader("X-Target-Latency", targetLatency.toString)).asFuture
  }
}

class DefaultErrorRendererModule extends ErrorRendererModule[NoExtension, NoExtension] {

  override def id: String = "DefaultErrorRendererModule"

  override def render(
      ctx: ReqContext,
      status: Int,
      message: String,
      service: Option[Service[NoExtension, NoExtension]]
  )(implicit ec: ExecutionContext): Future[HttpResponse] =
    HttpResponse(
      status,
      entity = HttpEntity(ContentTypes.`application/json`,
                          Json.obj("error" -> Json.fromString(message), "reqId" -> Json.fromString(ctx.reqId)).noSpaces)
    ).asFuture
}

class DefaultTargetSetChooserModule extends TargetSetChooserModule[NoExtension, NoExtension] {

  override def id: String = "DefaultTargetSetChooserModule"

  override def choose(ctx: ReqContext,
                      service: Service[NoExtension, NoExtension])(implicit ec: ExecutionContext): Future[Seq[Target]] =
    service.targets.asFuture
}

class DefaultServiceFinderModule(store: DefaultAtomicStore) extends ServiceFinderModule[NoExtension, NoExtension] {

  override def id: String = "DefaultServiceFinderModule"

  override def findService(
      ctx: ReqContext,
      host: String,
      path: Uri.Path,
      headers: Map[String, HttpHeader]
  )(implicit ec: ExecutionContext): Future[Option[Service[NoExtension, NoExtension]]] = {
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

    store.get().map { map =>
      map.get(host).flatMap(findSubService) match {
        case s @ Some(_) => s
        case None => {
          val state = map
          state.keys.find(k => RegexPool(k).matches(host)).flatMap(k => state.get(k)) match {
            case Some(services) => findSubService(services)
            case None           => None
          }
        }
      }
    }
  }
}

class DefaultBeforeAfterModule extends BeforeAfterModule[NoExtension, NoExtension] {

  val fu = FastFuture.successful(())

  override def beforeRequest(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit] = fu

  override def afterRequestSuccess(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit] = fu

  override def afterRequestWebSocketSuccess(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit] = fu

  override def afterRequestError(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit] = fu

  override def afterRequestEnd(ctx: ReqContext)(implicit ec: ExecutionContext): Future[Unit] = fu

  override def id: String = "DefaultBeforeAfterModule"
}

case class UpdateStoreFile(path: String, state: Map[String, Seq[Service[NoExtension, NoExtension]]])

class DefaultAtomicStore(initialState: Map[String, Seq[Service[NoExtension, NoExtension]]] =
                           Map.empty[String, Seq[Service[NoExtension, NoExtension]]],
                         stateConfig: Option[StateConfig],
                         extensions: Extensions[NoExtension, NoExtension])
    extends Startable[DefaultAtomicStore]
    with Stoppable[DefaultAtomicStore] {

  private implicit val system       = ActorSystem()
  private implicit val executor     = system.dispatcher
  private implicit val materializer = ActorMaterializer.create(system)
  private implicit val http         = Http(system)

  private val encoders = new Encoders[NoExtension, NoExtension](extensions)
  private val decoders = new Decoders[NoExtension, NoExtension](extensions)
  private val actor    = system.actorOf(FileWriter.props(encoders))

  lazy val logger = LoggerFactory.getLogger("heimdallr")

  private val ref: AtomicReference[Map[String, Seq[Service[NoExtension, NoExtension]]]] = {
    if (stateConfig.isDefined) {
      if (stateConfig.get.isRemote) {
        new AtomicReference[Map[String, Seq[Service[NoExtension, NoExtension]]]](initialState)
      } else if (stateConfig.get.isOtoroshi) {
        new AtomicReference[Map[String, Seq[Service[NoExtension, NoExtension]]]](initialState)
      } else {
        val config = stateConfig.get.local
        new AtomicReference[Map[String, Seq[Service[NoExtension, NoExtension]]]](
          config
            .map(c => new File(c.path))
            .filter(_.exists())
            .map { file =>
              io.circe.parser.parse(new String(Files.readAllBytes(file.toPath))) match {
                case Left(e) =>
                  logger.error(s"Error while parsing state file: ${e.message}")
                  initialState
                case Right(json) =>
                  json.as[Seq[Service[NoExtension, NoExtension]]](Decoder.decodeSeq(decoders.ServiceDecoder)) match {
                    case Left(e) =>
                      logger.error(s"Error while parsing state file: ${e.message}")
                      initialState
                    case Right(services) =>
                      logger.info(s"Loading state from ${file.toPath.toString}")
                      services.groupBy(_.domain)
                  }
              }
            } getOrElse {
            initialState
          }
        )
      }
    } else {
      new AtomicReference[Map[String, Seq[Service[NoExtension, NoExtension]]]](initialState)
    }
  }

  def modify(
      f: Map[String, Seq[Service[NoExtension, NoExtension]]] => Map[String, Seq[Service[NoExtension, NoExtension]]]
  )(implicit ec: ExecutionContext): Future[Map[String, Seq[Service[NoExtension, NoExtension]]]] = {
    val modifiedState = ref.updateAndGet(services => f(services))
    stateConfig.flatMap(_.local).map(_.path).foreach { path =>
      actor ! UpdateStoreFile(path, modifiedState)
    }
    FastFuture.successful(modifiedState)
  }

  def get()(implicit ec: ExecutionContext): Future[Map[String, Seq[Service[NoExtension, NoExtension]]]] = {
    FastFuture.successful(ref.get())
  }

  override def start(): Stoppable[DefaultAtomicStore] = {
    stateConfig.flatMap(_.local).foreach { config =>
      system.scheduler.schedule(0.seconds, config.writeEvery) {
        get().map { map =>
          actor ! UpdateStoreFile(config.path, map)
        }
      }
    }
    stateConfig.flatMap(_.remote).foreach { config =>
      system.scheduler.schedule(0.seconds, config.pollEvery) {
        RemoteStateFetch.fetchRemoteState(config, http, decoders).map(s => modify(_ => s)).andThen {
          case Failure(e) => logger.error(s"Error while fetching remote state", e)
        }
      }
    }
    stateConfig.flatMap(_.otoroshi).foreach { config =>
      system.scheduler.schedule(0.seconds, config.pollEvery) {
        OtoroshiStateFetch.fetchOtoroshiState(config, http).map(s => modify(_ => s)).andThen {
          case Failure(e) => logger.error(s"Error while fetching otoroshi state", e)
        }
      }
    }
    this
  }

  override def stop(): Unit = {
    system.terminate()
  }
}

class FileWriter(encoders: Encoders[NoExtension, NoExtension]) extends Actor {

  import io.circe.syntax._

  override def receive: Receive = {
    case e: UpdateStoreFile => {
      val content = e.state.values.flatten.toSeq.asJson(Encoder.encodeSeq(encoders.ServiceEncoder)).noSpaces
      Files.write(Paths.get(e.path), content.getBytes)
    }
  }
}

object FileWriter {
  def props(encoders: Encoders[NoExtension, NoExtension]): Props = Props(new FileWriter(encoders))
}

object RemoteStateFetch {

  lazy val logger = LoggerFactory.getLogger("heimdallr")

  def fetchRemoteState(
      config: RemoteStateConfig,
      http: HttpExt,
      decoders: Decoders[NoExtension, NoExtension]
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Map[String, Seq[Service[NoExtension, NoExtension]]]] = {
    val headers: List[HttpHeader] = config.headers.toList.map(t => RawHeader(t._1, t._2))
    http
      .singleRequest(
        HttpRequest(
          uri = Uri(config.url),
          method = HttpMethods.GET,
          headers = headers
        )
      )
      .flatMap { response =>
        response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _)
      }
      .flatMap { bs =>
        val body = bs.utf8String
        io.circe.parser.parse(body) match {
          case Left(e) =>
            logger.error(s"Error while parsing json from http body: ${e.message}")
            FastFuture.failed(e)
          case Right(json) =>
            json.as[Seq[Service[NoExtension, NoExtension]]](Decoder.decodeSeq(decoders.ServiceDecoder)) match {
              case Left(e) =>
                logger.error(s"Error while parsing state from http body: ${e.message}")
                FastFuture.failed(e)
              case Right(services) =>
                FastFuture.successful(services.groupBy(_.domain))
            }
        }
      }
  }
}

object OtoroshiStateFetch {

  lazy val logger = LoggerFactory.getLogger("heimdallr")

  def fetchOtoroshiApiKeys(
      config: OtoroshiStateConfig,
      http: HttpExt
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Map[String, Seq[ApiKey[NoExtension]]]] = {
    val headers: List[HttpHeader] = config.headers.toList.map(t => RawHeader(t._1, t._2))
    http
      .singleRequest(
        HttpRequest(
          uri = Uri(config.url + "/api/apikeys"),
          method = HttpMethods.GET,
          headers = headers
        )
      )
      .flatMap { response =>
        response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _)
      }
      .flatMap { bs =>
        val body = bs.utf8String
        io.circe.parser.parse(body) match {
          case Left(e) =>
            logger.error(s"Error while parsing json from http body: ${e.message}")
            FastFuture.failed(e)
          case Right(json) => {
            json.as[Seq[Json]] match {
              case Left(e) =>
                logger.error(s"Error while parsing json array from http body: ${e.message}")
                FastFuture.failed(e)
              case Right(arr) => {
                val seq: Seq[Decoder.Result[(String, ApiKey[NoExtension])]] = arr.map(_.hcursor).map { c =>
                  for {
                    clientId        <- c.downField("clientId").as[String]
                    clientSecret    <- c.downField("clientSecret").as[String]
                    clientName      <- c.downField("clientName").as[String]
                    enabled         <- c.downField("enabled").as[Boolean]
                    authorizedGroup <- c.downField("authorizedGroup").as[String]
                    metadata        <- c.downField("metadata").as[Map[String, String]]
                  } yield {
                    (authorizedGroup,
                     ApiKey[NoExtension](
                       clientId = clientId,
                       clientSecret = clientSecret,
                       name = clientName,
                       enabled = enabled,
                       metadata = metadata,
                       extension = None
                     ))
                  }
                }
                val map = seq
                  .collect {
                    case Right(tuple) => tuple
                  }
                  .groupBy(_._1)
                  .mapValues(_.map(_._2))
                FastFuture.successful(map)
              }
            }
          }
        }
      }
  }

  def fetchOtoroshiState(
      config: OtoroshiStateConfig,
      http: HttpExt
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Map[String, Seq[Service[NoExtension, NoExtension]]]] = {
    val headers: List[HttpHeader] = config.headers.toList.map(t => RawHeader(t._1, t._2))
    http
      .singleRequest(
        HttpRequest(
          uri = Uri(config.url + "/api/services"),
          method = HttpMethods.GET,
          headers = headers
        )
      )
      .flatMap { response =>
        response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _)
      }
      .flatMap { bs =>
        fetchOtoroshiApiKeys(config, http).map { keys =>
          (bs, keys)
        }
      }
      .flatMap { tuple =>
        val (bs, keys) = tuple
        val body       = bs.utf8String
        io.circe.parser.parse(body) match {
          case Left(e) =>
            logger.error(s"Error while parsing json from http body: ${e.message}")
            FastFuture.failed(e)
          case Right(json) => {
            json.as[Seq[Json]] match {
              case Left(e) =>
                logger.error(s"Error while parsing json array from http body: ${e.message}")
                FastFuture.failed(e)
              case Right(arr) => {
                val seq: Seq[Decoder.Result[Service[NoExtension, NoExtension]]] = arr.map(_.hcursor).map { c =>
                  for {
                    id                <- c.downField("id").as[String]
                    _subdomain        <- c.downField("subdomain").as[String]
                    _domain           <- c.downField("domain").as[String]
                    _env              <- c.downField("env").as[String]
                    id                <- c.downField("id").as[String]
                    groupId           <- c.downField("groupId").as[String]
                    root              <- c.downField("matchingRoot").as[Option[String]]
                    targetRoot        <- c.downField("root").as[String]
                    enabled           <- c.downField("enabled").as[Boolean]
                    metadata          <- c.downField("metadata").as[Map[String, String]]
                    additionalHeaders <- c.downField("additionalHeaders").as[Map[String, String]]
                    matchingHeaders   <- c.downField("matchingHeaders").as[Map[String, String]]
                    publicPatterns    <- c.downField("publicPatterns").as[Set[String]]
                    privatePatterns   <- c.downField("privatePatterns").as[Set[String]]
                    retry             <- c.downField("clientConfig").downField("retries").as[Int]
                    maxFailures       <- c.downField("clientConfig").downField("maxErrors").as[Int]
                    callTimeout       <- c.downField("clientConfig").downField("callTimeout").as[Int]
                    resetTimeout      <- c.downField("clientConfig").downField("sampleInterval").as[Int]
                    targets <- c.downField("targets").as[Seq[Json]].map { seq =>
                                seq.map { json =>
                                  val target = json.hcursor
                                  for {
                                    scheme <- target.downField("scheme").as[String]
                                    host   <- target.downField("host").as[String]
                                  } yield {
                                    Target(s"$scheme://$host")
                                  }
                                } collect {
                                  case Right(t) => t
                                }
                              }
                  } yield {
                    var domain = _domain
                    if (_env.nonEmpty && _env != "prod") {
                      domain = _env + "." + domain
                    }
                    if (_subdomain.nonEmpty) {
                      domain = _subdomain + "." + domain
                    }
                    Service[NoExtension, NoExtension](
                      id = id,
                      domain = domain,
                      enabled = enabled,
                      targets = targets,
                      apiKeys = keys.getOrElse(groupId, Seq.empty),
                      clientConfig = ClientConfig(
                        retry = retry,
                        maxFailures = maxFailures,
                        callTimeout = callTimeout.millis,
                        resetTimeout = resetTimeout.millis,
                      ),
                      additionalHeaders = additionalHeaders,
                      matchingHeaders = matchingHeaders,
                      targetRoot = targetRoot,
                      root = root,
                      publicPatterns = publicPatterns,
                      privatePatterns = privatePatterns,
                      metadata = metadata,
                      extension = None
                    )
                  }
                }
                val map = seq map {
                  case Left(e) =>
                    println(e.toString)
                    Left(e)
                  case Right(e) => Right(e)
                } collect {
                  case Right(service) => service
                } groupBy (_.domain)
                FastFuture.successful(map)
              }
            }
          }
        }
      }
  }
}
