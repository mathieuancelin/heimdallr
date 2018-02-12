# Heimdallr

Experimental project to try new things on http reverse proxies. Do not use in production ... yet, or not ;)

## Use it

## Build it

```sh
sh ./scripts/build.sh server
```

or to build everything

```sh
sh ./scripts/build.sh all
```

## Features

- [ ] write some usage docs in readme
- [ ] built-in kafka support as commands input
- [ ] built-in kafka support as logs output
- [ ] statsd support (include metrics in statsd actor for REST metrics)
- [ ] dynamic TLS
- [ ] handle serde calls for services with pluggables modules
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
