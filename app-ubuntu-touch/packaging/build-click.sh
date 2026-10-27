#!/bin/bash
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
: "${UT_ARM_PACKAGER_IMAGE:?Build arm-packager.Dockerfile and set an image ID or digest}"
: "${UT_OUTPUT:?Choose a new absent absolute output directory}"
[[ "$UT_OUTPUT" = /* && ! -e "$UT_OUTPUT" ]] || { echo 'UT_OUTPUT must be absolute and absent' >&2; exit 1; }
[[ ${UT_BUILD_JOBS:-4} =~ ^[1-9][0-9]*$ ]] || exit 1
STAGE=$(mktemp -d "$(dirname "$UT_OUTPUT")/.mail-demo-build.XXXXXX")
trap 'echo "Build staging retained: $STAGE" >&2' EXIT
cd "$ROOT"
./gradlew :app-ubuntu-touch:stageUtJvmInput -PutTargetArch=arm64 "-PutJvmOutput=$STAGE/inputs" --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers="${UT_BUILD_JOBS:-4}"
mkdir "$STAGE/image"
# Iteration may reuse a previously verified target-native JRE. jpackage still
# runs on ARM64; no host runtime is ever relabelled as the target.
runtime_mounts=()
runtime_options=(--add-modules ALL-MODULE-PATH --jlink-options '--strip-debug --no-man-pages --no-header-files')
if [[ -n ${UT_RUNTIME_IMAGE:-} ]]; then
    [[ "$UT_RUNTIME_IMAGE" = /* && -d "$UT_RUNTIME_IMAGE" ]] || exit 1
    readelf -h "$UT_RUNTIME_IMAGE/lib/server/libjvm.so" | grep -q 'Machine:.*AArch64'
    [[ -f "$UT_RUNTIME_IMAGE/lib/libawt.so" && -x "$UT_RUNTIME_IMAGE/bin/java" ]] || exit 1
    runtime_mounts=(-v "$UT_RUNTIME_IMAGE:/target-runtime:ro")
    runtime_options=(--runtime-image /target-runtime)
fi
docker run --rm --platform linux/arm64 --network none --cpus "${UT_BUILD_JOBS:-4}" --memory 4g \
    --user "$(id -u):$(id -g)" -v "$STAGE/inputs:/inputs:ro" -v "$STAGE/image:/output" \
    "${runtime_mounts[@]}" "$UT_ARM_PACKAGER_IMAGE" /usr/lib/jvm/java-21-openjdk-arm64/bin/jpackage \
    --type app-image --name mail-demo --input /inputs --main-jar mail-demo.jar \
    --main-class net.thunderbird.app.ubuntutouch.MainKt --dest /output --app-version 0.1.8 \
    "${runtime_options[@]}"
mkdir "$STAGE/payload"
cp -a "$STAGE/image/mail-demo" "$STAGE/payload/"
for name in manifest.json mail-demo.apparmor mail-demo.desktop mail-demo.svg launch.sh clickable.yaml; do
    cp "$HERE/$name" "$STAGE/payload/"
done
cp "$ROOT/LICENSE" "$STAGE/payload/mail-demo/UPSTREAM-LICENSE"
docker image inspect "$UT_ARM_PACKAGER_IMAGE" > "$STAGE/payload/mail-demo/PACKAGER-IMAGE.json"
docker run --rm --platform linux/arm64 --network none "$UT_ARM_PACKAGER_IMAGE" cat /BUILD-PACKAGES.txt > "$STAGE/payload/mail-demo/PACKAGER-PACKAGES.txt"
if [[ -n ${UT_RUNTIME_IMAGE:-} ]]; then
    sha256sum "$UT_RUNTIME_IMAGE/lib/modules" > "$STAGE/payload/mail-demo/REUSED-RUNTIME-SHA256.txt"
fi
git rev-parse HEAD > "$STAGE/payload/mail-demo/SOURCE-COMMIT.txt"
git diff --binary HEAD > "$STAGE/payload/mail-demo/SOURCE-CHANGES.patch"
tar --exclude=build -czf "$STAGE/payload/mail-demo/PORT-SOURCES.tar.gz" app-ubuntu-touch settings.gradle.kts gradle/libs.versions.toml docs/engineering/ubuntu-touch-roadmap.md components/ui/bolt/src/commonMain/kotlin/net/thunderbird/components/ui/bolt/atom/textfield/TextFieldOutlinedPassword.kt
# Refuse to replace an existing output, including one created since the initial check.
mkdir "$UT_OUTPUT"
cp -a "$STAGE/payload/." "$UT_OUTPUT/"
cd "$UT_OUTPUT"
clickable build --arch arm64
"$HERE/verify-click.sh" "$(find "$UT_OUTPUT/build" -name '*.click' -print -quit)"
