#!/usr/bin/env bash

# Intended to be run from the project root directory

# schema repo name
SCHEMA_REPO_DIR="sbomer-contracts"
# schema repo URL
SCHEMA_REPO_URL="https://github.com/sbomer-project/${SCHEMA_REPO_DIR}.git"
# build command for the schemas
SCHEMA_BUILD_CMD="mvn clean install"

TARGET_PROFILE=${1:-dev}
echo "Building with Quarkus Profile: $TARGET_PROFILE"
# build command for the component
COMPONENT_BUILD_CMD="mvn clean package -Dquarkus.profile=$TARGET_PROFILE"

set -e

# Detect the Operating System
OS="$(uname -s)"
echo "Detected OS: $OS"

if [ "$OS" = "Linux" ]; then
    # Linux: Ask Podman directly where the socket is
    SOCKET_PATH=$(podman info --format '{{.Host.RemoteSocket.Path}}')

    if [ -z "$SOCKET_PATH" ]; then
        echo "Error: Could not determine Podman socket path on Linux."
        exit 1
    fi

    export DOCKER_HOST="unix://$SOCKET_PATH"
    echo "Configured for Linux Podman: $DOCKER_HOST"

elif [ "$OS" = "Darwin" ]; then
    # macOS: Check if Podman machine is running first
    status=$(podman machine info --format "{{.Host.MachineState}}" 2>/dev/null)
    if [ "$status" != "Running" ]; then
        echo "Starting Podman machine..."
        podman machine start
    fi

    # macOS: Point to the socket inside the Podman VM
    export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
    echo "Configured for macOS Podman: $DOCKER_HOST"

else
    echo "Warning: Unsupported OS ($OS). Assuming Docker environment is already set."
fi

# Common Configuration
# Set Docker API version for compatibility with Podman 5
export DOCKER_API_VERSION=1.44
# Disable Ryuk (resource reaper) which often causes issues with Podman
export TESTCONTAINERS_RYUK_DISABLED=true

# Clone the schema repo only if it doesn't exist
if [ ! -d "$SCHEMA_REPO_DIR" ]; then
  echo "Cloning schema repo..."
  git clone $SCHEMA_REPO_URL
else
  echo "Schema repo already exists, skipping clone and updating repository"
  pushd $SCHEMA_REPO_DIR
  git pull
  popd
fi

# Go into the schema repo and build it
pushd $SCHEMA_REPO_DIR
echo "Building schemas in $(pwd)..."
$SCHEMA_BUILD_CMD

# Go back to the component directory
popd
echo "Back in $(pwd)."

echo "--- Building Component ---"
$COMPONENT_BUILD_CMD

echo "--- Build Complete ---"