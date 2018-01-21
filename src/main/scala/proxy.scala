import java.util.concurrent.TimeUnit

import api.AdminApi
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.jmx.JmxReporter
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
  def fromConfigFile(path: String): Proxy    = ???
}
