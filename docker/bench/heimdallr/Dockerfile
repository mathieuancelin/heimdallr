FROM openjdk:8

RUN mkdir -p /heimdallr

WORKDIR /heimdallr

COPY . /heimdallr

EXPOSE 8091
EXPOSE 8443
EXPOSE 9081
EXPOSE 9443

CMD ["java", "-jar", "heimdallr.jar", "--proxy.config.url=http://backend:8100/heimdallr.json"]