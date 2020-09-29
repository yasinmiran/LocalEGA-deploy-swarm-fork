# LocalEGA-deploy-swarm
![Integration tests](https://github.com/neicnordic/LocalEGA-deploy-swarm/workflows/Integration%20tests/badge.svg)

Docker Swarm deployment of LocalEGA

## Pre-requisites

- `mkcert` (https://github.com/FiloSottile/mkcert)
- `crypt4gh` (https://github.com/elixir-oslo/crypt4gh)
- `j2cli` (https://github.com/kolypto/j2cli)

## How-to

`make bootstrap deploy` (CEGA-related env-vars should be set manually, e.g. `CEGA_CONNECTION`)

Cleaning up: `make rm purge`.
