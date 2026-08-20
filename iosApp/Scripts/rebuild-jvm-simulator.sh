#!/usr/bin/env bash
#
# Rebuilds only the iOS *Simulator* slice of the OpenJDK Mobile Zero runtime, with MAP_JIT
# enabled so the VM can reserve executable memory. See patches/simulator-map-jit.patch for why
# the stock runtime cannot start on the Simulator.
#
# Deliberately narrow. The full tachiyomiazios build produces a macOS JDK image, a device VM and
# a simulator VM, and takes hours; nothing here changes the device slice or either java_bundle,
# so this rebuilds the one library that actually differs and re-wraps the xcframework around it.
#
# The source tree is kept at ~/.tachiyomiaz-jdk-src rather than a temp directory, because the
# upstream script deletes its build root on exit and a failed run therefore costs a full re-clone.
# Re-running this is cheap once the tree exists.
#
#   ./Scripts/rebuild-jvm-simulator.sh
#
# Requires JDK 24 as the boot JDK (no packager ships it; use Adoptium), autoconf, and a runnable
# Metal toolchain -- `xcodebuild -downloadComponent MetalToolchain`.

set -euo pipefail

mobile_revision="ad8bb1b065bf230d193a1dfd0ebd39a8b1fedf53"
builder_revision="0a753240e2b143137e73593c48d646a4956c2351"
symbol_keeper_sha256="ec02a950b2c630b234aa393abdf48efe7ce95c3a637c189e0a2e492e8ec316db"
simulator_libffi_sha256="701b522e3eff0263f18d4a9e487f0a7ac30050fb2af20e86b6849daa14f5781f"
deployment_target="15.0"
configuration="iossim-aarch64-zero-release"
jobs="${JVM_BUILD_JOBS:-4}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ios_app_root="$(cd "$script_dir/.." && pwd)"
vendor="$ios_app_root/Vendor/jvm"
work="${TACHIAZ_JDK_SRC:-$HOME/.tachiyomiaz-jdk-src}"
mobile_root="$work/mobile"
support="$work/simulator-support"

for executable in git curl unzip make libtool ar xcrun xcodebuild autoconf java shasum ditto; do
    command -v "$executable" >/dev/null || { echo "$executable is required." >&2; exit 1; }
done

java_feature="$(java -version 2>&1 | awk -F '[\".]' '/version/ { print $2; exit }')"
if [[ "$java_feature" != "24" ]]; then
    echo "OpenJDK 24 is required as the boot JDK (found ${java_feature:-none})." >&2
    echo "Set JAVA_HOME and PATH to a JDK 24, e.g. ~/.jdks/jdk-24.0.2+12/Contents/Home" >&2
    exit 1
fi

if [[ ! -d "$vendor/OpenJDK.xcframework" ]]; then
    echo "Missing $vendor/OpenJDK.xcframework -- run the full tachiyomiazios build first." >&2
    exit 1
fi

mkdir -p "$work" "$support"

# ---------------------------------------------------------------- source tree
if [[ ! -d "$mobile_root/.git" ]]; then
    echo "==> Cloning openjdk/mobile at $mobile_revision (once; reused afterwards)"
    mkdir -p "$mobile_root"
    git -C "$mobile_root" init -q
    git -C "$mobile_root" remote add origin https://github.com/openjdk/mobile.git
    git -C "$mobile_root" fetch -q --depth=1 origin "$mobile_revision"
    git -C "$mobile_root" checkout -q --detach FETCH_HEAD
fi

echo "==> Resetting source tree to the pinned revision"
git -C "$mobile_root" checkout -q -- .
git -C "$mobile_root" clean -qfd -e build

echo "==> Applying the iOS runtime patch"
git -C "$mobile_root" apply "$script_dir/patches/openjdk-mobile-ios-runtime.patch"

echo "==> Enabling MAP_JIT for the Simulator"
python3 - "$mobile_root/src/hotspot/os/bsd/os_bsd.cpp" <<'PY'
import sys
path = sys.argv[1]
source = open(path).read()

original = """  const int flags = MAP_PRIVATE | MAP_NORESERVE | MAP_ANONYMOUS
#ifdef __IOS__
      ;
#else
      MACOS_ONLY(| (exec ? MAP_JIT : 0));
#endif"""

replacement = """  const int flags = MAP_PRIVATE | MAP_NORESERVE | MAP_ANONYMOUS
#if defined(__IOS__) && defined(TARGET_OS_SIMULATOR) && TARGET_OS_SIMULATOR
      // The Simulator is an arm64 macOS process, and Apple Silicon macOS refuses an executable
      // mapping that did not ask for MAP_JIT. A device has no MAP_JIT and does not need one.
      | (exec ? MAP_JIT : 0);
#elif defined(__IOS__)
      ;
#else
      MACOS_ONLY(| (exec ? MAP_JIT : 0));
#endif"""

if original not in source:
    if "TARGET_OS_SIMULATOR" in source:
        print("    already patched")
        raise SystemExit(0)
    raise SystemExit("anon_mmap flag block not found -- the pinned revision moved, re-check the patch")

source = source.replace(original, replacement)

# TARGET_OS_SIMULATOR comes from TargetConditionals.h, which this file does not include directly.
anchor = "#include <sys/mman.h>"
if anchor in source and "TargetConditionals.h" not in source:
    source = source.replace(
        anchor,
        "#include <sys/mman.h>\n#ifdef __APPLE__\n#include <TargetConditionals.h>\n#endif",
        1,
    )
elif "TargetConditionals.h" not in source:
    lines = source.split("\n")
    for index, line in enumerate(lines):
        if line.startswith("#include"):
            lines.insert(index, "#ifdef __APPLE__\n#include <TargetConditionals.h>\n#endif")
            break
    source = "\n".join(lines)

open(path, "w").write(source)
print("    patched anon_mmap")
PY

echo "==> Enabling W^X toggling for the Simulator"
python3 - "$mobile_root/src/hotspot/os_cpu/bsd_zero/os_bsd_zero.cpp" <<'WXPY'
import sys
path = sys.argv[1]
source = open(path).read()

original = """void os::current_thread_enable_wx(WXMode mode) {
#ifndef __IOS__
  pthread_jit_write_protect_np(mode == WXExec);
#endif
}"""

replacement = """void os::current_thread_enable_wx(WXMode mode) {
// MAP_JIT memory on Apple Silicon is W^X: a thread must unlock it before writing and relock it
// before executing. iOS has neither MAP_JIT nor this call, so upstream compiles the body out --
// but the Simulator is a macOS process and needs it, or writes into the code cache SIGBUS.
#if defined(__IOS__) && defined(TARGET_OS_SIMULATOR) && TARGET_OS_SIMULATOR
  // The iPhoneSimulator SDK marks pthread_jit_write_protect_np unavailable for iOS, so it cannot
  // be called directly even though the Simulator's libSystem exports it. Resolving it at runtime
  // sidesteps the availability annotation, and leaves this a no-op if it is ever truly absent.
  typedef void (*tachiaz_jit_write_protect_fn)(int);
  static tachiaz_jit_write_protect_fn tachiaz_jit_write_protect =
      (tachiaz_jit_write_protect_fn)::dlsym(RTLD_DEFAULT, "pthread_jit_write_protect_np");
  if (tachiaz_jit_write_protect != nullptr) {
    tachiaz_jit_write_protect(mode == WXExec);
  }
#elif !defined(__IOS__)
  pthread_jit_write_protect_np(mode == WXExec);
#endif
}"""

if original not in source:
    if "TARGET_OS_SIMULATOR" in source:
        print("    already patched")
        raise SystemExit(0)
    raise SystemExit("current_thread_enable_wx not found -- the pinned revision moved")

source = source.replace(original, replacement)

if "TargetConditionals.h" not in source:
    lines = source.split(chr(10))
    for index, line in enumerate(lines):
        if line.startswith("#include"):
            lines.insert(index, "#ifdef __APPLE__" + chr(10) + "#include <TargetConditionals.h>" + chr(10) + "#include <dlfcn.h>" + chr(10) + "#endif")
            break
    source = chr(10).join(lines)

open(path, "w").write(source)
print("    patched current_thread_enable_wx")
WXPY

# ------------------------------------------------------------- support pieces
# Cached pristine, then copied before use: add-openjdk-static-lookup.sh rewrites the file in
# place, so verifying the cached copy directly would fail on every run after the first.
symbol_keeper_pristine="$work/symbol_keeper.orig.cpp"
if [[ ! -f "$symbol_keeper_pristine" ]]; then
    curl -fsSL -o "$symbol_keeper_pristine" \
        "https://raw.githubusercontent.com/openjdk-mobile/ios-tools/$builder_revision/openjdk-ext/src/hotspot/symbol_keeper.cpp"
fi
actual="$(shasum -a 256 "$symbol_keeper_pristine" | awk '{print $1}')"
[[ "$actual" == "$symbol_keeper_sha256" ]] || { echo "symbol_keeper.cpp checksum mismatch" >&2; exit 1; }

symbol_keeper="$work/symbol_keeper.cpp"
cp "$symbol_keeper_pristine" "$symbol_keeper"

libffi_zip="$work/libffi-ios-sim.zip"
if [[ ! -f "$libffi_zip" ]]; then
    curl -fsSL -o "$libffi_zip" \
        "https://github.com/openjdk-mobile/ios-tools/releases/download/libffi-build/libffi-ios-sim.zip"
fi
actual="$(shasum -a 256 "$libffi_zip" | awk '{print $1}')"
[[ "$actual" == "$simulator_libffi_sha256" ]] || { echo "libffi checksum mismatch" >&2; exit 1; }

rm -rf "$support"; mkdir -p "$support"
unzip -q "$libffi_zip" -d "$support"

# The published simulator libffi is universal; this build is arm64 only, and libtool would
# otherwise emit an x86_64 slice holding libffi and none of the VM.
libffi_arm64="$work/libffi-simulator-arm64.a"
xcrun lipo -thin arm64 "$support/libffi.a" -output "$libffi_arm64"

bash "$script_dir/add-openjdk-static-lookup.sh" "$symbol_keeper" "$script_dir/OpenJDK.exports"
cp "$symbol_keeper" "$mobile_root/src/hotspot/os/bsd/symbol_keeper.cpp"

# -------------------------------------------------------------------- build
simulator_sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
macos_sdk="$(xcrun --sdk macosx --show-sdk-path)"

echo "==> Configuring $configuration"
(
    cd "$mobile_root"
    bash configure \
        --with-jobs="$jobs" \
        --with-conf-name="$configuration" \
        --disable-warnings-as-errors \
        --openjdk-target=aarch64-macos-ios \
        --with-sysroot="$simulator_sdk" \
        --with-libffi-include="$support/include/ffi" \
        --with-libffi-lib="$support" \
        --with-extra-cflags="-target arm64-apple-ios${deployment_target}-simulator -mios-simulator-version-min=$deployment_target" \
        --with-extra-cxxflags="-target arm64-apple-ios${deployment_target}-simulator -mios-simulator-version-min=$deployment_target" \
        --with-extra-ldflags="-target arm64-apple-ios${deployment_target}-simulator -mios-simulator-version-min=$deployment_target" \
        --with-cups-include="$macos_sdk/usr/include"

    echo "==> Building static libraries"
    make LOG=info CONF="$configuration" static-libs-image
)

# ------------------------------------------------------------- repackage
static_root="$mobile_root/build/$configuration/images/static-libs/lib"
simulator_library="$work/libsim.a"
rm -f "$simulator_library"
libtool -static -o "$simulator_library" \
    "$static_root/zero/libjvm.a" \
    "$libffi_arm64" \
    "$static_root/libverify.a" \
    "$static_root/libjava.a" \
    "$static_root/libzip.a" \
    "$static_root/libnet.a" \
    "$static_root/libnio.a" \
    "$static_root/libjimage.a"

simulator_headers="$work/simulator-headers"
rm -rf "$simulator_headers"; mkdir -p "$simulator_headers"
cp -R "$mobile_root/build/$configuration/jdk/include/." "$simulator_headers/"
[[ -d "$simulator_headers/ios" ]] && cp -R "$simulator_headers/ios/." "$simulator_headers/"

# Reuse the existing device slice untouched -- nothing here rebuilds or affects it.
device_slice="$vendor/OpenJDK.xcframework/ios-arm64"
device_library="$device_slice/$(ls "$device_slice" | grep '\.a$' | head -1)"
device_headers="$device_slice/Headers"

framework="$work/OpenJDK.xcframework"
rm -rf "$framework"
xcodebuild -create-xcframework \
    -library "$device_library" -headers "$device_headers" \
    -library "$simulator_library" -headers "$simulator_headers" \
    -output "$framework"

rm -rf "$vendor/OpenJDK.xcframework"
ditto "$framework" "$vendor/OpenJDK.xcframework"

echo
echo "Rebuilt the Simulator slice with MAP_JIT enabled."
echo "  $vendor/OpenJDK.xcframework"
ls "$vendor/OpenJDK.xcframework"
