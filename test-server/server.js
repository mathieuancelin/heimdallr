const { fork, exec } = require('child_process');
const express = require('express');
const _ = require('lodash');
const argv = require('minimist')(process.argv.slice(2));
const from = argv.fromPort;
const to = argv.toPort + 1;
const processes = [];

_.range(from, to).forEach((port, idx) => {
  const cp = fork('fork.js', [`--port=${port}`, `--idx=${idx}`]);
  processes.push(cp);
});

const configPort = argv.port;
const configApp = express();
configApp.get('/heimdallr.json', (req, res) => {
  console.log('Serving test config');
  res.status(200).send({
    "http" : {
      "httpPort" : 8091,
      "httpsPort" : 8443,
      "listenOn" : "0.0.0.0",
      "keyStoreType" : "PKCS12",
      "certPath" : null,
      "certPass" : null
    },
    "api" : {
      "httpPort" : 9081,
      "httpsPort" : 9443,
      "listenOn" : "127.0.0.1",
      "keyStoreType" : "PKCS12",
      "certPath" : null,
      "certPass" : null,
      "enabled" : true
    },
    "services" : [
      {
        "id" : "loadbalancing-test",
        "enabled": true,
        "domain" : "test.foo.bar",
        "targets" : _.range(from, to).map(port => {
          return {
            "url" : `http://127.0.0.1:${port}`,
            "weight" : 1,
            "protocol" : "HTTP/1.1"
          };
        }),
        "apiKeys" : [],
        "clientConfig" : {
          "retry" : 3,
          "maxFailures" : 5,
          "callTimeout" : 30000,
          "resetTimeout" : 10000
        },
        "additionalHeaders" : {},
        "matchingHeaders" : {},
        "targetRoot" : "",
        "root" : null,
        "publicPatterns" : ["/*"],
        "privatePatterns" : [],
        "metadata": {}
      }
    ],
    "state" : null,
    "loggers": {
      "level": "WARN",
      "configPath" : null
    },
    "statsd": null  
  });
});

configApp.listen(configPort, () => {
  console.log(`Config server listening on port ${configPort}!`);
  const heimdallr = exec(`java -jar ../target/scala-2.12/heimdallr.jar --proxy.config.url=http://127.0.0.1:${configPort}/heimdallr.json`);
  processes.push(heimdallr);
});

setTimeout(() => {
  console.log('\nStarting warmup load test')
  const wrk = exec(`wrk2 -R 1000 -t6 -c200 -d40s -H "Host: test.foo.bar" --latency http://127.0.0.1:8091/`);
  processes.push(wrk);
  wrk.on('exit', (e) => {
    console.log('Starting actual load test')
    const wrkLoad = exec(`wrk2 -R 8000 -t80 -c800 -d60s -H "Host: test.foo.bar" --latency http://127.0.0.1:8091/`);
    processes.push(wrkLoad);
    wrkLoad.stdout.on('data', (chunk) => {
      console.log(`[wrk-load] ${chunk}`);
    });
    wrkLoad.stderr.on('data', (chunk) => {
      console.log(`[wrk-load-err] ${chunk}`);
    });
    wrkLoad.on('exit', (e) => {
      process.exit();
    });
  });
}, 6000)

function exitHandler(options, err) {
  processes.forEach(a => a.kill());
  if (err) console.log(err.stack);
  if (options.exit) process.exit();
}

process.on('exit', exitHandler.bind(null,{cleanup:true}));
process.on('SIGINT', exitHandler.bind(null, {exit:true}));
process.on('SIGUSR1', exitHandler.bind(null, {exit:true}));
process.on('SIGUSR2', exitHandler.bind(null, {exit:true}));
process.on('uncaughtException', exitHandler.bind(null, {exit:true}));

// java -jar target/scala-2.12/heimdallr.jar --proxy.config.url=http://127.0.0.1:${configPort}/heimdallr.json
// wrk -t40 -c800 -d60s -H "Host: test.foo.bar" --latency http://127.0.0.1:8091/
