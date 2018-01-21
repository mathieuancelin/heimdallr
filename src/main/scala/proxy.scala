import java.io.File
import java.util.concurrent.TimeUnit

import api.AdminApi
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.jmx.JmxReporter
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import models._
import proxies.HttpProxy
import store.Store

class Proxy(config: ProxyConfig) {

  val metrics   = new MetricRegistry()
  val store     = new Store(config.services.groupBy(_.domain), metrics)
  val httpProxy = new HttpProxy(config, store, metrics)
  val adminApi  = new AdminApi(config, store, metrics)

  private val jmxReporter = JmxReporter
    .forRegistry(metrics)
    .convertRatesTo(TimeUnit.SECONDS)
    .convertDurationsTo(TimeUnit.MILLISECONDS)
    .build()

  def start(): Unit = {
    httpProxy.start()
    adminApi.start()
    jmxReporter.start()
  }
}

object Proxy {
  def withConfig(config: ProxyConfig): Proxy = new Proxy(config)
  def fromConfigPath(path: String): Either[ConfigError, Proxy]    = {
    fromConfigFile(new File(path))
  }
  def fromConfigFile(file: File): Either[ConfigError, Proxy]    = {
    val conf = ConfigFactory.parseFile(file)
    val jsonConf = conf.root().render(ConfigRenderOptions.concise())
    io.circe.parser.parse(jsonConf) match {
      case Left(e) => Left(ConfigError(e.message))
      case Right(json) => Decoders.ProxyConfigDecoder.decodeJson(json) match {
        case Right(config) => Right(new Proxy(config))
        case Left(e) => Left(ConfigError(e.message))
      }
    }
  }
}
