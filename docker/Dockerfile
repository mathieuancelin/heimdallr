FROM openjdk:8u151-jre-alpine

LABEL name "Heimdallr"
LABEL maintainer "Mathieu ANCELIN"

RUN mkdir -p /usr/app

WORKDIR /usr/app

COPY . /usr/app

RUN apk update \
  && apk upgrade \
  && apk add bash \
  && mkdir config

VOLUME /usr/app/config

ENTRYPOINT ["./entrypoint.sh"]

EXPOSE 8080

CMD [""]