#!/usr/bin/env bash

# Intended to be run from the project root directory

# schema repo name
SCHEMA_REPO_DIR="sbomer-contracts"
# schema repo URL
SCHEMA_REPO_URL="https://github.com/sbomer-project/${SCHEMA_REPO_DIR}.git"
# build command for the schemas
SCHEMA_BUILD_CMD="mvn clean install"
# build command for the component
COMPONENT_BUILD_CMD="mvn clean package -Dquarkus.profile=dev"

set -e

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