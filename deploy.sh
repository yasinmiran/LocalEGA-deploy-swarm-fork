#!/bin/bash

docker-compose config > docker-stack.yml
docker stack deploy LEGA -c docker-stack.yml
