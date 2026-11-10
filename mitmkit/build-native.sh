#!/bin/sh
set -e
cd "$(dirname "$0")"

if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "$1" != "--skip-package" ]; }; then
    echo "usage: build-native.sh [--skip-package]" >&2
    exit 2
fi

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

if [ "${1:-}" = "--skip-package" ]; then
    if [ ! -f "target/mitmkit.jar" ]; then
        echo "build-native.sh: --skip-package requires target/mitmkit.jar; run Maven package first" >&2
        exit 1
    fi
elif [ -f "../pom.xml" ]; then
    mvn -q -DskipTests -f ../pom.xml package -pl mitmkit -am
else
    mvn -q -DskipTests package
fi

set --
if [ -f "../scripts/pruned-security.properties" ]; then
    set -- "-J-Djava.security.properties==$(pwd)/../scripts/pruned-security.properties"
fi

# BouncyCastle is initialized explicitly via Security.addProvider, so no reflection
# metadata is required; the JCA/JCE providers must survive the pruned security file.
"$NATIVE_IMAGE" \
    --no-fallback \
    --enable-native-access=ALL-UNNAMED \
    -H:+ReportExceptionStackTraces \
    -J-Xmx4g \
    "$@" \
    ${NATIVE_IMAGE_THREADS:+-J-XX:ActiveProcessorCount=$NATIVE_IMAGE_THREADS} \
    -jar target/mitmkit.jar \
    -o target/mitmkit

OUT_FILE="target/mitmkit"
[ -f "target/mitmkit.exe" ] && OUT_FILE="target/mitmkit.exe"
echo "native executable: $(wc -c < "$OUT_FILE" | tr -d '[:space:]') bytes at $OUT_FILE"
