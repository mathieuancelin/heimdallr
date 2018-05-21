#!/bin/bash

LOCATION=`pwd`
cd $LOCATION

build_server () { 
  sbt ';clean;compile;test;dist;assembly'
  rc=$?; if [ $rc != 0 ]; then exit $rc; fi
}

build_docker () {
  cp $LOCATION/target/scala-2.12/heimdallr.jar $LOCATION/docker/build/
  cp $LOCATION/target/scala-2.12/heimdallr.jar $LOCATION/docker/graalvm/
  cd $LOCATION/docker/build
  docker build -t heimdallr .
  rm -f $LOCATION/docker/build/heimdallr.jar
  cd $LOCATION/docker/graalvm
  docker build -t heimdallr-graalvm .
  rm -f $LOCATION/docker/graalvm/heimdallr.jar
  cd $LOCATION
}

build_docker_dev () {
  cd $LOCATION/docker/dev
  docker build -t heimdallr-dev .
  cd $LOCATION
}

case "${1}" in
  all)
    build_server
    build_docker
    ;;
  server)
    build_server
    ;;
  docker)
    build_docker
    ;;
  dev)
    build_docker_dev
    ;;
  *)
    build_server
    build_docker
esac

exit ${?}