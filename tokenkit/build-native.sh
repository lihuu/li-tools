#!/bin/sh
set -e
cd "$(dirname "$0")"

JDK_HOME="${JAVA_HOME:-}"
if [ -n "$JDK_HOME" ]; then
    JDK_HOME="$(echo "$JDK_HOME" | tr '\\' '/')"
elif [ -x /usr/libexec/java_home ]; then
    JDK_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
fi

if command -v native-image >/dev/null 2>&1; then
    NATIVE_IMAGE="$(command -v native-image)"
elif command -v native-image.cmd >/dev/null 2>&1; then
    NATIVE_IMAGE="$(command -v native-image.cmd)"
elif [ -n "$JDK_HOME" ] && [ -f "$JDK_HOME/bin/native-image" ]; then
    NATIVE_IMAGE="$JDK_HOME/bin/native-image"
elif [ -n "$JDK_HOME" ] && [ -f "$JDK_HOME/bin/native-image.cmd" ]; then
    NATIVE_IMAGE="$JDK_HOME/bin/native-image.cmd"
elif [ -n "$JDK_HOME" ] && [ -f "$JDK_HOME/bin/native-image.exe" ]; then
    NATIVE_IMAGE="$JDK_HOME/bin/native-image.exe"
else
    echo "build-native.sh: no native-image found in PATH or JAVA_HOME ($JDK_HOME)" >&2
    echo "build-native.sh: install GraalVM for JDK 25 and point JAVA_HOME at it" >&2
    exit 1
fi

mvn -q -DskipTests package

"$NATIVE_IMAGE" \
    --no-fallback \
    --enable-native-access=ALL-UNNAMED \
    -H:+ReportExceptionStackTraces \
    -J-Xmx4g \
    ${NATIVE_IMAGE_THREADS:+-J-XX:ActiveProcessorCount=$NATIVE_IMAGE_THREADS} \
    -O3 \
    -jar target/tokenkit.jar \
    -o target/tokenkit

OUT_FILE="target/tokenkit"
[ -f "target/tokenkit.exe" ] && OUT_FILE="target/tokenkit.exe"
echo "native executable: $(wc -c < "$OUT_FILE" | tr -d '[:space:]') bytes at $OUT_FILE"
