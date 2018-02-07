import models.{ApiKey, ProxyConfig, Service, Target}
import org.slf4j.LoggerFactory
import java.io.File;

object Main {

  def watchFile(file: File)(f: File => Unit): Unit = {

    import org.apache.commons.io.monitor.FileAlterationListener;
    import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
    import org.apache.commons.io.monitor.FileAlterationMonitor;
    import org.apache.commons.io.monitor.FileAlterationObserver;

    val observer = new FileAlterationObserver(file.getParentFile());
    val monitor  = new FileAlterationMonitor(500);
    val listener = new FileAlterationListenerAdaptor() {
      override def onFileChange(changedFile: File) {
        if (changedFile.getAbsolutePath == file.getAbsolutePath) {
          f(file)
        }
      }
    }
    observer.addListener(listener)
    monitor.addObserver(observer)
    monitor.start()
    sys.addShutdownHook {
      monitor.stop()
    }
  }

  def main(args: Array[String]) {

    val logger = LoggerFactory.getLogger("heimdallr")

    val demoConfig = ProxyConfig(
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
          publicPatterns = Set("/*")
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
          publicPatterns = Set("/*")
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
          publicPatterns = Set("/*")
        )
      )
    )

    // println(demoConfig.pretty)

    args
      .find(_.equals("--demo"))
      .map(_ => Proxy.withConfig(demoConfig).copy(modules = modules.Modules.defaultModules).start().stopOnShutdown())
      .orElse(args.find(_.startsWith("--proxy.config=")).map(_.replace("--proxy.config=", "")).map { path =>
        Proxy.fromConfigPath(path) match {
          case Left(e) => logger.error(s"Error while loading config file @ $path: $e")
          case Right(proxy) => {
            val configFile   = new File(path)
            val startedProxy = proxy.copy(modules = modules.Modules.defaultModules).start().stopOnShutdown()
            watchFile(configFile) { f =>
              Proxy.readProxyConfigFromFile(configFile, true) match {
                case Left(e)       => logger.error(s"Error while loading config file @ $path: $e")
                case Right(config) => startedProxy.updateState(_ => config.services)
              }
            }
          }
        }
      })
      .getOrElse {
        val path = "./heimdallr.conf"
        Proxy.fromConfigPath(path) match {
          case Left(e) => logger.error(s"Error while loading config file @ $path: $e")
          case Right(proxy) => {
            val configFile   = new File(path)
            val startedProxy = proxy.copy(modules = modules.Modules.defaultModules).start().stopOnShutdown()
            watchFile(configFile) { f =>
              Proxy.readProxyConfigFromFile(configFile, true) match {
                case Left(e)       => logger.error(s"Error while loading config file @ $path: $e")
                case Right(config) => startedProxy.updateState(_ => config.services)
              }
            }
          }
        }
      }
  }
}
