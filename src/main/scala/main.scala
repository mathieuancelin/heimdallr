import models.{ApiKey, ProxyConfig, Service, Target}

object Main {
  def main(args: Array[String]) {
    val config = ProxyConfig(
      services = Map(
        "test.foo.bar" -> Service(
          "UjvBYvkrqADUpq1N",
          "test.foo.bar",
          Seq(
            Target.weighted("http://127.0.0.1:8081", 1),
            Target.weighted("http://127.0.0.1:8082", 1),
            Target.weighted("http://127.0.0.1:8083", 1)
          ),
          Seq.empty[ApiKey]
        )
      )
    )
    new Proxy(config).start()
  }
}
