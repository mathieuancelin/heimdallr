package io.heimdallr

import java.io.{File, FileInputStream}
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import akka.stream.ActorMaterializer
import akka.util.ByteString
import io.heimdallr.api.AdminApi
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.classic.joran.JoranConfigurator
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.jmx.JmxReporter
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigRenderOptions, ConfigResolveOptions}
import io.heimdallr.models._
import io.heimdallr.modules.Modules
import org.slf4j.LoggerFactory
import io.heimdallr.proxies.HttpProxy
import io.heimdallr.store.Store
import io.heimdallr.statsd.Statsd
import io.heimdallr.util.{Startable, Stoppable}

import scala.concurrent.Await
import scala.concurrent.duration._

case class Proxy(config: ProxyConfig, modules: ModulesConfig) extends Startable[Proxy] with Stoppable[Proxy] {

  val actorSystem = ActorSystem("heimdallr")
  val statsd      = new Statsd(config, actorSystem)
  val store       = new Store(config.services.groupBy(_.domain), config.state, statsd)
  val httpProxy   = new HttpProxy(config, store, modules, statsd)
  val adminApi    = new AdminApi(config, store, statsd)

  private def setupLoggers(): Unit = {
    val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    config.loggers.configPath.foreach { path =>
      loggerContext.reset()
      val configurator = new JoranConfigurator
      val configStream = new FileInputStream(new File(path))
      configurator.setContext(loggerContext)
      configurator.doConfigure(configStream)
      configStream.close()
    }
    loggerContext.getLogger("heimdallr").setLevel(Level.valueOf(config.loggers.level))
  }

  override def start(): Proxy = {
    setupLoggers()
    store.start()
    statsd.start()
    httpProxy.start()
    if (config.api.enabled) {
      adminApi.start()
    }
    this
  }

  override def stop(): Unit = {
    store.stop()
    statsd.stop()
    httpProxy.stop()
    if (config.api.enabled) {
      adminApi.stop()
    }
    actorSystem.terminate()
  }

  def stopOnShutdown(): Proxy = {
    sys.addShutdownHook {
      stop()
    }
    this
  }

  def updateState(f: Seq[Service] => Seq[Service]): Seq[Service] = {
    store.modify(m => f(m.values.toSeq.flatten).groupBy(_.domain)).values.toSeq.flatten
  }

  def getState(): Seq[Service] = {
    store.get().values.toSeq.flatten
  }
}

object Proxy {

  private val logger = LoggerFactory.getLogger("heimdallr")

  def withConfig(config: ProxyConfig, modules: ModulesConfig = Modules.defaultModules): Proxy =
    new Proxy(config, modules)
  def fromConfigPath(path: String, modules: ModulesConfig = Modules.defaultModules): Either[ConfigError, Proxy] = {
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
            case Right(config) => Right(new Proxy(config, modules))
            case Left(e)       => Left(ConfigError(e.message))
          }
      }
      system.terminate()
      either
    } else {
      fromConfigFile(new File(path))
    }
  }
  def fromConfigFile(file: File, modules: ModulesConfig = Modules.defaultModules): Either[ConfigError, Proxy] = {
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
          case Right(config) => Right(new Proxy(config, modules))
          case Left(e)       => Left(ConfigError(e.message))
        }
    }
  }
  def readProxyConfigFromFile(file: File, reload: Boolean = false): Either[ConfigError, ProxyConfig] = {
    if (reload)
      logger.info(s"Reloading configuration from file @ ${file.toPath.toString}")
    else
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
          case Right(config) => Right(config)
          case Left(e)       => Left(ConfigError(e.message))
        }
    }
  }
}
