# reverse-proxy

Experimentatl project about http reverse proxies. Trying new things ...

## Features

- [ ] based on devoxx talk
- [ ] ProxyConfig object for Api integration
- [ ] withConfig(ProxyConfig) or withConfigFrom(path)
- [ ] additional headers
- [ ] admin API complete on another port. Add service to serve this api
- [ ] metrics JMX or statsd with metrics (maybe statsd integration already provided)
- [ ] apikeys on the service (no quotas), clientId, clientSecret, enabled, name
- [ ] api integration + main class 
- [ ] toml for config ?
- [ ] save state to file periodically
- [ ] circuit breaker config in service
- [ ] config for ssl
- [ ] logs like sozu
- [ ] matching root
- [ ] input root
- [ ] public / private stuff
- [ ] find a name for the project
- [ ] support for WS

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