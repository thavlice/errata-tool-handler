#!/usr/bin/env bash

# This script builds the schema, then tears down and rebuilds
# the local podman-compose development environment.
#
# It is intended to be run from the root of the project.

set -e

# Path to compose file
COMPOSE_FILE="./podman/podman-compose.yml"

echo "--- Building the component with schemas ---"
bash ./hack/build-with-schemas.sh

echo "--- Switching to podman folder ---"
pushd podman

echo "--- Creating bindmounts --"
[ ! -d "./kafka-data" ] && mkdir ./kafka-data && podman unshare chown 1001:0 ./kafka-data
[ ! -d "./kafka-config" ] && mkdir ./kafka-config && podman unshare chown 1001:0 ./kafka-config

echo "--- Starting podman-compose ---"
podman-compose up --build --force-recreate

echo "--- Local podman-compose is now running ---"