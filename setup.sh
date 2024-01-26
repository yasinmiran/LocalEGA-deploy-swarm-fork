#!/bin/bash

export CEGA_AUTH_URL=http://cega-auth:8443/lega/v1/legas/users/
export CEGA_USERNAME=dummy
export CEGA_PASSWORD=dummy
export EGA_BOX_USERNAME=dummy
export EGA_BOX_PASSWORD=dummy
export CEGA_MQ_CONNECTION=amqps://test:test@cegamq:5671/lega?cacertfile=/etc/ega/ssl/CA.cert
export BROKER_HOST=cegamq
export BROKER_PORT=5671
export BROKER_USERNAME=test
export BROKER_PASSWORD=test
export BROKER_VHOST=lega
export BROKER_VALIDATE=false
export EXCHANGE=localega.v1
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
export CAROOT="$(mkcert -CAROOT)"
export FILES=("localhost+5.pem" "localhost+5-key.pem" "localhost+5-client.pem" "localhost+5-client-key.pem" "rootCA.pem" "rootCA.p12" "localhost+5.p12" "localhost+5-client.p12" "localhost+5-client-key.der" "rootCA-key.pem" "docker-stack.yml" "jwt.pub.pem" "jwt.priv.pem" "ega.pub.pem" "ega.sec.pass" "ega.sec.pem" "server.pem" "server-key.pem" "server.p12" "client.pem" "client-key.pem" "client-key.der" "client.p12" "init-mappings-db.sh")

# Function to check if the current node
# is part of a Docker Swarm
function is_swarm_active() {
  if [ "$(docker info --format '{{.Swarm.LocalNodeState}}')" = "active" ]; then
    return 0 # Swarm is active
  else
    return 1 # Swarm is not active
  fi
}

# Function to create the mappings
# table in PostgreSQL
function create_mappings_table() {
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
CREATE TABLE IF NOT EXISTS mapping
(
    ega_id          VARCHAR NOT NULL,
    elixir_id       VARCHAR NOT NULL,
    PRIMARY KEY (ega_id),
    UNIQUE (ega_id, elixir_id)
);
EOSQL
}

function generate_certs_and_secrets() {

  # Step 1: Generate and install the root
  # certificate authority (CA) using mkcert
  mkcert -install
  echo "CAROOT is $CAROOT"

  # Step 2: Generate SSL/TLS certificates for
  # localhost and other services
  mkcert localhost db vault mq tsd proxy

  # Step 3: Generate the client certificates for
  # localhost and other services
  mkcert -client localhost db vault mq tsd proxy

  # Step 4: Export SSL/TLS certificates and
  # private keys to PKCS#12 format
  openssl pkcs12 -export \
    -out localhost+5.p12 \
    -in localhost+5.pem \
    -inkey localhost+5-key.pem -passout pass:"${SERVER_CERT_PASSWORD}"
  openssl pkcs12 -export \
    -out localhost+5-client.p12 \
    -in localhost+5-client.pem \
    -inkey localhost+5-client-key.pem \
    -passout pass:"${CLIENT_CERT_PASSWORD}"

  # Step 5: Convert client key to DER format
  openssl pkcs8 -topk8 \
    -inform PEM \
    -in localhost+5-client-key.pem \
    -outform DER \
    -nocrypt \
    -out localhost+5-client-key.der

  # Step 6: Generate JWT private and public keys
  openssl genpkey -algorithm RSA \
    -out jwt.priv.pem \
    -pkeyopt rsa_keygen_bits:4096
  openssl rsa -pubout \
    -in jwt.priv.pem \
    -out jwt.pub.pem

  # Step 7: Create Docker secrets for JWT private
  # key, JWT public key, and other secrets
  docker secret create jwt.priv.pem jwt.priv.pem
  openssl rsa -pubout -in jwt.priv.pem -out jwt.pub.pem
  docker secret create jwt.pub.pem jwt.pub.pem
  printf "%s" "${KEY_PASSWORD}" >ega.sec.pass
  docker secret create ega.sec.pass ega.sec.pass
  crypt4gh generate -n ega -p ${KEY_PASSWORD}
  docker secret create ega.sec.pem ega.sec.pem

  # Step 8: Generate Docker stack configuration file
  if [ -f docker-template.yml ]; then
    if [ ! -f docker-stack.yml ]; then
      touch docker-stack.yml
      echo "docker-stack.yml created successfully."
    else
      echo "docker-stack.yml already exists. Continuing to overwrite..."
    fi
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
      j2 docker-template.yml >docker-stack.yml
    elif [[ "$OSTYPE" == "darwin"* ]]; then
      jinja2 docker-template.yml >docker-stack.yml
    fi
    echo "Wrote to docker-stack.yml successfully."
  else
    echo "Error: docker-template.yml template file is missing." >&2
    return 1 # Return a non-zero status to indicate failure
  fi

  # Step 9: Copy root CA certificate and private key
  cp "$CAROOT/rootCA.pem" rootCA.pem
  docker secret create rootCA.pem rootCA.pem
  cp "$CAROOT/rootCA-key.pem" rootCA-key.pem
  chmod 600 rootCA-key.pem
  docker secret create rootCA-key.pem rootCA-key.pem

  # Step 10: Export root CA certificate to PKCS#12 format
  openssl pkcs12 -export \
    -out rootCA.p12 \
    -in rootCA.pem \
    -inkey rootCA-key.pem \
    -passout pass:${ROOT_CERT_PASSWORD}
  docker secret create rootCA.p12 rootCA.p12

  # Step 11: Copy and create Docker secrets
  # for server and client certificates
  cp localhost+5.pem server.pem
  docker secret create server.pem server.pem
  cp localhost+5-key.pem server-key.pem
  docker secret create server-key.pem server-key.pem
  cp localhost+5.p12 server.p12
  docker secret create server.p12 server.p12
  cp localhost+5-client.pem client.pem
  docker secret create client.pem client.pem
  cp localhost+5-client-key.pem client-key.pem
  docker secret create client-key.pem client-key.pem
  cp localhost+5-client-key.der client-key.der
  docker secret create client-key.der client-key.der
  cp localhost+5-client.p12 client.p12
  docker secret create client.p12 client.p12

}

# Invokers --

function init() {

  if ! check_dependencies; then
    echo "Dependency check failed. Exiting."
    exit 1
  fi

  # Initialize the Docker swarm and make
  # the current node the manager.
  if is_swarm_active; then
    echo "This node is already part of a Docker Swarm ✅. Skipping..."
  else
    echo "Initializing Docker Swarm..."
    docker swarm init
  fi

  echo "Configuring Certificates..."
  generate_certs_and_secrets

  # Create the init-mappings-db.sh script
  echo "#!/bin/bash" >init-mappings-db.sh
  echo "set -e" >>init-mappings-db.sh
  echo "" >>init-mappings-db.sh
  echo "$create_mappings_table" >>init-mappings-db.sh

  # Create a Docker secret for init-mappings-db.sh
  docker secret create init-mappings-db.sh init-mappings-db.sh

  # Create and own the temporary dirs
  mkdir -p /tmp/tsd /tmp/vault /tmp/db
  chown 65534:65534 /tmp/vault /tmp/tsd
  chmod 777 /tmp/tsd /tmp/vault /tmp/db

}

function clean() {

  for file in "${FILES[@]}"; do
    if [ -e "$file" ]; then
      chmod 644 "$file"
      rm "$file"
      echo "Deleted: $file"
    else
      echo "File not found: $file"
    fi
  done

  rm -rf /tmp/tsd /tmp/vault /tmp/db

  # Remove Docker secrets
  for file in "${FILES[@]}"; do
    if docker secret ls | grep -q "$file"; then
      docker secret rm "$file"
      echo "Removed Docker secret: $file"
    fi
  done

  docker swarm leave --force
  echo "Left the Docker swarm"

  echo "Cleanup completed"

}

function start() {
  echo "Starting the LEGA stack 🚀"
  docker stack deploy LEGA -c docker-stack.yml
}

function stop() {
  echo "Stopping the LEGA stack 🛑"
  docker stack rm LEGA
}

# Utility functions --

# Check the existence of a passed command but discard
# the outcome through redirection whether its successful
# or erroneous.
function exists() {
  command -v "$1" 1>/dev/null 2>&1
}

# Pre-condition function to check
# for required dependencies
function check_dependencies() {
  local missing_deps=0
  # Define an array of dependencies
  local deps=("mkcert" "openssl" "docker" "crypt4gh")
  # Check for j2 or jinja2 based on the platform
  if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    deps+=("j2")
  elif [[ "$OSTYPE" == "darwin"* ]]; then
    deps+=("jinja2")
  fi
  for dep in "${deps[@]}"; do
    if ! exists "$dep"; then
      echo "Error: '$dep' is not installed." >&2
      missing_deps=$((missing_deps + 1))
    fi
  done
  if [ $missing_deps -ne 0 ]; then
    echo "Please install the missing dependencies before proceeding." >&2
    return 1 # Return a non-zero status to indicate failure
  else
    echo "All required dependencies are installed."
  fi
}

# Entry --

if [ $# -ne 1 ]; then
  echo "Usage: $0 [bootstrap|clean|start|stop]"
  exit 1
fi

# Parse the action argument and perform
# the corresponding action
case "$1" in
"clean")
  clean
  ;;
"bootstrap")
  init
  ;;
"start")
  start
  ;;
"stop")
  stop
  ;;
*)
  echo "Invalid action. Usage: $0 [delete|list]"
  exit 1
  ;;

esac
