#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <symbol-keeper.cpp> <exports-file>" >&2
    exit 64
fi

symbol_keeper="$1"
exports_file="$2"
temporary="$(mktemp "${TMPDIR:-/tmp}/tachiyomiaz-static-lookup.XXXXXX")"
symbols="$(mktemp "${TMPDIR:-/tmp}/tachiyomiaz-static-symbols.XXXXXX")"
trap 'rm -f "$temporary" "$symbols"' EXIT

if [[ ! -f "$symbol_keeper" || ! -f "$exports_file" ]]; then
    echo "The symbol keeper and export manifest are required." >&2
    exit 1
fi

sed 's/^_//' "$exports_file" |
    awk '/^[A-Za-z_][A-Za-z0-9_]*$/' |
    LC_ALL=C sort -u > "$symbols"

if [[ ! -s "$symbols" ]]; then
    echo "The OpenJDK export manifest contains no usable C symbols." >&2
    exit 1
fi
mapping_count="$(wc -l < "$symbols" | tr -d ' ')"

required_symbols=(
    JNI_CreateJavaVM
    JIMAGE_Open
    VerifyClassForMajorVersion
    ZIP_Open
    Java_java_lang_System_registerNatives
    Java_jdk_internal_loader_NativeLibraries_findBuiltinLib
)
missing_symbols=()
for symbol in "${required_symbols[@]}"; do
    if ! grep -Fx "$symbol" "$symbols" >/dev/null; then
        missing_symbols+=("$symbol")
    fi
done
if (( ${#missing_symbols[@]} > 0 )); then
    echo "The direct OpenJDK lookup manifest is missing required symbols:" >&2
    printf '  %s\n' "${missing_symbols[@]}" >&2
    exit 1
fi

{
    cat "$symbol_keeper"
    printf '\n// iOS cannot reliably resolve statically linked JVM symbols through dyld.\n'
    printf '// Keep a direct name-to-address registry inside libjvm instead.\n'
    printf '// Unique assembler aliases avoid collisions with declarations from the HotSpot PCH.\n'
    index=0
    while IFS= read -r symbol; do
        printf 'extern void* tachiyomiaz_static_address_%d asm("_%s");\n' \
            "$index" \
            "$symbol"
        ((index += 1))
    done < "$symbols"
    cat <<'EOF'

struct TachiyomiAZStaticSymbol {
    const char* name;
    void* address;
};

extern "C" int strcmp(const char*, const char*);

static const TachiyomiAZStaticSymbol tachiyomiaz_static_symbols[] = {
EOF
    index=0
    while IFS= read -r symbol; do
        printf '    { "%s", reinterpret_cast<void*>(&tachiyomiaz_static_address_%d) },\n' \
            "$symbol" \
            "$index"
        ((index += 1))
    done < "$symbols"
    cat <<'EOF'
};

extern "C" void* tachiyomiaz_lookup_static_symbol(const char* name) {
    if (name == nullptr) {
        return nullptr;
    }
    for (const TachiyomiAZStaticSymbol& symbol : tachiyomiaz_static_symbols) {
        if (strcmp(name, symbol.name) == 0) {
            return symbol.address;
        }
    }
    return nullptr;
}
EOF
} > "$temporary"

mv "$temporary" "$symbol_keeper"
trap - EXIT
rm -f "$symbols"

echo "Added $mapping_count direct OpenJDK symbol mappings"
