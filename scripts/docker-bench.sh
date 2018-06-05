#!/bin/sh
#
# sudo apt-get update && sudo apt-get install git -y && git clone https://github.com/mathieuancelin/heimdallr.git heimdallr && cd ./heimdallr && sh ./scripts/docker-bench.sh
# 
# or
# 
# mkdir -p /tmp/heimdallr-bench
# cd /tmp/heimdallr-bench
# sudo apt-get update && sudo apt-get install git 
# git clone https://github.com/mathieuancelin/heimdallr.git heimdallr
# cd heimdallr

sudo apt-get remove docker docker-engine docker.io
sudo apt-get update
sudo apt-get install \
     apt-transport-https \
     ca-certificates \
     curl \
     git \
     gnupg2 \
     software-properties-common -y
curl -fsSL https://download.docker.com/linux/debian/gpg | sudo apt-key add -
sudo apt-key fingerprint 0EBFCD88
sudo add-apt-repository \
   "deb [arch=amd64] https://download.docker.com/linux/debian \
   $(lsb_release -cs) \
   stable"
sudo apt-get update
sudo apt-get install docker-ce -y
sudo docker run hello-world
sudo curl -L https://github.com/docker/compose/releases/download/1.21.2/docker-compose-$(uname -s)-$(uname -m) -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
sudo docker-compose --version
cd ./docker/bench
sudo docker-compose build
sudo docker-compose up

