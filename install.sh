#!/bin/bash

curl -L https://github.com/FiloSottile/mkcert/releases/download/v1.4.1/mkcert-v1.4.1-linux-amd64 -o ~/mkcert
chmod +x ~/mkcert
shopt -s expand_aliases
alias mkcert="~/mkcert"
curl -L https://github.com/uio-bmi/crypt4gh/releases/download/v2.3.0/crypt4gh.jar -o ~/crypt4gh.jar
alias crypt4gh="java -jar ~/crypt4gh.jar"
