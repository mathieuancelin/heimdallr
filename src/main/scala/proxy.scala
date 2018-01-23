import java.io.{File, FileInputStream}
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import akka.stream.ActorMaterializer
import akka.util.ByteString
import api.AdminApi
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.jmx.JmxReporter
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigRenderOptions, ConfigResolveOptions}
import models._
import org.slf4j.LoggerFactory
import proxies.HttpProxy
import store.Store
import util.{Startable, Stoppable}

import scala.concurrent.Await
import scala.concurrent.duration._

class Proxy(config: ProxyConfig) extends Startable[Proxy] with Stoppable[Proxy] {

  val metrics   = new MetricRegistry()
  val store     = new Store(config.services.groupBy(_.domain), config.state, metrics)
  val httpProxy = new HttpProxy(config, store, metrics)
  val adminApi  = new AdminApi(config, store, metrics)

  private val jmxReporter = JmxReporter
    .forRegistry(metrics)
    .convertRatesTo(TimeUnit.SECONDS)
    .convertDurationsTo(TimeUnit.MILLISECONDS)
    .build()

  private def setupLoggers(): Unit = {
    config.logConfigPath.foreach { path =>
      val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
      loggerContext.reset()
      val configurator = new JoranConfigurator
      val configStream = new FileInputStream(new File(path))
      configurator.setContext(loggerContext)
      configurator.doConfigure(configStream)
      configStream.close()
    }
  }

  override def start(): Stoppable[Proxy] = {
    setupLoggers()
    store.start()
    httpProxy.start()
    if (config.api.enabled) {
      adminApi.start()
    }
    jmxReporter.start()
    this
  }

  override def stop(): Unit = {
    store.stop()
    httpProxy.stop()
    if (config.api.enabled) {
      adminApi.stop()
    }
    jmxReporter.stop()
  }
}

object Proxy {

  private val logger = LoggerFactory.getLogger("proxy")

  def withConfig(config: ProxyConfig): Proxy = new Proxy(config)
  def fromConfigPath(path: String): Either[ConfigError, Proxy] = {
    if (path.startsWith("http://") || path.startsWith("https://")) {
      logger.info(s"Loading configuration from http resource @ $path")
      val system        = ActorSystem()
      implicit val ec   = system.dispatcher
      implicit val mat  = ActorMaterializer.create(system)
      implicit val http = Http(system)
      val response = Await.result(http.singleRequest(
                                    HttpRequest(
                                      method = HttpMethods.GET,
                                      uri = Uri(path)
                                    )
                                  ),
                                  60.seconds)
      val body       = Await.result(response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map(_.utf8String), 60.seconds)
      val withLoader = ConfigParseOptions.defaults.setClassLoader(getClass.getClassLoader)
      val conf = ConfigFactory
        .systemProperties()
        .withFallback(ConfigFactory.systemEnvironment())
        .withFallback(ConfigFactory.parseString(body, withLoader))
        .resolve(ConfigResolveOptions.defaults)
      val jsonConf = conf.root().render(ConfigRenderOptions.concise())
      val either = io.circe.parser.parse(jsonConf) match {
        case Left(e) => Left(ConfigError(e.message))
        case Right(json) =>
          Decoders.ProxyConfigDecoder.decodeJson(json) match {
            case Right(config) => Right(new Proxy(config))
            case Left(e)       => Left(ConfigError(e.message))
          }
      }
      system.terminate()
      either
    } else {
      fromConfigFile(new File(path))
    }
  }
  def fromConfigFile(file: File): Either[ConfigError, Proxy] = {
    logger.info(s"Loading configuration from file @ ${file.toPath.toString}")
    val withLoader = ConfigParseOptions.defaults.setClassLoader(getClass.getClassLoader)
    val conf = ConfigFactory
      .systemProperties()
      .withFallback(ConfigFactory.systemEnvironment())
      .withFallback(ConfigFactory.parseFile(file, withLoader))
      .resolve(ConfigResolveOptions.defaults)
    val jsonConf = conf.root().render(ConfigRenderOptions.concise())
    io.circe.parser.parse(jsonConf) match {
      case Left(e) => Left(ConfigError(e.message))
      case Right(json) =>
        Decoders.ProxyConfigDecoder.decodeJson(json) match {
          case Right(config) => Right(new Proxy(config))
          case Left(e)       => Left(ConfigError(e.message))
        }
    }
  }
}
