#!/bin/bash

set -eux

TRINO_VERSION=${1:-475}
FORK_VERSION=${2:-475.2}
ARCHITECTURE=${3:-amd64}
architectures=(amd64 arm64 ppc64le)
package=trino-server-core
tag=trino-history-webui

# Build the image for the specified architecture
core/docker/build.sh -a "$ARCHITECTURE" -r "$TRINO_VERSION" -p "$package" -t "$tag"

# Tag for each architecture
docker tag "$tag:$TRINO_VERSION-$ARCHITECTURE" "$tag:$FORK_VERSION-$ARCHITECTURE"
