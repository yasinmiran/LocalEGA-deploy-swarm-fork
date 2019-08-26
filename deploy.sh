#!/bin/bash

if [[ "$TRAVIS_BRANCH" == "master" && "$TRAVIS_PULL_REQUEST" == "false" ]]; then
  gradle generateCertificate \
    -PsubjectString=C=NO,ST=Oslo,L=Oslo,O=UiO,OU=IFI,CN=nels-developers@googlegroups.com \
    -PfileName=client-server \
    -PipAddress=$TSD_IP_ADDRESS \
    -Ptype=BOTH \
    -ProotCA=rootCA.pem \
    -ProotCAKey=rootCA-key.pem \
    --stacktrace

  gradle generatePGPKey -Pid=ega -Ppassphrase=$PGP_PASSPHRASE
  printf $LEGA_PASSWORD > ega.shared.pass

  gradle generateConfIni

  docker config create CA.cert rootCA.pem
  docker config create client-server.cert client-server.pem
  docker config create client-server.key client-server-key.pem
  docker config create conf.ini conf.ini
  docker config create ega.sec ega.sec
  docker config create ega.sec.pass ega.sec.pass
  docker config create ega.pub ega.pub
  docker config create ega.shared.pass ega.shared.pass

  docker-compose config > docker-stack.yml
  docker stack deploy LEGA -c docker-stack.yml

  sleep 30
fi
