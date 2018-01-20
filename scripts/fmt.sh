#!/bin/bash

LOCATION=`pwd`
cd $LOCATION
sbt ';scalafmt;sbt:scalafmt;test:scalafmt'