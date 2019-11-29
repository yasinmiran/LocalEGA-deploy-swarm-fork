#!/bin/bash

curl -L https://services.gradle.org/distributions/gradle-6.0.1-bin.zip -o gradle.zip
sudo unzip -qq -d /opt/gradle gradle.zip && rm gradle.zip
export GRADLE_HOME=/opt/gradle/gradle-6.0.1
export PATH=${GRADLE_HOME}/bin:${PATH}
curl -L https://github.com/FiloSottile/mkcert/releases/download/v1.4.1/mkcert-v1.4.1-linux-amd64 -o ~/mkcert
chmod +x ~/mkcert
shopt -s expand_aliases
alias mkcert="~/mkcert"
curl -L https://github.com/uio-bmi/crypt4gh/releases/download/v2.3.0/crypt4gh.jar -o ~/crypt4gh.jar
alias crypt4gh="java -jar ~/crypt4gh.jar"
