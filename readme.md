# reverse-proxy

Experimental project to try new things on http reverse proxies. Do not use in production ... yet ;)

## Features

- [x] ProxyConfig object for Api integration
- [x] withConfig(ProxyConfig)
- [x] withConfigFrom(path)
- [x] additional headers
- [ ] admin API complete on another port. Add service to serve this api
- [x] metrics JMX or statsd with metrics (maybe statsd integration already provided)
- [x] apikeys on the service (no quotas), clientId, clientSecret, enabled, name
- [x] pass if public or apikey
- [x] api integration + main class 
- [ ] save state to file periodically
- [x] circuit breaker config in service
- [x] config for ssl
- [x] logs like sozu
- [x] matching root
- [x] target root
- [x] public / private stuff
- [ ] find a name for the project
- [ ] support for WS
- [ ] support JWT auth
- [ ] start https only if certificate provided
- [ ] smaller https pass
- [ ] httpscontext from .pem ?
- [ ] pass --log.config=
- [ ] pass --proxy.config=prod.xml
- [ ] pass --proxy.state=
- [ ] try sozu state.json
- [ ] shutdown hook in main
- [ ] provide apln support with agent from jar if jDK8

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