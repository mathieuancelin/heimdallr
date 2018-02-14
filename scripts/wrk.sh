#!/bin/sh

tune_linux () {
  sysctl -w fs.file-max="9999999"
  sysctl -w fs.nr_open="9999999"
  sysctl -w net.core.netdev_max_backlog="4096"
  sysctl -w net.core.rmem_max="16777216"
  sysctl -w net.core.somaxconn="65535"
  sysctl -w net.core.wmem_max="16777216"
  sysctl -w net.ipv4.ip_local_port_range="1025       65535"
  sysctl -w net.ipv4.tcp_fin_timeout="30"
  sysctl -w net.ipv4.tcp_keepalive_time="30"
  sysctl -w net.ipv4.tcp_max_syn_backlog="20480"
  sysctl -w net.ipv4.tcp_max_tw_buckets="400000"
  sysctl -w net.ipv4.tcp_no_metrics_save="1"
  sysctl -w net.ipv4.tcp_syn_retries="2"
  sysctl -w net.ipv4.tcp_synack_retries="2"
  sysctl -w net.ipv4.tcp_tw_recycle="1"
  sysctl -w net.ipv4.tcp_tw_reuse="1"
  sysctl -w vm.min_free_kbytes="65536"
  sysctl -w vm.overcommit_memory="1"
  ulimit -n 9999999
}

kill_heimdallr () {
  echo "Kill heimdallr"
  ps aux | grep java | grep heimdallr.jar | awk '{print $2}' | xargs kill  >> /dev/null
}

build_sozu () {
  git clone https://github.com/sozu-proxy/sozu.git --depth=1
  cd $LOCATION/sozu/ctl && cargo build --release; cd $LOCATION/sozu/bin && cargo build --release
  cp $LOCATION/sozu/target/release/sozu $LOCATION/sozu
  cp $LOCATION/sozu/target/release/sozuctl $LOCATION/sozuctl
  mkdir $LOCATION/command_folder
  touch $LOCATION/state.json
  cd $LOCATION
  rm -rf $LOCATION/sozu
}

echo "Prepare test ...."

ROOT_LOCATION=`pwd`
if [ ! -d "$ROOT_LOCATION/wrk_test" ]; then
  mkdir wrk_test
fi

cd wrk_test

LOCATION=`pwd`

if [ ! -f "$LOCATION/heimdallr.jar" ]; then
  echo "no heimdallr.jar :("
  exit 1
fi 

if [ ! -f "$LOCATION/heimdallr.conf" ]; then
  echo "no heimdallr.conf :("
  exit 1
fi 

if [ ! -f "$LOCATION/traefik_darwin-amd64" ]; then	
  wget -q --show-progress https://github.com/containous/traefik/releases/download/v1.5.0-rc4/traefik_darwin-amd64
fi

if [ ! -f "$LOCATION/traefik.toml" ]; then	
  wget -q --show-progress https://gist.githubusercontent.com/mathieuancelin/a32506603c8425963b30d6d6a6c148fb/raw/c6bfec26078e44d21b4358efdf43f0cbeaaa5789/traefik.toml
fi

if [ ! -f "$LOCATION/sozu" ]; then	
  build_sozu
fi

if [ ! -f "$LOCATION/sozu.toml" ]; then	
  wget -q --show-progress https://gist.githubusercontent.com/mathieuancelin/2d4b16443199e93926c640e4fdb2ec17/raw/dd97412975d6c2cd9de88cdd948b8023e4d884ed/sozu.toml
fi

chmod +x traefik_darwin-amd64
chmod +x sozu

java -jar heimdallr.jar >> /dev/null &
./traefik_darwin-amd64 --configFile=traefik.toml >> /dev/null &
./sozu start -c sozu.toml >> /dev/null &

docker kill $(docker ps -q) >> /dev/null
docker run -d -p "8081:80" emilevauge/whoami  >> /dev/null
docker run -d -p "8082:80" emilevauge/whoami  >> /dev/null
docker run -d -p "8083:80" emilevauge/whoami  >> /dev/null

sleep 10

echo "Warm up ..."
wrk -t1 -c1 -d40s -H "Host: test.foo.bar" http://127.0.0.1:8080/ >> /dev/null
wrk -t1 -c1 -d40s -H "Host: test.foo.bar" http://127.0.0.1:8000/ >> /dev/null
wrk -t1 -c1 -d40s -H "Host: test.foo.bar" http://127.0.0.1:8088/ >> /dev/null

echo "Running test at `date`"
wrk -t2 -c200 -d60s -H "Host: test.foo.bar" --latency http://127.0.0.1:8080/
wrk -t2 -c200 -d60s -H "Host: test.foo.bar" --latency http://127.0.0.1:8000/
wrk -t2 -c200 -d60s -H "Host: test.foo.bar" --latency http://127.0.0.1:8088/

docker kill $(docker ps -q) >> /dev/null
kill_heimdallr  >> /dev/null
killall traefik_darwin-amd64  >> /dev/null
killall sozu  >> /dev/null
rm -rf logs

case "${1}" in
  rm)
    cd $ROOT_LOCATION
    rm -rf $ROOT_LOCATION/wrk_test
    ;;
  *)
    echo "Done !"
esac

exit ${?}