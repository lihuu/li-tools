#!/bin/sh
set -e
cd "$(dirname "$0")"

if [ "$#" -ne 0 ]; then
    echo "usage: build-native.sh" >&2
    exit 2
fi

TOOLS="gpgkit certkit tokenkit skillkit jsonkit dukit mitmkit"

# Native Image already uses several threads per build, so two to four concurrent
# builds is the sweet spot: enough overlap to hide JVM startup and analysis,
# not so many that the builds fight over cores and memory.
JOBS="${NATIVE_BUILD_JOBS:-}"
if [ -z "$JOBS" ]; then
    CPUS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
    case "$CPUS" in
        ''|*[!0-9]*) CPUS=4 ;;
    esac
    JOBS=$((CPUS / 2))
    [ "$JOBS" -lt 1 ] && JOBS=1
    [ "$JOBS" -gt 4 ] && JOBS=4
fi
case "$JOBS" in
    ''|*[!0-9]*|0)
        echo "build-native.sh: NATIVE_BUILD_JOBS must be a positive integer" >&2
        exit 2
        ;;
esac

# Prepare every JAR in one reactor before entering any Native Image build.
mvn -q -DskipTests package -pl gpgkit,certkit,tokenkit,skillkit,jsonkit,dukit,mitmkit -am

if [ "$JOBS" -le 1 ]; then
    for tool in $TOOLS; do
        "./$tool/build-native.sh" --skip-package
    done
    exit 0
fi

STATUS_DIR="$(mktemp -d "${TMPDIR:-/tmp}/li-tools-native.XXXXXX")"
cleanup() {
    rm -rf "$STATUS_DIR"
}
trap cleanup EXIT INT TERM

running=0
for tool in $TOOLS; do
    while [ "$running" -ge "$JOBS" ]; do
        running="$(jobs -rp | wc -l | tr -d ' ')"
        [ "$running" -ge "$JOBS" ] && sleep 1
    done
    (
        if "./$tool/build-native.sh" --skip-package > "$STATUS_DIR/$tool.log" 2>&1; then
            printf '0\n' > "$STATUS_DIR/$tool.status"
        else
            printf '%s\n' "$?" > "$STATUS_DIR/$tool.status"
        fi
    ) &
    running=$((running + 1))
    echo "started $tool"
done
wait

failed=0
failed_code=1
for tool in $TOOLS; do
    code="$(cat "$STATUS_DIR/$tool.status" 2>/dev/null || echo 1)"
    if [ "$code" -ne 0 ]; then
        echo "FAILED $tool (exit $code)" >&2
        sed 's/^/  /' "$STATUS_DIR/$tool.log" >&2
        [ "$failed" -eq 0 ] && failed_code="$code"
        failed=$((failed + 1))
    else
        tail -n 1 "$STATUS_DIR/$tool.log"
    fi
done

if [ "$failed" -ne 0 ]; then
    echo "build-native.sh: $failed tool(s) failed" >&2
    exit "$failed_code"
fi
