package io.heimdallr.store

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.{Http, HttpExt}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.util.FastFuture
import akka.stream._
import akka.util.ByteString
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.heimdallr.models._
import io.heimdallr.statsd._
import org.slf4j.LoggerFactory
import io.heimdallr.util.{Startable, Stoppable}

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Failure

case class UpdateStoreFile[A, K](path: String, state: Map[String, Seq[Service[A, K]]])

trait Store[A, K] extends Startable[Store[A, K]] with Stoppable[Store[A, K]] {
  def modify(f: Map[String, Seq[Service[A, K]]] => Map[String, Seq[Service[A, K]]])(
      implicit ec: ExecutionContext
  ): Future[Map[String, Seq[Service[A, K]]]]
  def get()(implicit ec: ExecutionContext): Future[Map[String, Seq[Service[A, K]]]]
}

class AtomicStore[A, K](initialState: Map[String, Seq[Service[A, K]]] = Map.empty[String, Seq[Service[A, K]]],
                        stateConfig: Option[StateConfig],
                        statsd: Statsd[A, K],
                        encoders: Encoders[A, K],
                        decoders: Decoders[A, K])
    extends Store[A, K]
    with Startable[Store[A, K]]
    with Stoppable[Store[A, K]] {

  private implicit val system       = ActorSystem()
  private implicit val executor     = system.dispatcher
  private implicit val materializer = ActorMaterializer.create(system)
  private implicit val http         = Http(system)

  private val actor = system.actorOf(FileWriter.props(encoders))

  lazy val logger = LoggerFactory.getLogger("heimdallr")

  private val ref: AtomicReference[Map[String, Seq[Service[A, K]]]] = {
    if (stateConfig.isDefined) {
      if (stateConfig.get.isRemote) {
        new AtomicReference[Map[String, Seq[Service[A, K]]]](initialState)
      } else if (stateConfig.get.isOtoroshi) {
        new AtomicReference[Map[String, Seq[Service[A, K]]]](initialState)
      } else {
        val config = stateConfig.get.local
        new AtomicReference[Map[String, Seq[Service[A, K]]]](
          config
            .map(c => new File(c.path))
            .filter(_.exists())
            .map { file =>
              io.circe.parser.parse(new String(Files.readAllBytes(file.toPath))) match {
                case Left(e) =>
                  logger.error(s"Error while parsing state file: ${e.message}")
                  initialState
                case Right(json) =>
                  json.as[Seq[Service[A, K]]](Decoder.decodeSeq(decoders.ServiceDecoder)) match {
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
      new AtomicReference[Map[String, Seq[Service[A, K]]]](initialState)
    }
  }

  def modify(
      f: Map[String, Seq[Service[A, K]]] => Map[String, Seq[Service[A, K]]]
  )(implicit ec: ExecutionContext): Future[Map[String, Seq[Service[A, K]]]] = {
    statsd.increment("store-reads")
    val modifiedState = ref.updateAndGet(services => f(services))
    stateConfig.flatMap(_.local).map(_.path).foreach { path =>
      actor ! UpdateStoreFile(path, modifiedState)
    }
    FastFuture.successful(modifiedState)
  }

  def get()(implicit ec: ExecutionContext): Future[Map[String, Seq[Service[A, K]]]] = {
    statsd.increment("store-writes")
    FastFuture.successful(ref.get())
  }

  override def start(): Stoppable[Store[A, K]] = {
    stateConfig.flatMap(_.local).foreach { config =>
      system.scheduler.schedule(0.seconds, config.writeEvery) {
        get().map { map =>
          actor ! UpdateStoreFile(config.path, map)
        }
      }
    }
    stateConfig.flatMap(_.remote).foreach { config =>
      system.scheduler.schedule(0.seconds, config.pollEvery) {
        RemoteStateFetch.fetchRemoteState[A, K](config, http, decoders).map(s => modify(_ => s)).andThen {
          case Failure(e) => logger.error(s"Error while fetching remote state", e)
        }
      }
    }
    stateConfig.flatMap(_.otoroshi).foreach { config =>
      system.scheduler.schedule(0.seconds, config.pollEvery) {
        OtoroshiStateFetch.fetchOtoroshiState[A, K](config, http).map(s => modify(_ => s)).andThen {
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

class FileWriter[A, K](encoders: Encoders[A, K]) extends Actor {

  import io.circe.syntax._

  override def receive: Receive = {
    case e: UpdateStoreFile[A, K] => {
      val content = e.state.values.flatten.toSeq.asJson(Encoder.encodeSeq(encoders.ServiceEncoder)).noSpaces
      Files.write(Paths.get(e.path), content.getBytes)
    }
  }
}

object FileWriter {
  def props[A, K](encoders: Encoders[A, K]): Props = Props(new FileWriter(encoders))
}

object RemoteStateFetch {

  lazy val logger = LoggerFactory.getLogger("heimdallr")

  def fetchRemoteState[A, K](
      config: RemoteStateConfig,
      http: HttpExt,
      decoders: Decoders[A, K]
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Map[String, Seq[Service[A, K]]]] = {
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
            json.as[Seq[Service[A, K]]](Decoder.decodeSeq(decoders.ServiceDecoder)) match {
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

  def fetchOtoroshiApiKeys[K](
      config: OtoroshiStateConfig,
      http: HttpExt
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Map[String, Seq[ApiKey[K]]]] = {
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
                val seq: Seq[Decoder.Result[(String, ApiKey[K])]] = arr.map(_.hcursor).map { c =>
                  for {
                    clientId        <- c.downField("clientId").as[String]
                    clientSecret    <- c.downField("clientSecret").as[String]
                    clientName      <- c.downField("clientName").as[String]
                    enabled         <- c.downField("enabled").as[Boolean]
                    authorizedGroup <- c.downField("authorizedGroup").as[String]
                    metadata        <- c.downField("metadata").as[Map[String, String]]
                  } yield {
                    (authorizedGroup,
                     ApiKey[K](
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

  def fetchOtoroshiState[A, K](
      config: OtoroshiStateConfig,
      http: HttpExt
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Map[String, Seq[Service[A, K]]]] = {
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
        fetchOtoroshiApiKeys[K](config, http).map { keys =>
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
                val seq: Seq[Decoder.Result[Service[A, K]]] = arr.map(_.hcursor).map { c =>
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
                    Service[A, K](
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
