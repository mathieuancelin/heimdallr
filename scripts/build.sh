#!/bin/bash

sbt ';clean;compile;dist;assembly'
cp ./target/scala-2.12/proxy.jar ./docker/
cd docker
docker build -t proxy .
cd ..
