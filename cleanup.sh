#!/bin/bash

if [[ "$TRAVIS_BRANCH" == "master" && "$TRAVIS_PULL_REQUEST" == "false" ]]; then
  docker stack rm LEGA

  sleep 30
  docker volume rm LEGA_vault_s3
  docker volume rm LEGA_db

  docker config rm CA.cert
  docker config rm client-server.cert
  docker config rm client-server.key
  docker config rm conf.ini
  docker config rm ega.sec
  docker config rm ega.sec.pass
  docker config rm ega.pub
  docker config rm ega.shared.pass
fi
