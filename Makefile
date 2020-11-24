SHELL := /bin/bash -O expand_aliases

FILES := localhost+5.pem localhost+5-key.pem localhost+5-client.pem localhost+5-client-key.pem rootCA.pem rootCA.p12 localhost+5.p12 localhost+5-client.p12 localhost+5-client-key.der rootCA-key.pem docker-stack.yml jwt.pub.pem jwt.priv.pem ega.pub.pem ega.sec.pass ega.sec.pem server.pem server-key.pem server.p12 client.pem client-key.pem client-key.der client.p12 init-mappings-db.sh

export CAROOT := $(shell mkcert -CAROOT)
export ROOT_CERT_PASSWORD=r00t_cert_passw0rd
export TSD_ROOT_CERT_PASSWORD=r00t_cert_passw0rd
export SERVER_CERT_PASSWORD=server_cert_passw0rd
export CLIENT_CERT_PASSWORD=client_cert_passw0rd
export TSD_HOST=tsd:8080
export TSD_PROJECT=p11
export TSD_ACCESS_KEY=s0me_key
export DB_HOST=db
export DB_DATABASE_NAME=lega
export DB_LEGA_IN_USER=lega_in
export DB_LEGA_IN_PASSWORD=in_passw0rd
export DB_LEGA_OUT_USER=lega_out
export DB_LEGA_OUT_PASSWORD=0ut_passw0rd
export ARCHIVE_PATH=/ega/archive/
export PUBLIC_BROKER_USER=admin
export PUBLIC_BROKER_HASH=4tHURqDiZzypw0NTvoHhpn8/MMgONWonWxgRZ4NXgR8nZRBz
export PUBLIC_BROKER_PASSWORD=guest
export PRIVATE_BROKER_USER=admin
export PRIVATE_BROKER_PASSWORD=guest
export PRIVATE_BROKER_VHOST=test
export PRIVATE_BROKER_HASH=4tHURqDiZzypw0NTvoHhpn8/MMgONWonWxgRZ4NXgR8nZRBz
export MQ_HOST=mq
export MQ_CONNECTION=amqps://admin:guest@mq:5671/test
export DB_IN_CONNECTION=postgres://lega_in:in_passw0rd@db:5432/lega?application_name=LocalEGA
export DB_OUT_CONNECTION=postgres://lega_out:0ut_passw0rd@db:5432/lega?application_name=LocalEGA
export KEY_PASSWORD=key_passw0rd
export POSTGRES_PASSWORD=p0stgres_passw0rd
export POSTGRES_CONNECTION=postgres://postgres:p0stgres_passw0rd@postgres:5432/postgres?sslmode=disable

bootstrap: init $(FILES)
	@chmod 644 $(FILES)
	@mkdir -p /tmp/tsd /tmp/vault /tmp/db
	@chmod 777 /tmp/tsd /tmp/vault /tmp/db

init:
	@-docker swarm init

mkcert:
	@mkcert -install

localhost+5.pem: mkcert
	@mkcert localhost db vault mq tsd proxy

localhost+5-key.pem: localhost+5.pem

localhost+5.p12: localhost+5-key.pem
	@openssl pkcs12 -export -out localhost+5.p12 -in localhost+5.pem -inkey localhost+5-key.pem -passout pass:"${SERVER_CERT_PASSWORD}"

localhost+5-client.pem: mkcert
	@mkcert -client localhost db vault mq tsd proxy

localhost+5-client-key.pem: localhost+5-client.pem

localhost+5-client.p12: localhost+5-client-key.pem
	@openssl pkcs12 -export -out localhost+5-client.p12 -in localhost+5-client.pem -inkey localhost+5-client-key.pem -passout pass:${CLIENT_CERT_PASSWORD}

localhost+5-client-key.der: localhost+5-client-key.pem
	@openssl pkcs8 -topk8 -inform PEM -in localhost+5-client-key.pem -outform DER -nocrypt -out localhost+5-client-key.der

jwt.priv.pem:
	@openssl genpkey -algorithm RSA -out jwt.priv.pem -pkeyopt rsa_keygen_bits:4096
	@docker secret create $@ $@

jwt.pub.pem: jwt.priv.pem
	@openssl rsa -pubout -in jwt.priv.pem -out jwt.pub.pem
	@docker secret create $@ $@

ega.sec.pass:
	@printf $(KEY_PASSWORD) > ega.sec.pass
	@docker secret create $@ $@

ega.sec.pem:
	@crypt4gh generate -n ega -p $(KEY_PASSWORD)
	@docker secret create $@ $@

ega.pub.pem: ega.sec.pem

docker-stack.yml:
	@j2 docker-template.yml > docker-stack.yml

rootCA.pem: mkcert
	@cp "$(CAROOT)/rootCA.pem" rootCA.pem
	@docker secret create $@ $@

rootCA-key.pem: mkcert
	@cp "$(CAROOT)/rootCA-key.pem" rootCA-key.pem
	@chmod 600 rootCA-key.pem
	@docker secret create $@ $@

rootCA.p12: rootCA.pem rootCA-key.pem
	@openssl pkcs12 -export -out rootCA.p12 -in rootCA.pem -inkey rootCA-key.pem -passout pass:${ROOT_CERT_PASSWORD}
	@docker secret create $@ $@

server.pem: localhost+5.pem
	@cp localhost+5.pem server.pem
	@docker secret create $@ $@

server-key.pem: localhost+5-key.pem
	@cp localhost+5-key.pem server-key.pem
	@docker secret create $@ $@

server.p12: localhost+5.p12
	@cp localhost+5.p12 server.p12
	@docker secret create $@ $@

client.pem: localhost+5-client.pem
	@cp localhost+5-client.pem client.pem
	@docker secret create $@ $@

client-key.pem: localhost+5-client-key.pem
	@cp localhost+5-client-key.pem client-key.pem
	@docker secret create $@ $@

client-key.der: localhost+5-client-key.der
	@cp localhost+5-client-key.der client-key.der
	@docker secret create $@ $@

client.p12: localhost+5-client.p12
	@cp localhost+5-client.p12 client.p12
	@docker secret create $@ $@

define mappings
#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$$POSTGRES_USER" --dbname "$$POSTGRES_DB" <<-EOSQL
CREATE TABLE IF NOT EXISTS mapping
(
    ega_id          VARCHAR NOT NULL,
    elixir_id       VARCHAR NOT NULL,
    PRIMARY KEY (ega_id),
    UNIQUE (ega_id, elixir_id)
);
EOSQL
endef

export mappings

init-mappings-db.sh:
	@echo "$$mappings" > init-mappings-db.sh
	@docker secret create $@ $@

deploy: init
	@docker stack deploy LEGA -c docker-stack.yml

ls:
	@docker service list

rm:
	@docker stack rm LEGA
	@sleep 10

clean:
	@rm -rf $(FILES)
	@rm -rf /tmp/tsd /tmp/vault /tmp/db
	@docker secret rm $(FILES)

test:
	@mvn --no-transfer-progress test
