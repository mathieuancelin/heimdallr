import models.{ProxyConfig, Service, Target}
import org.slf4j.LoggerFactory

object Main {
  def main(args: Array[String]) {
    val logger = LoggerFactory.getLogger("proxy")
    args.find(_.startsWith("-Dproxy.config=")).map(_.replace("-Dproxy.config=", "")).map { path =>
      logger.info(s"Loading from $path")
      // Proxy.fromConfigPath("/Users/mathieuancelin/Desktop/reverse-proxy/src/main/resources/proxy.conf")
      Proxy.fromConfigPath(path) match {
        case Left(e) => logger.error(s"Error while loading config file: $e")
        case Right(proxy) => proxy.start()
      }
    } getOrElse {
      val config = ProxyConfig(
        services = Seq(
          Service(
            id = "UjvBYvkrqADUpq1N",
            domain = "test.foo.bar",
            targets = Seq(
              Target("http://127.0.0.1:8081"),
              Target("http://127.0.0.1:8082"),
              Target("http://127.0.0.1:8083")
            ),
            headers = Map(
              "Authorization" -> "basic 1234"
            ),
            publicPatterns = Seq("/*")
          ),
          Service(
            id = "UjvBYvkrqADUpq1N2",
            domain = "test.foo.bar",
            root = Some("/foo"),
            targets = Seq(
              Target("http://127.0.0.1:8081")
            ),
            headers = Map(
              "Worked" -> "Yeah"
            ),
            publicPatterns = Seq("/*")
          )
        )
      )
      // println(config.pretty)
      Proxy.withConfig(config).start()
    }
  }
}
