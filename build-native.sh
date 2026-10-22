#!/bin/sh
set -e
cd "$(dirname "$0")"

if [ "$#" -ne 0 ]; then
    echo "usage: build-native.sh" >&2
    exit 2
fi

# Prepare every JAR in one reactor before entering any Native Image build.
mvn -q -DskipTests package -pl gpgkit,certkit,tokenkit -am

for tool in gpgkit certkit tokenkit; do
    "./$tool/build-native.sh" --skip-package
done
