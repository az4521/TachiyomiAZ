#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// The WKWebView bridge the JNI layer calls out to for source web-login flows.
//
// MobileNativeBridge.cpp declares this weak_import and null-checks it, so the runtime is designed
// to work without one. The link still needs the symbol to resolve, and providing the sentinel the
// bridge itself would have returned is more honest than forcing the linker past it: web login
// reports itself unavailable instead of failing in some less obvious way further in.
//
// Replace this with a real WKWebView implementation when web login is ported. Nothing else in the
// runtime depends on it -- extension loading, browsing and reading do not go through here.
//
// The caller takes ownership and frees the result with free(), so this must return heap memory.
char *tachiyomiaz_webkit_command(
    const char *operation,
    int64_t handle,
    const char *argument1,
    const char *argument2
) {
    (void)operation;
    (void)handle;
    (void)argument1;
    (void)argument2;

    // The exact string the bridge uses when the symbol is absent, so callers see one behaviour.
    static const char unavailable[] = "__UNAVAILABLE__WKWebView bridge is not installed";
    return strdup(unavailable);
}
