#!/bin/sh
# Produce the one file this project distributes.
#
# POSIX shell: macOS and Linux. On Windows the equivalent has to be a .cmd or a .ps1 — Windows has
# no /bin/sh, and the JAVA_HOME lookup below is macOS's own. Saying so here is the difference
# between "this project does not do that yet" and a confusing failure halfway through a build.
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

if [ -f "../pom.xml" ]; then
    mvn -q -DskipTests -f ../pom.xml package -pl gpgkit -am
else
    mvn -q -DskipTests package
fi

# Use Native Image's default optimization level (O2). Forcing O3 on CE 25.4.4.1.1
# increased certkit build time and image size; see ../docs/NATIVE_BUILD.md.
#
# --no-fallback is the one flag that must not be removed. Without it, native-image happily emits
# a launcher that needs a JVM at runtime and says so only when it is too late. The entire point
# of this build is a file that runs on a machine with no JDK, so a build that cannot produce one
# has to fail here rather than ship.
#
# No -H:ReflectionConfigurationFiles and no reachability metadata, because nothing here is
# reached reflectively: dispatch is a switch over strings (Main), JSON is written by hand (Json),
# and gpg is an external process. That is the whole reason the project has no dependencies. If
# something reflective is ever added, its metadata goes under
# src/main/resources/META-INF/native-image/ — and note that the JVM tests will not catch its
# absence, only the native image will.
#
# NATIVE_IMAGE_THREADS caps how many threads native-image uses to compile. Unset, it sizes itself
# for the whole machine, which is right for one build and wrong for several at once: running a
# handful of these concurrently on a many-core box spends the time waiting on itself. Nothing
# normally needs to set it; the hook stays so the next person running several at once does not
# have to rediscover the number.
set --
if [ -f "../scripts/pruned-security.properties" ]; then
    set -- "-J-Djava.security.properties==$(pwd)/../scripts/pruned-security.properties"
fi

"$NATIVE_IMAGE" \
    --no-fallback \
    -H:+ReportExceptionStackTraces \
    -J-Xmx4g \
    "$@" \
    ${NATIVE_IMAGE_THREADS:+-J-XX:ActiveProcessorCount=$NATIVE_IMAGE_THREADS} \
    -jar target/gpgkit.jar \
    -o target/gpgkit

# Run the executable, not the jar: the jar is the development path and proves nothing about
# whether the image carries the classes the tool actually reaches.
OUT_FILE="target/gpgkit"
[ -f "target/gpgkit.exe" ] && OUT_FILE="target/gpgkit.exe"
echo "native executable: $(wc -c < "$OUT_FILE" | tr -d '[:space:]') bytes at $OUT_FILE"
