#!/bin/bash

if [[ "$TRAVIS_BRANCH" == "master" && "$TRAVIS_PULL_REQUEST" == "false" ]]; then
  attempt_counter=0
  max_attempts=20
  while [[ $(curl --insecure -s -o /dev/null -w "%{http_code}" https://$TSD_IP_ADDRESS:9000/minio/health/ready) != 200 ]]; do
    if [ ${attempt_counter} -eq ${max_attempts} ]; then
      printf "\nMax attempts reached\n"
      exit 1
    fi

    printf '.'
    attempt_counter=$(($attempt_counter + 1))
    sleep 10
  done
fi
