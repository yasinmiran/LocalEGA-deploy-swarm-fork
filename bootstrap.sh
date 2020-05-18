#!/bin/bash

mkcert -install
cp "$(mkcert -CAROOT)/rootCA.pem" rootCA.pem
cp "$(mkcert -CAROOT)/rootCA-key.pem" rootCA-key.pem
chmod 600 rootCA-key.pem
openssl pkcs12 -export -out rootCA.p12 -in rootCA.pem -inkey rootCA-key.pem -passout pass:"${ROOT_CERT_PASSWORD}"
mkcert localhost db vault public-mq private-mq tsd proxy kibana logstash elasticsearch
openssl pkcs12 -export -out localhost+9.p12 -in localhost+9.pem -inkey localhost+9-key.pem -passout pass:"${SERVER_CERT_PASSWORD}"
mkcert -client localhost db vault public-mq private-mq tsd proxy kibana logstash elasticsearch
openssl pkcs12 -export -out localhost+9-client.p12 -in localhost+9-client.pem -inkey localhost+9-client-key.pem -passout pass:"${CLIENT_CERT_PASSWORD}"
openssl pkcs8 -topk8 -inform PEM -in localhost+9-client-key.pem -outform DER -nocrypt -out localhost+9-client-key.der

docker swarm init
docker secret create rootCA.pem rootCA.pem
docker secret create rootCA.p12 rootCA.p12
docker secret create server.pem localhost+9.pem
docker secret create server-key.pem localhost+9-key.pem
docker secret create server.p12 localhost+9.p12
docker secret create client.pem localhost+9-client.pem
docker secret create client-key.pem localhost+9-client-key.pem
docker secret create client-key.der localhost+9-client-key.der
docker secret create client.p12 localhost+9-client.p12

openssl genpkey -algorithm RSA -out jwt.priv.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -pubout -in jwt.priv.pem -out jwt.pub.pem
docker secret create jwt.pub.pem jwt.pub.pem

# shellcheck disable=SC2059
printf "${KEY_PASSWORD}" > ega.sec.pass
crypt4gh generate -n ega -p "${KEY_PASSWORD}"
docker secret create ega.sec.pem ega.sec.pem
docker secret create ega.sec.pass ega.sec.pass
docker secret create ega.pub.pem ega.pub.pem

cp default.conf.ini conf.ini
perl -i -pe 's!KEY_PASSWORD!$ENV{"KEY_PASSWORD"}!g' conf.ini
perl -i -pe 's!MINIO_ACCESS_KEY!$ENV{"MINIO_ACCESS_KEY"}!g' conf.ini
perl -i -pe 's!MINIO_SECRET_KEY!$ENV{"MINIO_SECRET_KEY"}!g' conf.ini
perl -i -pe 's!DB_HOST!$ENV{"DB_HOST"}!g' conf.ini
perl -i -pe 's!DB_DATABASE_NAME!$ENV{"DB_DATABASE_NAME"}!g' conf.ini
perl -i -pe 's!DB_LEGA_IN_USER!$ENV{"DB_LEGA_IN_USER"}!g' conf.ini
perl -i -pe 's!DB_LEGA_IN_PASSWORD!$ENV{"DB_LEGA_IN_PASSWORD"}!g' conf.ini
perl -i -pe 's!MQ_CONNECTION!$ENV{"MQ_CONNECTION"}!g' conf.ini
perl -i -pe 's!TSD_PROJECT!$ENV{"TSD_PROJECT"}!g' conf.ini
docker secret create conf.ini conf.ini

cp default.elasticsearch.yml elasticsearch.yml
docker secret create elasticsearch.yml elasticsearch.yml

cp default.kibana.yml kibana.yml
perl -i -pe 's!ELASTIC_PASSWORD!$ENV{"ELASTIC_PASSWORD"}!g' kibana.yml
docker secret create kibana.yml kibana.yml

cp default.logstash.yml logstash.yml
perl -i -pe 's!ELASTIC_PASSWORD!$ENV{"ELASTIC_PASSWORD"}!g' logstash.yml
docker secret create logstash.yml logstash.yml

cp default.logstash.conf logstash.conf
perl -i -pe 's!ELASTIC_PASSWORD!$ENV{"ELASTIC_PASSWORD"}!g' logstash.conf
docker secret create logstash.conf logstash.conf

cp default.logstash-logger.yaml logstash-logger.yaml
perl -i -pe 's!LOGSTASH_HOST!$ENV{"LOGSTASH_HOST"}!g' logstash-logger.yaml
perl -i -pe 's!LOGSTASH_PORT!$ENV{"LOGSTASH_PORT"}!g' logstash-logger.yaml
docker secret create logstash-logger.yaml logstash-logger.yaml

docker-compose config > docker-stack.yml
