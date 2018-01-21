import models.{ProxyConfig, Service, Target}

object Main {
  def main(args: Array[String]) {
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
    new Proxy(config).start()
  }
}
