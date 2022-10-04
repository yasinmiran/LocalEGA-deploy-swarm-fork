# LocalEGA-deploy-swarm-fork
![Integration tests](https://github.com/neicnordic/LocalEGA-deploy-swarm/workflows/Integration%20tests/badge.svg)

Docker Swarm deployment of LocalEGA. This is a fork meant for internal testing purposes.
Please, refer to the project documentation for getting more comprehensive information: https://neic-sda.readthedocs.io/

## Purpose

The `docker-template.yml` file can be used in several ways:
1. Deploy all the NeIC nordic microservices in use by FEGA Norway locally as containers together with mock services for TSD and CEGA functionality needed.
2. One can trim it down to a Public stack or a Private stack depending on what needs to be deployed.
3. GitHub actions can test nightly if the master branch is building and testing ok (without connection to live CEGA services. For that, please visit the upstream neicnordic/localega-deploy-swarm repo)

## Development

### Pre-requisites

- `mkcert` (https://github.com/FiloSottile/mkcert)
- `crypt4gh` (https://github.com/elixir-oslo/crypt4gh)
- `j2cli` (https://github.com/kolypto/j2cli)

**IMPORTANT**

CEGA-related env-vars _is no more needed to be set_  manually in `Makefile` before running the makefile. They decker-template.yml file already contains default values expected by the micro-services to work. The variables are:

```
export CEGA_USERNAME= 
export CEGA_PASSWORD=
export BROKER_HOST= 
export BROKER_PORT=
export BROKER_USERNAME=
export BROKER_PASSWORD=
export CEGA_MQ_CONNECTION= 
export BROKER_VALIDATE= 
export BROKER_VHOST= 
export EXCHANGE=
```
all these variables are required by proxy and interceptor micro services.

### How-to

Run:
```bash
> make bootstrap deploy
```

Clean:
```bash
> make rm clean
```

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

Example Docker Swarm deployment descriptor for the public stack.

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

- Documentation: https://neic-sda.readthedocs.io/en/latest/db.html
- Development/testing Docker image: https://github.com/neicnordic/LocalEGA-db

#### Broker

- Documentation: https://neic-sda.readthedocs.io/en/latest/connection.html#local-message-broker
- Development/testing Docker image: https://github.com/uio-bmi/localega-broker/tree/master/private

#### Configuration

**NB**: for some reason, Docker in TSD supports only long syntax for ports mapping, i.e.:

```
...
    ports:
      - target: 8080
        published: 80
        mode: host
...
```

Example Docker Swarm deployment descriptor for the private stack. 

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
    environment:
      - DEFAULT_LOG=debug
      - INBOX_LOCATION
      - ARCHIVE_LOCATION=/ega/%s
      - ARCHIVE_USER=archive
      - BROKER_CONNECTION
      - BROKER_ENABLE_SSL=yes
      - BROKER_VERIFY_PEER=no
      - BROKER_VERIFY_HOSTNAME=no
      - DB_CONNECTION
    volumes:
      - tsd:/ega/inbox
      - vault:/ega/archive
    user: lega
    entrypoint: ["ega-ingest"]

  verify:
    image: neicnordic/sda-base:latest
    deploy:
      restart_policy:
        condition: on-failure
        delay: 5s
        window: 120s
    environment:
      - DEFAULT_LOG=debug
      - C4GH_FILE_PASSPHRASE
      - ARCHIVE_LOCATION=/ega/%s
      - ARCHIVE_USER=archive
      - BROKER_CONNECTION
      - BROKER_ENABLE_SSL=yes
      - BROKER_VERIFY_PEER=no
      - BROKER_VERIFY_HOSTNAME=no
      - DB_CONNECTION
    secrets:
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
    environment:
      - DEFAULT_LOG=debug
      - BROKER_CONNECTION
      - BROKER_ENABLE_SSL=yes
      - BROKER_VERIFY_PEER=no
      - BROKER_VERIFY_HOSTNAME=no
      - DB_CONNECTION
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
      - SSL_ENABLED=false
      - SSL_MODE=require
      - ARCHIVE_PATH
      - DB_INSTANCE
      - POSTGRES_DB
      - POSTGRES_PASSWORD
      - OUTBOX_ENABLED
    secrets:
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
      - vault:/ega/archive

secrets:
  client.pem:
    external: true
  ega.sec.pem:
    external: true
  ega.sec.pass:
    external: true
  jwt.pub.pem:
    external: true
```

