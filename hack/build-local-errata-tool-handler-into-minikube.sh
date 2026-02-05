#!/bin/bash

# Exit immediately if a command fails
set -e

# Variables for minikube profile and errata-tool-handler
SBOM_SERVICE_IMAGE="errata-tool-handler:latest"
PROFILE="sbomer"
TAR_FILE="errata-tool-handler.tar"

echo "--- Building and inserting errata-tool-handler image into Minikube registry ---"

bash ./hack/build-with-schemas.sh prod

podman build --format docker -t "$SBOM_SERVICE_IMAGE" -f src/main/docker/Dockerfile.jvm .

echo "--- Exporting errata-tool-handler image to archive ---"
if [ -f "$TAR_FILE" ]; then
    rm "$TAR_FILE"
fi
podman save -o "$TAR_FILE" "$SBOM_SERVICE_IMAGE"

echo "--- Loading errata-tool-handler into Minikube ---"
# This sends the file to Minikube
minikube -p "$PROFILE" image load "$TAR_FILE"

echo "--- Cleanup ---"
rm "$TAR_FILE"

echo "Done! Image '$SBOM_SERVICE_IMAGE' is ready in cluster '$PROFILE'."