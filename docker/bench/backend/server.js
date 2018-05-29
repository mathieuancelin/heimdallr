const { fork, exec } = require('child_process');
const express = require('express');
const _ = require('lodash');
const argv = require('minimist')(process.argv.slice(2));
const from = argv.fromPort;
const to = argv.toPort + 1;
const docker = (argv.docker === 'true') || false;
const graalvm = (argv.graalvm === 'true') || false;
const ipAddress = argv.ipAddress || '127.0.0.1';
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
            "url" : `http://${ipAddress}:${port}`,
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

configApp.get('/heimdallr2.json', (req, res) => {
  console.log('Serving test config');
  res.status(200).send({
    "http" : {
      "httpPort" : 8092,
      "httpsPort" : 8444,
      "listenOn" : "0.0.0.0",
      "keyStoreType" : "PKCS12",
      "certPath" : null,
      "certPass" : null
    },
    "api" : {
      "httpPort" : 9082,
      "httpsPort" : 9444,
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
            "url" : `http://${ipAddress}:${port}`,
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
});

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
