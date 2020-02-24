#!/bin/bash

curl -L https://github.com/FiloSottile/mkcert/releases/download/v1.4.1/mkcert-v1.4.1-linux-amd64 -o ~/mkcert
chmod +x ~/mkcert
shopt -s expand_aliases
alias mkcert="~/mkcert"
curl -fsSL https://raw.githubusercontent.com/elixir-oslo/crypt4gh/master/install.sh | sh
