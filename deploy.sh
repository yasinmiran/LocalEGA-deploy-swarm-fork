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

  ./gradlew generateCertificate \
    -PsubjectString=C=NO,ST=Oslo,L=Oslo,O=UiO,OU=IFI,CN=nels-developers@googlegroups.com \
    -PfileName=client-server \
    -PipAddress=$TSD_IP_ADDRESS \
    -Ptype=BOTH \
    -ProotCA=rootCA.pem \
    -ProotCAKey=rootCA-key.pem \
    --stacktrace

  ./gradlew generatePGPKey -Pid=ega -Ppassphrase=$PGP_PASSPHRASE
  printf $LEGA_PASSWORD > ega.shared.pass

  ./gradlew generateConfIni

  docker config create CA.cert rootCA.pem
  docker config create client-server.cert client-server.pem
  docker config create client-server.key client-server-key.pem
  docker config create conf.ini conf.ini
  docker config create ega.sec ega.sec
  docker config create ega.sec.pass ega.sec.pass
  docker config create ega.pub ega.pub
  docker config create ega.shared.pass ega.shared.pass

  docker stack deploy LEGA --compose-file docker-stack.yml

  sleep 30
fi
