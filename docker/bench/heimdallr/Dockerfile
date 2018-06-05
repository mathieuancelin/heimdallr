FROM openjdk:8

RUN mkdir -p /heimdallr

WORKDIR /heimdallr

COPY . /heimdallr

RUN apt-get update -y \
  && apt-get install -y curl bash wget \
  && wget https://dl.bintray.com/mathieuancelin/heimdallr/heimdallr.jar/snapshot/heimdallr.jar

EXPOSE 8091
EXPOSE 8443
EXPOSE 9081
EXPOSE 9443

CMD ["java", "-Dakka.http.server.pipelining-limit=1", "-Dakka.http.server.host-connection-pool.pipelining-limit=1", "-jar", "heimdallr.jar", "--proxy.config.url=http://backend:8100/heimdallr.json"]