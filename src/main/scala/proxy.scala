import java.io.{File, FileInputStream}
import java.util.concurrent.TimeUnit

import api.AdminApi
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.jmx.JmxReporter
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import models._
import org.slf4j.LoggerFactory
import proxies.HttpProxy
import store.Store
import util.{Startable, Stoppable}

class Proxy(config: ProxyConfig) extends Startable[Proxy] with Stoppable[Proxy] {

  val metrics   = new MetricRegistry()
  val store     = new Store(config.services.groupBy(_.domain), config.statePath, metrics)
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
    adminApi.start()
    jmxReporter.start()
    this
  }

  override def stop(): Unit = {
    store.stop()
    httpProxy.stop()
    adminApi.stop()
    jmxReporter.stop()
  }
}

object Proxy {
  def withConfig(config: ProxyConfig): Proxy = new Proxy(config)
  def fromConfigPath(path: String): Either[ConfigError, Proxy] = {
    fromConfigFile(new File(path))
  }
  def fromConfigFile(file: File): Either[ConfigError, Proxy] = {
    val conf     = ConfigFactory.parseFile(file)
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
