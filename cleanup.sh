#!/bin/bash

docker stack rm LEGA
sleep 10
# shellcheck disable=SC2046
docker rm $(docker ps -aq)
# shellcheck disable=SC2046
docker volume rm $(docker volume list -q)
# shellcheck disable=SC2046
docker config rm $(docker config list -q)

rm conf.ini db+*.pem docker-stack.yml ega*.pem ega*.pass
