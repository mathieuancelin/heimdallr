# reverse-proxy

Experimental project to try new things on http reverse proxies. Do not use in production ... yet ;)

## Features

- [ ] find a name for the project
- [x] admin API complete on another port. Add service to serve this api
- [x] api based on diff commands
- [ ] write some docs in readme
- [ ] built-in kafka support as commands input
- [ ] built-in kafka support as logs output
- [ ] dynamic TLS
- [x] disable api from config
- [x] config for state file (period for writes, enabled or not, etc ...)
- [x] demo mode (--demo) or use config file ./proxy.conf
- [x] remote config file
- [x] remote state (with polling support from conf)
- [ ] statsd support (include metrics in statsd actor for REST metrics)
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
- [ ] handle service access preconditions with pluggable podules
- [ ] handle service access with pluggable modules (apikey + throttling, global throtthling, ip filtering)
- [ ] handle headers out manipulation with pluggable modules
- [ ] handle errors rendering with pluggable modules
- [ ] handle target set choice with pluggable modules

## Missing from otoroshi

* [x] service enabled
* [ ] private mode
* [ ] maintenance mode
* [ ] build mode
* [ ] force https mode
* [ ] secure mode mode
* [ ] pass apikey by custom headers
* [ ] header name custom values from config
* [ ] api quotas
* [ ] api throttling
* [ ] global throttling
* [ ] canary mode
* [ ] custom templates
* [ ] push api usage back
* [ ] alerts
* [ ] push alerts back


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

* https://github.com/akka/akka-http/pull/1735