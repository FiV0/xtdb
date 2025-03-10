#!/usr/bin/env bash

set -e
(
    cd $(dirname $0)/..

    if [ "$1" == "--clean" ] || ! [ -e aws/build/libs/xtdb-aws.jar ]; then
        ../gradlew ":docker:aws:shadowJar"
    fi

    mkdir -p docker-compose/build
    cp aws/build/libs/xtdb-aws.jar docker-compose/build/xtdb-aws.jar

    sha=$(git rev-parse --short HEAD)

    echo Building Docker image ...
    docker build -t xtdb/xtdb-docker-compose --build-arg GIT_SHA="$sha" --build-arg XTDB_VERSION="${XTDB_VERSION:-2-SNAPSHOT}" --output type=docker docker-compose
    echo Done
)
