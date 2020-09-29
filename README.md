# LocalEGA-deploy-swarm
![Integration tests](https://github.com/neicnordic/LocalEGA-deploy-swarm/workflows/Integration%20tests/badge.svg)

Docker Swarm deployment of LocalEGA

## Development

### Pre-requisites

- `mkcert` (https://github.com/FiloSottile/mkcert)
- `crypt4gh` (https://github.com/elixir-oslo/crypt4gh)
- `j2cli` (https://github.com/kolypto/j2cli)

### How-to

`make bootstrap deploy` (CEGA-related env-vars should be set manually, e.g. `CEGA_CONNECTION`)

Cleaning up: `make rm purge`.

## Production

Production set up of Federated EGA node consists of two stacks: so-called "public" and "private".

### Public stack

The public stack is deployed to the so-called "Tryggve" server (USIT-managed server outside TSD).

#### Dependencies

The public stack deployment depends on such external services:

- CentralEGA RabbitMQ broker managed by CRG.
- LocalEGA RabbitMQ broker managed by TSD.
- File API managed by TSD.
- Postgres database managed by USIT (not to mix up with the internal database managed by TSD).

#### Database

```
CREATE TABLE IF NOT EXISTS mapping
(
    ega_id          VARCHAR NOT NULL,
    elixir_id       VARCHAR NOT NULL,
    PRIMARY KEY (ega_id),
    UNIQUE (ega_id, elixir_id)
);
```

#### Configuration

Example Docker Swarm deployment descriptor for the public stack:

```
version: '3.3'

services:

  proxy:
    image: uiobmi/localega-tsd-proxy:latest
    ports:
      - 443:8080
    environment:
      - SERVER_CERT_PASSWORD
      - CLIENT_ID
      - CLIENT_SECRET
      - BROKER_HOST
      - BROKER_PORT
      - BROKER_USERNAME
      - BROKER_PASSWORD
      - BROKER_VHOST
      - BROKER_VALIDATE
      - EXCHANGE
      - CEGA_USERNAME
      - CEGA_PASSWORD
      - TSD_PROJECT
      - TSD_ACCESS_KEY
      - DB_INSTANCE
      - POSTGRES_DB
      - POSTGRES_USER
      - POSTGRES_PASSWORD
    secrets:
      - source: server.p12
        target: /etc/ega/ssl/server.cert
    volumes:
      - ./local-ega.github.io:/html

  interceptor:
    image: uiobmi/mq-interceptor:latest
    environment:
      - POSTGRES_CONNECTION
      - CEGA_MQ_CONNECTION
      - LEGA_MQ_CONNECTION

secrets:
  server.p12:
    external: true
```


### Private stack

The private stack is solely deployed within TSD.

#### Dependencies

The private stack deployment depends on such external services:

- LocalEGA RabbitMQ broker managed by TSD.
- Postgres database managed by TSD (not to mix up with the external database managed by USIT).

#### Database

https://github.com/neicnordic/LocalEGA-db

#### Broker

https://github.com/uio-bmi/localega-broker/tree/master/private

#### Configuration

Example Docker Swarm deployment descriptor for the private stack:

```
version: '3.3'

services:

  ingest:
    image: neicnordic/sda-base:latest
    deploy:
      restart_policy:
        condition: on-failure
        delay: 5s
        window: 120s
    secrets:
      - source: conf.ini
        target: /etc/ega/conf.ini
    volumes:
      - </path/to/the/inbox>:/ega/inbox
      - </path/to/the/archive>:/ega/archive
    user: lega
    entrypoint: ["ega-ingest"]

  verify:
    image: neicnordic/sda-base:latest
    deploy:
      restart_policy:
        condition: on-failure
        delay: 5s
        window: 120s
    secrets:
      - source: conf.ini
        target: /etc/ega/conf.ini
      - source: ega.sec.pem
        target: /etc/ega/ega.sec
        uid: '1000'
        gid: '1000'
        mode: 0600
    volumes:
      - vault:/ega/archive
    user: lega
    entrypoint: ["ega-verify"]

  finalize:
    image: neicnordic/sda-base:latest
    deploy:
      restart_policy:
        condition: on-failure
        delay: 5s
        window: 120s
    secrets:
      - source: conf.ini
        target: /etc/ega/conf.ini
    volumes:
      - vault:/ega/archive
    user: lega
    entrypoint: ["ega-finalize"]

  mapper:
    image: neicnordic/sda-mapper:latest
    deploy:
      restart_policy:
        condition: on-failure
        delay: 5s
        window: 120s
    environment:
      - DB_IN_CONNECTION
      - DB_OUT_CONNECTION
      - MQ_CONNECTION

  doa:
    image: neicnordic/sda-doa:latest
    ports:
      - 8080:8080
    deploy:
      restart_policy:
        condition: on-failure
        delay: 5s
        window: 120s
    environment:
      - SSL_MODE=require
      - ARCHIVE_PATH
      - KEYSTORE_PASSWORD
      - DB_INSTANCE
      - POSTGRES_DB
      - POSTGRES_PASSWORD
      - OUTBOX_ENABLED
      - BROKER_HOST
    secrets:
      - source: server.p12
        target: /etc/ega/ssl/server.cert
      - source: client.pem
        target: /etc/ega/ssl/client.cert
      - source: jwt.pub.pem
        target: /etc/ega/jwt/passport.pem
      - source: jwt.pub.pem
        target: /etc/ega/jwt/visa.pem
      - source: ega.sec.pem
        target: /etc/ega/crypt4gh/key.pem
      - source: ega.sec.pass
        target: /etc/ega/crypt4gh/key.pass
    volumes:
      - </path/to/the/inbox>:/ega/archive

secrets:
  conf.ini:
    external: true
  server.p12:
    external: true
  client.pem:
    external: true
  ega.sec.pem:
    external: true
  ega.sec.pass:
    external: true
  ega.pub.pem:
    external: true
  jwt.pub.pem:
    external: true
```

