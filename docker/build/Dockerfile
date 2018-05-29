FROM openjdk:8

LABEL name "Heimdallr"
LABEL maintainer "Mathieu ANCELIN"

RUN groupadd -g 999 heimdallr && useradd -r -u 999 -g heimdallr heimdallr

RUN mkdir -p /heimdallr

WORKDIR /heimdallr

COPY . /heimdallr

RUN chown -R heimdallr:heimdallr /heimdallr

VOLUME /heimdallr

ENTRYPOINT ["./entrypoint.sh"]

EXPOSE 8091
EXPOSE 8443
EXPOSE 9081
EXPOSE 9443

CMD [""]