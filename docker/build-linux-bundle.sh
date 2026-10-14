#!/bin/sh
# Builds the self-contained Linux bundle (cli/build/distributions/micdrop-bundle.tar.gz) in a Linux container, so it
# can be done from any machine with Docker. Run from anywhere; set BASE_IMAGE to build against the distribution (and
# so the glibc) of the servers that will run it.
set -eu

cd "$(dirname "$0")/.."
BASE_IMAGE="${BASE_IMAGE:-eclipse-temurin:24-jdk}"

docker run --rm \
    -v "$PWD:/src" -w /src \
    -v micdrop-gradle-cache:/root/.gradle \
    "$BASE_IMAGE" \
    sh -c './gradlew --no-daemon :cli:appImageTar && chown -R "$(stat -c %u:%g /src)" /src/build /src/cli/build /src/.gradle'

echo "Created cli/build/distributions/micdrop-bundle.tar.gz"
