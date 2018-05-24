package io.heimdallr

import java.io.{File, FileInputStream}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import akka.stream.ActorMaterializer
import akka.util.ByteString
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.{Level, LoggerContext}
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigRenderOptions, ConfigResolveOptions}
import io.heimdallr.api.AdminApi
import io.heimdallr.models._
import io.heimdallr.modules._
import io.heimdallr.modules.default._
import io.heimdallr.proxies.HttpProxy
import io.heimdallr.statsd.Statsd
import io.heimdallr.util.{Startable, Stoppable}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

case class Proxy[A, K](config: ProxyConfig[A, K], modules: Modules[A, K])
    extends Startable[Proxy[A, K]]
    with Stoppable[Proxy[A, K]] {

  val actorSystem = ActorSystem("heimdallr")
  val encoders    = new Encoders[A, K](modules.extensions)
  val decoders    = new Decoders[A, K](modules.extensions)
  val commands    = new Commands[A, K](decoders)
  val statsd      = new Statsd[A, K](config, actorSystem)
  val httpProxy   = new HttpProxy(config, modules, statsd)
  val adminApi    = new AdminApi[A, K](config, modules, statsd, commands, encoders)

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

  override def start(): Proxy[A, K] = {
    setupLoggers()
    statsd.start()
    httpProxy.start()
    if (config.api.enabled) {
      adminApi.start()
    }
    this
  }

  def startAndWait(): Proxy[A, K] = {
    import scala.concurrent.duration._
    setupLoggers()
    statsd.start()
    httpProxy.start()
    Await.result(httpProxy.boundHttp.future, 60.seconds)
    if (config.api.enabled) {
      adminApi.start()
      Await.result(adminApi.boundHttp.future, 60.seconds)
    }
    this
  }

  override def stop(): Unit = {
    statsd.stop()
    httpProxy.stop()
    if (config.api.enabled) {
      adminApi.stop()
    }
    actorSystem.terminate()
  }

  def stopOnShutdown(): Proxy[A, K] = {
    sys.addShutdownHook {
      stop()
    }
    this
  }

  def updateState(
      f: Seq[Service[A, K]] => Seq[Service[A, K]]
  )(implicit ec: ExecutionContext): Future[Seq[Service[A, K]]] = {
    for {
      services <- modules.modules.ServiceStore.getAllServices()
      res      <- modules.modules.ServiceStore.setAllServices(f(services.values.toSeq.flatten).groupBy(_.domain))
    } yield {
      res.values.toSeq.flatten
    }
  }

  def getState()(implicit ec: ExecutionContext): Future[Seq[Service[A, K]]] = {
    modules.modules.ServiceStore.getAllServices().map(_.values.toSeq.flatten)
  }
}

object Proxy {

  private val logger = LoggerFactory.getLogger("heimdallr")

  def defaultWithConfig(config: ProxyConfig[NoExtension, NoExtension]): Proxy[NoExtension, NoExtension] =
    withConfig(config, DefaultModules(config))
  def defaultFromConfigPath(path: String): Either[ConfigError, Proxy[NoExtension, NoExtension]] =
    fromConfigPath(path, DefaultModules.apply)
  def defaultFromConfigFile(file: File): Either[ConfigError, Proxy[NoExtension, NoExtension]] =
    fromConfigFile(file, DefaultModules.apply)

  def withConfig[A, K](config: ProxyConfig[A, K], modules: Modules[A, K]): Proxy[A, K] =
    new Proxy[A, K](config, modules)

  def fromConfigPath[A, K](path: String,
                           _modules: ProxyConfig[A, K] => Modules[A, K]): Either[ConfigError, Proxy[A, K]] = {
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
          val decoders = new Decoders[A, K](_modules(ProxyConfig()).extensions) // ARF !!!
          decoders.ProxyConfigDecoder.decodeJson(json) match {
            case Right(config) => Right(new Proxy(config, _modules(config)))
            case Left(e)       => Left(ConfigError(e.message))
          }
      }
      system.terminate()
      either
    } else {
      fromConfigFile(new File(path), _modules)
    }
  }

  def fromConfigFile[A, K](file: File,
                           _modules: ProxyConfig[A, K] => Modules[A, K]): Either[ConfigError, Proxy[A, K]] = {
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
        val decoders = new Decoders[A, K](_modules(ProxyConfig()).extensions) // ARF !!!
        decoders.ProxyConfigDecoder.decodeJson(json) match {
          case Right(config) => Right(new Proxy(config, _modules(config)))
          case Left(e)       => Left(ConfigError(e.message))
        }
    }
  }

  def readProxyConfigFromFile[A, K](file: File,
                                    reload: Boolean = false,
                                    extensions: Extensions[A, K]): Either[ConfigError, ProxyConfig[A, K]] = {
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
        val decoders = new Decoders[A, K](extensions)
        decoders.ProxyConfigDecoder.decodeJson(json) match {
          case Right(config) => Right(config)
          case Left(e)       => Left(ConfigError(e.message))
        }
    }
  }

  def readProxyConfigFromString[A, K](content: String,
                                    extensions: Extensions[A, K]): Either[ConfigError, ProxyConfig[A, K]] = {
    val withLoader = ConfigParseOptions.defaults.setClassLoader(getClass.getClassLoader)
    val conf = ConfigFactory
      .systemProperties()
      .withFallback(ConfigFactory.systemEnvironment())
      .withFallback(ConfigFactory.parseString(content, withLoader))
      .resolve(ConfigResolveOptions.defaults)
    val jsonConf = conf.root().render(ConfigRenderOptions.concise())
    io.circe.parser.parse(jsonConf) match {
      case Left(e) => Left(ConfigError(e.message))
      case Right(json) =>
        val decoders = new Decoders[A, K](extensions)
        decoders.ProxyConfigDecoder.decodeJson(json) match {
          case Right(config) => Right(config)
          case Left(e)       => Left(ConfigError(e.message))
        }
    }
  }
}
