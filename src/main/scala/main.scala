import models.{ApiKey, ProxyConfig, Service, Target}
import org.slf4j.LoggerFactory

object Main {
  def main(args: Array[String]) {
    val logger = LoggerFactory.getLogger("proxy")
    args.find(_.startsWith("--proxy.config=")).map(_.replace("--proxy.config=", "")).map { path =>
      logger.info(s"Loading configuration from file @ $path")
      // Proxy.fromConfigPath("/Users/mathieuancelin/Desktop/reverse-proxy/src/main/resources/proxy.conf")
      Proxy.fromConfigPath(path) match {
        case Left(e)      => logger.error(s"Error while loading config file @ $path: $e")
        case Right(proxy) => proxy.start().stopOnShutdown()
      }
    } getOrElse {
      val config = ProxyConfig(
        // statePath = Some("./state.json"),
        services = Seq(
          Service(
            id = "load-balancing-test",
            domain = "test.foo.bar",
            targets = Seq(
              Target("http://127.0.0.1:8081"),
              Target("http://127.0.0.1:8082"),
              Target("http://127.0.0.1:8083")
            ),
            additionalHeaders = Map(
              "Authorization" -> "basic 1234"
            ),
            publicPatterns = Seq("/*")
          ),
          Service(
            id = "matching-root-test",
            domain = "test.foo.bar",
            root = Some("/foo"),
            targets = Seq(
              Target("http://127.0.0.1:8081")
            ),
            additionalHeaders = Map(
              "Worked" -> "Yeah"
            ),
            publicPatterns = Seq("/*")
          ),
          Service(
            id = "admin-api",
            domain = "admin-api.foo.bar",
            targets = Seq(
              Target("http://127.0.0.1:9080")
            ),
            apiKeys = Seq(
              ApiKey(
                clientId = "SIVt3w0khFY5qP92",
                clientSecret = "KVJxk0huwCj8984MZ9sxCXhqV7PUwWK5",
                name = "admin-apikey",
                enabled = true
              )
            )
          ),
          Service(
            id = "ws-test",
            domain = "ws.foo.bar",
            targets = Seq(
              Target("http://echo.websocket.org:80")
            ),
            publicPatterns = Seq("/*")
          )
        )
      )
      // println(config.pretty)
      Proxy.withConfig(config).start().stopOnShutdown()
    }
  }
}
