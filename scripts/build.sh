#!/bin/bash

sbt ';clean;compile;dist;assembly'
cp ./target/scala-2.12/heimdallr.jar ./docker/
cd docker
docker build -t heimdallr .
cd ..
