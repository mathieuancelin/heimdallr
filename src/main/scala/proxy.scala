import api.AdminApi
import models._
import proxies.HttpProxy
import store.Store

class Proxy(config: ProxyConfig) {

  val store     = new Store(config.services)
  val httpProxy = new HttpProxy(config, store)
  val adminApi  = new AdminApi(config, store)

  def start(): Unit = {
    httpProxy.start()
    adminApi.start()
  }
}

object Proxy {
  def withConfig(config: ProxyConfig): Proxy = new Proxy(config)
  def fromConfigFile(path: String): Proxy    = ???
}
