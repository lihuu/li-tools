#!/bin/sh
set -e
cd "$(dirname "$0")"

JDK_HOME="${JAVA_HOME:-}"
if [ -z "$JDK_HOME" ] && [ -x /usr/libexec/java_home ]; then
    JDK_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
fi
if [ -z "$JDK_HOME" ]; then
    echo "build-native.sh: set JAVA_HOME to a GraalVM for JDK 25" >&2
    exit 1
fi

NATIVE_IMAGE="$JDK_HOME/bin/native-image"
if [ ! -x "$NATIVE_IMAGE" ]; then
    echo "build-native.sh: no native-image in $JDK_HOME" >&2
    echo "build-native.sh: install GraalVM for JDK 25 and point JAVA_HOME at it" >&2
    exit 1
fi

mvn -q -o -DskipTests package

"$NATIVE_IMAGE" \
    --no-fallback \
    -H:+ReportExceptionStackTraces \
    -J-Xmx4g \
    ${NATIVE_IMAGE_THREADS:+-J-XX:ActiveProcessorCount=$NATIVE_IMAGE_THREADS} \
    -O3 \
    -cp target/classes \
    -o target/certkit \
    io.github.lihuu.certkit.Main
