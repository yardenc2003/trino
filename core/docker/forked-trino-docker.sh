#!/bin/bash

set -eux

TRINO_VERSION=${1:-475}
FORK_VERSION=${2:-475.2}
architectures=(arm64)
package=trino-server-core
tag=trino-history-webui

# Build the image
core/docker/build.sh -a "$(IFS=, ; echo "${architectures[*]}")" -r "$TRINO_VERSION" -p "$package" -t "$tag"

# Tag for each architecture
for arch in "${architectures[@]}"; do
    docker tag "$tag:$TRINO_VERSION-$arch" "$tag:$FORK_VERSION-$arch"
done
