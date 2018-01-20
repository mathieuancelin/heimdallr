import models.{ApiKey, ProxyConfig, Service, Target}

object Main {
  def main(args: Array[String]) {
    val config = ProxyConfig(
      services = Map(
        "test.foo.bar" -> Service(
          "UjvBYvkrqADUpq1N",
          "test.foo.bar",
          Seq(
            Target("http://127.0.0.1:8081"),
            Target("http://127.0.0.1:8082"),
            Target("http://127.0.0.1:8083")
          ),
          Seq.empty[ApiKey]
        )
      )
    )
    println(config.pretty)
    new Proxy(config).start()
  }
}
