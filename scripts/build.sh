#!/bin/bash

LOCATION=`pwd`
cd $LOCATION

build_server () { 
  sbt ';clean;compile;dist;assembly'
}

build_docker () {
  cp $LOCATION/target/scala-2.12/heimdallr.jar $LOCATION/docker/build/
  cd $LOCATION/docker/build
  docker build -t heimdallr .
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
    build_docker_dev
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
    build_docker_dev
esac

exit ${?}