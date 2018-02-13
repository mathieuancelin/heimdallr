# Heimdallr

A library to build modern http reverse proxies. By default supports http/http2, TLS terminaison, circuit breaker, retry, weighted round robin load balancing, api key access, etc .... Provides a pluggable module system to add new features easily.

Experimental project to try new things on http reverse proxies. Do not use in production ... yet, or not ;)

## Get it

you can fetch the last Heimdallr build from bintray an run it

```sh
wget -q --show-progress https://dl.bintray.com/mathieuancelin/heimdallr/heimdallr.jar/snapshot/heimdallr.jar
java -jar heimdallr.jar --proxy-config=/path/to/heimdallr.conf
```

or you can use the latest Docker image

```sh
docker run -p "8080:8080"  mathieuancelin-docker-heimdallr-docker.bintray.io/heimdallr:latest
```

## Use it from code

Heimdallr is designed to be embedded and enhanced through pluggable modules


```scala
import io.heimdallr.Proxy

object MyOwnProxy {

  def main(args: Array[String]): Unit = {
    Proxy
      .fromConfigPath("./heimdallr.conf") match {
        case Left(e) => println(s"error while parsing config. $e")
        case Right(proxy) => proxy.stopOnShutdown()
      }
  }
} 

```

```scala
import io.heimdallr.Proxy
import java.io.File

object MyOwnProxy {

  def main(args: Array[String]): Unit = {
    Proxy
      .fromConfigPath("https://foo.bar/heimdallr.conf") match {
        case Left(e) => println(s"error while parsing config. $e")
        case Right(proxy) => proxy.stopOnShutdown()
      }
  }
}

```

```scala
import io.heimdallr.Proxy

object MyOwnProxy {

  def main(args: Array[String]): Unit = {
    Proxy
      .fromConfigFile(new File("./heimdallr.conf")) match {
        case Left(e) => println(s"error while parsing config. $e")
        case Right(proxy) => proxy.stopOnShutdown()
      }
  }
}

```

```scala
import io.heimdallr.Proxy
import io.heimdallr.models._

object MyOwnProxy {

  def main(args: Array[String]): Unit = {
    val proxy = Proxy.withConfig(ProxyConfig(
      http = HttpConfig(
        httpPort = 8080,
        httpsPort = 8443,
        listenOn = "0.0.0.0",
      ),
      api = ApiConfig(
        httpPort = 9080,
        httpsPort = 9443,
        listenOn = "127.0.0.1",
        certPath = Some("./cert/foo.bar-cert.pem"),
        keyPath = Some("./cert.foo.bar-key.pem"),
        certPass = Some("foo")
      ),
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
        )
      )
    )).stopOnShutdown()
  }
}
```

## Modules

Heimdallr provides extension points to add new feature on top of basic http proxies

```scala
def withConfig(config: ProxyConfig, modules: ModulesConfig = Modules.defaultModules): Proxy
def fromConfigPath(path: String, modules: ModulesConfig = Modules.defaultModules): Either[ConfigError, Proxy]
def fromConfigFile(file: File, modules: ModulesConfig = Modules.defaultModules): Either[ConfigError, Proxy]
```

modules includes the following possibilities 

```scala

// can handle construction mode, maintenance mode
trait PreconditionModule extends Module {
  def validatePreconditions(reqId: String, service: Service, request: HttpRequest): Either[HttpResponse, Unit]
}

// can handle pass by api, pass by auth0, throttling, gobal throtthling, etc ...
trait ServiceAccessModule extends Module {
  def access(reqId: String, service: Service, request: HttpRequest): WithApiKeyOrNot
}

// can handle headers additions, like JWT header, request id, API quotas, etc ... to target
trait HeadersInTransformationModule extends Module {
  def transform(reqId: String,
                host: String,
                service: Service,
                target: Target,
                request: HttpRequest,
                waon: WithApiKeyOrNot,
                headers: List[HttpHeader]): List[HttpHeader]
}

// can handle headers additions, like, API quotas, etc ... on target responses
trait HeadersOutTransformationModule extends Module {
  def transform(reqId: String,
                host: String,
                service: Service,
                target: Target,
                request: HttpRequest,
                waon: WithApiKeyOrNot,
                proxyLatency: Long,
                targetLatency: Long,
                headers: List[HttpHeader]): List[HttpHeader]
}

// can handle custom template errors
trait ErrorRendererModule extends Module {
  def render(reqId: String, status: Int, message: String, service: Option[Service], request: HttpRequest): HttpResponse
}

// can handle canary mode
trait TargetSetChooserModule extends Module {
  def choose(reqId: String, service: Service, request: HttpRequest): Seq[Target]
}
````

`Modules.defaultModules` provides the following features

```scala
DefaultPreconditionModule // return error if service is not enabled
DefaultServiceAccessModule // handle access by ApiKey using various headers
DefaultHeadersInTransformationModule // add new headers on request like X-Request-Id, X-Fowarded-Host, X-Fowarded-Scheme
DefaultHeadersOutTransformationModule // add new headers on response like X-Proxy-Latency, X-Target-Latency
DefaultErrorRendererModule // return errors as json responses
DefaultTargetSetChooserModule // use service targets for loadbalancing
```

## Build it

```sh
sh ./scripts/build.sh server
```

or to build everything

```sh
sh ./scripts/build.sh all
```

## Helpers

for http2

```sh
sbt 
~reStart --- -javaagent:/Users/mathieuancelin/.ivy2/cache/org.mortbay.jetty.alpn/jetty-alpn-agent/jars/jetty-alpn-agent-2.0.6.jar

curl2 -k -H 'Host: test.foo.bar' https://127.0.0.1:8443 --include
curl2 -k --http2-H 'Host: test.foo.bar' https://127.0.0.1:8443 --include
curl2 -k -v -H 'Host: test.foo.bar' https://127.0.0.1:8443 --include
```

```sh
wrk -t1 -c1 -d20s -H "Host: test.foo.bar" http://127.0.0.1:8080/ >> /dev/null
wrk -t2 -c200 -d60s -H "Host: test.foo.bar" --latency http://127.0.0.1:8080/
```

```sh
docker kill $(docker ps -q)
docker run -d -p "8081:80" emilevauge/whoami
docker run -d -p "8082:80" emilevauge/whoami
docker run -d -p "8083:80" emilevauge/whoami
```

## Waiting for 

* https://github.com/akka/akka-http/issues/1843
* https://github.com/akka/akka-http/issues/530

## Features

- [x] write some usage docs in readme
- [ ] built-in kafka support as commands input
- [ ] built-in kafka support as logs output
- [ ] statsd support (include metrics in statsd actor for REST metrics)
- [ ] dynamic TLS
- [ ] add extra typesafe attributes to services and apikey for modules ???
- [ ] handle wildcard matching hosts
- [-] handle serde calls for services with pluggables modules
- [x] otoroshi config poll module
- [x] find a name for the project
- [x] admin API complete on another port. Add service to serve this api
- [x] api based on diff commands
- [x] scan config file for changes and reload
- [x] docker dev
- [x] session.sh
- [x] build.sh
- [x] travis.yml
- [x] upload on bintray
- [x] API to change state of a Proxy instance
- [x] API to get one service per command
- [x] read API rest style to get services
- [x] read API rest style to get one service
- [x] disable api from config
- [x] config for state file (period for writes, enabled or not, etc ...)
- [x] demo mode (--demo) or use config file ./proxy.conf
- [x] remote config file
- [x] remote state (with polling support from conf)
- [x] ProxyConfig object for Api integration
- [x] withConfig(ProxyConfig)
- [x] withConfigFrom(path)
- [x] additional headers
- [x] metrics JMX or statsd with metrics (maybe statsd integration already provided)
- [x] apikeys on the service (no quotas), clientId, clientSecret, enabled, name
- [x] pass if public or apikey
- [x] api integration + main class 
- [x] save state to file periodically
- [x] circuit breaker config in service
- [x] config for ssl
- [x] matching root
- [x] target root
- [x] public / private stuff
- [x] support for WS
- [x] support JWT auth
- [x] start https only if certificate provided
- [x] smaller https password
- [x] pass --proxy.config=???
- [x] pass --proxy.config=???
- [x] shutdown hook in main
- [x] handle service access preconditions with pluggable modules
- [x] handle service access with pluggable modules (apikey + throttling, global throtthling, ip filtering)
- [x] handle headers out manipulation with pluggable modules
- [x] handle errors rendering with pluggable modules
- [x] handle target set choice with pluggable modules