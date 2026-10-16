#!/bin/sh
set -e
cd "$(dirname "$0")"

JDK_HOME="${JAVA_HOME:-}"
if [ -z "$JDK_HOME" ] && [ -x /usr/libexec/java_home ]; then
    JDK_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
fi

if command -v native-image >/dev/null 2>&1; then
    NATIVE_IMAGE="$(command -v native-image)"
elif [ -n "$JDK_HOME" ] && [ -x "$JDK_HOME/bin/native-image" ]; then
    NATIVE_IMAGE="$JDK_HOME/bin/native-image"
elif [ -n "$JDK_HOME" ] && [ -x "$JDK_HOME/bin/native-image.cmd" ]; then
    NATIVE_IMAGE="$JDK_HOME/bin/native-image.cmd"
elif [ -n "$JDK_HOME" ] && [ -x "$JDK_HOME/bin/native-image.exe" ]; then
    NATIVE_IMAGE="$JDK_HOME/bin/native-image.exe"
else
    echo "build-native.sh: no native-image found in PATH or JAVA_HOME ($JDK_HOME)" >&2
    echo "build-native.sh: install GraalVM for JDK 25 and point JAVA_HOME at it" >&2
    exit 1
fi

mvn -q -DskipTests package

"$NATIVE_IMAGE" \
    --no-fallback \
    -H:+ReportExceptionStackTraces \
    -J-Xmx4g \
    ${NATIVE_IMAGE_THREADS:+-J-XX:ActiveProcessorCount=$NATIVE_IMAGE_THREADS} \
    -O3 \
    -jar target/certkit.jar \
    -o target/certkit
