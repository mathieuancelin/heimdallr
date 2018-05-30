FROM openjdk:8

LABEL name "Heimdallr"
LABEL maintainer "Mathieu ANCELIN"

RUN groupadd -g 999 heimdallr && useradd -r -u 999 -g heimdallr heimdallr

RUN mkdir -p /heimdallr

WORKDIR /heimdallr

COPY . /heimdallr

RUN apt-get update -y \
  && apt-get install -y curl bash build-essential zlib1g-dev wget \
  && wget https://api.bintray.com/content/mathieuancelin/heimdallr/heimdallr.jar/snapshot/heimdallr.jar \	
  && wget https://github.com/oracle/graal/releases/download/vm-1.0.0-rc1/graalvm-ce-1.0.0-rc1-linux-amd64.tar.gz \
  && tar -xvf graalvm-ce-1.0.0-rc1-linux-amd64.tar.gz \
  && mv graalvm-1.0.0-rc1 graalvm \
  && chown -R heimdallr:heimdallr /heimdallr

VOLUME /heimdallr

ENTRYPOINT ["./entrypoint.sh"]

EXPOSE 8091
EXPOSE 8443
EXPOSE 9081
EXPOSE 9443

CMD [""]