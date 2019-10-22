#!/bin/bash

if [[ "$TRAVIS_BRANCH" == "master" && "$TRAVIS_PULL_REQUEST" == "false" ]]; then
  docker stack rm LEGA

  sleep 30
  docker rmi $(docker images -aq) || true
  docker volume rm $(docker volume list -q) || true
  docker config rm $(docker config list -q) || true
fi
