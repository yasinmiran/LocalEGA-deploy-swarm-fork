#!/bin/bash

docker stack rm LEGA
sleep 10
# shellcheck disable=SC2046
docker rm $(docker ps -aq)
docker volume rm LEGA_tsd LEGA_db LEGA_vault
# shellcheck disable=SC2046
docker config rm $(docker config list -q)

rm conf.ini rootCA.pem localhost+*.pem localhost+*.p12 localhost+*.der docker-stack.yml ega*.pem ega*.pass
