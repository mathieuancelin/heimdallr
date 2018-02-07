#!/bin/sh

echo "Will upload artifacts for $TRAVIS_BRANCH / $TRAVIS_PULL_REQUEST"
if test "$TRAVIS_BRANCH" = "master"
then
  if test "$TRAVIS_PULL_REQUEST" = "false"
  then
    echo "Uploading heimdallr.jar"
    curl -T ./target/scala-2.12/heimdallr.jar -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: snapshot' -H 'X-Bintray-Package: heimdallr.jar' https://api.bintray.com/content/mathieuancelin/heimdallr/heimdallr.jar/snapshot/heimdallr.jar
    curl -T ./target/universal/heimdallr-1.0.0.zip -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: snapshot' -H 'X-Bintray-Package: heimdallr-dist' https://api.bintray.com/content/mathieuancelin/heimdallr/heimdallr-dist/snapshot/heimdallr-dist.zip
  fi
fi

echo "Will upload artifacts for $TRAVIS_BRANCH / $TRAVIS_PULL_REQUEST"
if test "$TRAVIS_BRANCH" = "master"
then
  if test "$TRAVIS_PULL_REQUEST" = "false"
  then
    docker login -u $DOCKER_USER -p $DOCKER_PASSWORD mathieuancelin-docker-heimdallr-docker.bintray.io
    docker tag heimdallr mathieuancelin-docker-heimdallr-docker.bintray.io/heimdallr
    docker push mathieuancelin-docker-heimdallr-docker.bintray.io/heimdallr
    docker tag heimdallr-dev mathieuancelin-docker-heimdallr-docker.bintray.io/heimdallr-dev
    docker push mathieuancelin-docker-heimdallr-docker.bintray.io/heimdallr-dev
  fi
fi