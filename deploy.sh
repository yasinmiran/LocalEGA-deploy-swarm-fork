#!/bin/bash

if [[ "$TRAVIS_BRANCH" == "master" && "$TRAVIS_PULL_REQUEST" == "false" ]]; then
  docker stack rm LEGA

  sleep 10
  docker volume rm LEGA_inbox_s3
  docker volume rm LEGA_vault_s3
  docker volume rm LEGA_db

  docker config rm CA.cert
  docker config rm client-server.cert
  docker config rm client-server.key
  docker config rm db.entrypoint.sh
  docker config rm conf.ini
  docker config rm ega.sec
  docker config rm ega.sec.pass
  docker config rm ega2.sec
  docker config rm ega2.sec.pass
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
  ./gradlew generatePGPKey -Pid=ega2 -Ppassphrase=$PGP_PASSPHRASE
  echo $LEGA_PASSWORD > ega.shared.pass

  ./gradlew generateConfIni

  docker config create CA.cert rootCA.pem
  docker config create client-server.cert client-server.pem
  docker config create client-server.key client-server-key.pem
  docker config create db.entrypoint.sh ./db/entrypoint.sh
  docker config create conf.ini conf.ini
  docker config create ega.sec ega.sec
  docker config create ega.sec.pass ega.sec.pass
  docker config create ega2.sec ega2.sec
  docker config create ega2.sec.pass ega2.sec.pass
  docker config create ega.shared.pass ega.shared.pass

  docker stack deploy LEGA --compose-file docker-stack.yml
fi
