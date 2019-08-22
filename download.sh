#!/bin/bash

if [[ "$TRAVIS_BRANCH" == "master" && "$TRAVIS_PULL_REQUEST" == "false" ]]; then
  curl $TSD_CA --create-dirs -o ~/.docker/ca.pem
  curl $TSD_CERT --create-dirs -o ~/.docker/cert.pem
  curl $TSD_KEY --create-dirs -o ~/.docker/key.pem

  curl $CA_CERT -o rootCA.pem
  curl $CA_KEY -o rootCA-key.pem
fi
