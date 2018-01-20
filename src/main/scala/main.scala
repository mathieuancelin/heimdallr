import models.{ProxyConfig, Service, Target}

object Main {
  def main(args: Array[String]) {
    val config = ProxyConfig(
      services = Map(
        "test.foo.bar" -> Service(
          id = "UjvBYvkrqADUpq1N",
          domain = "test.foo.bar",
          targets = Seq(
            Target("http://127.0.0.1:8081"),
            Target("http://127.0.0.1:8082"),
            Target("http://127.0.0.1:8083")
          ),
          headers = Map(
            "Authorization" -> "basic 1234"
          )
        )
      )
    )
    // println(config.pretty)
    new Proxy(config).start()
  }
}
