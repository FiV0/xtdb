#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

cd "$REPO_ROOT"

# Get current branch name (sanitized for Docker tag)
BRANCH=$(git rev-parse --abbrev-ref HEAD | sed 's/[^a-zA-Z0-9._-]/-/g')
SHA=$(git rev-parse --short HEAD)
TAG="${BRANCH}-${SHA}"

echo "Building images with tag: $TAG"
echo ""

# Build JARs
echo "=== Building xtdb-aws JAR ==="
./gradlew :docker:aws:shadowJar

echo ""
echo "=== Building xtdb-bench JAR ==="
./gradlew :modules:bench:shadowJar

echo ""
echo "=== Building xtdb-aws Docker image ==="
docker build \
    -t "xtdb/xtdb-aws:${TAG}" \
    -t "xtdb/xtdb-aws:local" \
    --build-arg GIT_SHA="$SHA" \
    --build-arg XTDB_VERSION="2-SNAPSHOT" \
    docker/aws

echo ""
echo "=== Building xtdb-bench Docker image ==="
docker build \
    -t "xtdb/xtdb-bench:${TAG}" \
    -t "xtdb/xtdb-bench:local" \
    --build-arg GIT_SHA="$SHA" \
    --build-arg XTDB_VERSION="2-SNAPSHOT" \
    -f modules/bench/Dockerfile \
    .

echo ""
echo "=== Done ==="
echo ""
echo "Images built:"
echo "  xtdb/xtdb-aws:${TAG}"
echo "  xtdb/xtdb-aws:local"
echo "  xtdb/xtdb-bench:${TAG}"
echo "  xtdb/xtdb-bench:local"
echo ""
echo "To use these images, update docker-compose.yaml or run:"
echo "  docker-compose -f $SCRIPT_DIR/docker-compose.yaml up -d"
