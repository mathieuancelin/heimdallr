# Heimdallr dev environment for quick patches

this docker image provide a functionnal dev environement to patch Heimdallr quickly without installing a lot of stuff, you just need `Docker` and a web browser available on your machine.

## Run it

```sh 
docker run -p "8081:8081" -p "8443:8443" -p "9081:9081" -p "9443:9443" -it heimdallr-dev zsh
```

## Build and Run it

```sh
docker build -t heimdallr-dev .
docker run -p "8081:8081" -p "8443:8443" -p "9081:9081" -p "9443:9443" -it heimdallr-dev zsh
```

