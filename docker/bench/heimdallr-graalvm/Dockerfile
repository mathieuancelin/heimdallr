FROM openjdk:8

RUN mkdir -p /heimdallr

WORKDIR /heimdallr

COPY . /heimdallr

RUN apt-get update -y \
  && apt-get install -y curl bash build-essential zlib1g-dev wget \
  && wget https://dl.bintray.com/mathieuancelin/heimdallr/heimdallr.jar/snapshot/heimdallr.jar \
  && wget https://github.com/oracle/graal/releases/download/vm-1.0.0-rc1/graalvm-ce-1.0.0-rc1-linux-amd64.tar.gz \
  && tar -xvf graalvm-ce-1.0.0-rc1-linux-amd64.tar.gz \
  && mv graalvm-1.0.0-rc1 graalvm 

EXPOSE 8092
EXPOSE 8444
EXPOSE 9082
EXPOSE 9444

CMD ["/heimdallr/graalvm/bin/java", "-Dakka.http.server.pipelining-limit=1", "-Dakka.http.server.host-connection-pool.pipelining-limit=1", "-jar", "heimdallr.jar", "--proxy.config.url=http://backend:8100/heimdallr2.json"]