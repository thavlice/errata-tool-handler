#!/usr/bin/env bash

# This script builds the schema, then tears down and rebuilds
# the local podman-compose development environment.
#
# It is intended to be run from the root of the project.

# Prequisites: Minikube environment with tekton installed

set -e

PROFILE=sbomer
NAMESPACE=sbomer-test
PLATFORM_REPO="https://github.com/sbomer-project/sbomer-platform.git"
PLATFORM_DIR="sbomer-platform"
LOCAL_CHART_PATH="helm/errata-tool-handler-chart"

echo "--- Checking Minikube status (Profile: $PROFILE) ---"

if ! minikube -p "$PROFILE" status > /dev/null 2>&1; then
    echo "Error: Minikube cluster '$PROFILE' is NOT running."
    echo ""
    echo "Please run the setup script first to start the cluster and install dependencies:"
    echo "./hack/setup-minikube.sh (and please leave it running so that the cluster can be exposed to the host at port 8001)"
    echo ""
    exit 1
fi

echo "--- Building local errata-tool-handler image inside of Minikube ---"
# Will have local image errata-tool-handler:latest in minikube
bash ./hack/build-local-errata-tool-handler-into-minikube.sh

echo "--- Setting up SBOMer Platform Chart ---"

# Clone the platform chart if it doesn't exist
if [ ! -d "$PLATFORM_DIR" ]; then
    echo "Cloning sbomer-platform..."
    git clone "$PLATFORM_REPO" "$PLATFORM_DIR"
else
    echo "sbomer-platform directory exists, updating..."
    git -C "$PLATFORM_DIR" pull
fi

# Update dependencies to pull the local chart
echo "Updating Helm dependencies..."
helm dependency update "$PLATFORM_DIR"

# Install/Upgrade with overrides
echo "--- Deploying to Minikube ---"
# We override the repository to just the image name (no quay.io prefix)
# and set pullPolicy to Never so K8s uses the image we just built in Minikube.

helm upgrade --install sbomer-release "./$PLATFORM_DIR" \
    --namespace $NAMESPACE \
    --create-namespace \
    --set global.includeKafka=true \
    --set global.includeApicurio=true \
    --set global.includeApiGateway=true

helm upgrade --install errata-tool-handler "./helm/errata-tool-handler-chart" \
    --namespace $NAMESPACE \
    --create-namespace \
    --set image.repository=localhost/errata-tool-handler \
    --set image.tag=latest \
    --set image.pullPolicy=Never \
    --set config.kafka.bootstrapServers="sbomer-release-kafka:9092" \
    --set config.kafka.schemaRegistryUrl="http://sbomer-release-apicurio:8080/apis/registry/v2"

echo "--- Forcing Rolling Restart to pick up new local image ---"
# We ignore "not found" errors in case it's the very first install
kubectl rollout restart deployment -n $NAMESPACE -l app.kubernetes.io/name=errata-tool-handler-chart || true

echo "--- Deployment Complete ---"
echo "You can check status with: kubectl get pods -n $NAMESPACE"
echo "You can port-forward with: kubectl port-forward svc/sbomer-release-gateway 8080:8080 -n $NAMESPACE"