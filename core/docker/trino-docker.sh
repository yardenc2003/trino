#!/bin/bash

set -eux

VERSION=$1
architectures=(amd64 arm64 ppc64le)
packages=(trino-server-core trino-server)
tags=(trino-history-core trino-history)

for index in "${!packages[@]}"; do
    TAG=${tags[$index]}
    TARGET=$TAG:$VERSION
    core/docker/build.sh -a "$(IFS=, ; echo "${architectures[*]}")" -r "$VERSION" -p "${packages[$index]}" -t "$TAG"

    for arch in "${architectures[@]}"; do
        docker tag "$TAG:$VERSION-$arch" "$TARGET-$arch"
    done
done
