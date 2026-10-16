#!/bin/sh
# Produce the one file this project distributes.
#
# POSIX shell: macOS and Linux. On Windows the equivalent has to be a .cmd or a .ps1 — Windows has
# no /bin/sh, and the JAVA_HOME lookup below is macOS's own. Saying so here is the difference
# between "this project does not do that yet" and a confusing failure halfway through a build.
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
    # native-image ships with GraalVM, not with a plain JDK, so it is not searched for. A
    # JAVA_HOME without one is the wrong JAVA_HOME, not a reason to go looking for another.
    echo "build-native.sh: no native-image in $JDK_HOME" >&2
    echo "build-native.sh: install GraalVM for JDK 25 and point JAVA_HOME at it" >&2
    exit 1
fi

# Compile first and let the image build only read target/classes. There is no
# dependency:build-classpath step here, unlike the GUI project next door: this module has no
# dependencies at all, so an empty classpath beyond its own classes is the whole story.
mvn -q -o -DskipTests package

# -O3 rather than the default -O2: this is a command people wait on, the build is seconds longer,
# and the image is smaller. The level is a deliberate choice, not a default left in place.
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
"$NATIVE_IMAGE" \
    --no-fallback \
    -H:+ReportExceptionStackTraces \
    -J-Xmx4g \
    ${NATIVE_IMAGE_THREADS:+-J-XX:ActiveProcessorCount=$NATIVE_IMAGE_THREADS} \
    -O3 \
    -cp target/classes \
    -o target/gpgkit \
    io.github.lihuu.gpgkit.Main

# Run the executable, not the jar: the jar is the development path and proves nothing about
# whether the image carries the classes the tool actually reaches.
echo "native executable: $(wc -c < target/gpgkit | tr -d '[:space:]') bytes at target/gpgkit"
