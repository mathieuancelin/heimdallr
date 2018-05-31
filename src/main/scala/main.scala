package io.heimdallr

import java.io.File
import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import akka.stream.ActorMaterializer
import akka.util.ByteString
import io.heimdallr.models.{ApiKey, ProxyConfig, Service, Target}
import io.heimdallr.modules.default.{DefaultModules, NoExtension}
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, ExecutionContext, Future}

object Main {

  def watchFile(file: File)(f: File => Unit): Unit = {

    import org.apache.commons.io.monitor.{FileAlterationListenerAdaptor, FileAlterationMonitor, FileAlterationObserver};

    val observer = new FileAlterationObserver(file.getParentFile())
    val monitor  = new FileAlterationMonitor(500)
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

    implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

    val logger = LoggerFactory.getLogger("heimdallr")

    val demoConfig = ProxyConfig[NoExtension, NoExtension](
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

    def fetch(url: String): ProxyConfig[NoExtension, NoExtension] = {
      import scala.concurrent.duration._
      val system       = ActorSystem("config-fetch-system")
      val http         = Http.apply()(system)
      implicit val mat = ActorMaterializer.create(system)
      val fu = http
        .singleRequest(
          HttpRequest(
            uri = Uri(url),
            method = HttpMethods.GET
          )
        )
        .flatMap { resp =>
          resp.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { body =>
            Proxy.readProxyConfigFromString(body.utf8String, NoExtension) match {
              case Left(e)       => throw new RuntimeException(s"Error while loading config from url @ $url: $e")
              case Right(config) => config
            }
          }
        }
      val config = Await.result(fu, 10.seconds)
      system.terminate()
      config
    }

    args
      .find(_.equals("--demo"))
      .map(
        _ =>
          Proxy
            .withConfig[NoExtension, NoExtension](demoConfig, DefaultModules(demoConfig))
            .start()
            .stopOnShutdown()
      )
      .orElse(args.find(_.startsWith("--proxy.config=")).map(_.replace("--proxy.config=", "")).map { path =>
        Proxy.fromConfigPath[NoExtension, NoExtension](path, DefaultModules.apply) match {
          case Left(e) => logger.error(s"Error while loading config file @ $path: $e")
          case Right(proxy) => {
            val configFile   = new File(path)
            val startedProxy = proxy.start().stopOnShutdown()
            watchFile(configFile) { f =>
              Proxy.readProxyConfigFromFile(configFile, true, NoExtension) match {
                case Left(e) => logger.error(s"Error while loading config file @ $path: $e")
                case Right(config) =>
                  startedProxy.updateState(_ => config.services)
              }
            }
          }
        }
      })
      .orElse(args.find(_.startsWith("--proxy.config.url=")).map(_.replace("--proxy.config.url=", "")).map { url =>
        val config = fetch(url)
        Proxy
          .withConfig[NoExtension, NoExtension](config, DefaultModules(config))
          .start()
          .stopOnShutdown()
      })
      .getOrElse {
        val path = "./heimdallr.conf"
        Proxy.fromConfigPath[NoExtension, NoExtension](path, DefaultModules.apply) match {
          case Left(e) => logger.error(s"Error while loading config file @ $path: $e")
          case Right(proxy) => {
            val configFile   = new File(path)
            val startedProxy = proxy.start().stopOnShutdown()
            watchFile(configFile) { f =>
              Proxy.readProxyConfigFromFile(configFile, true, NoExtension) match {
                case Left(e)       => logger.error(s"Error while loading config file @ $path: $e")
                case Right(config) => startedProxy.updateState(_ => config.services)
              }
            }
          }
        }
      }
  }
}
