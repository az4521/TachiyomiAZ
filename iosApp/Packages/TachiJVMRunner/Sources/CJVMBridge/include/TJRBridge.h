#ifndef TJRBridge_h
#define TJRBridge_h

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct TJRRuntime TJRRuntime;

typedef enum TJRStatus {
    TJRStatusOK = 0,
    TJRStatusInvalidArgument = 1,
    TJRStatusUnsupportedPlatform = 2,
    TJRStatusLibraryLoadFailed = 3,
    TJRStatusSymbolLoadFailed = 4,
    TJRStatusVMCreationFailed = 5,
    TJRStatusThreadAttachFailed = 6,
    TJRStatusJavaClassNotFound = 7,
    TJRStatusJavaMethodNotFound = 8,
    TJRStatusJavaException = 9,
    TJRStatusAllocationFailed = 10,
    TJRStatusCompressionFailed = 11
} TJRStatus;

TJRRuntime *tjr_runtime_create(
    const char *java_home,
    const char *frameworks_directory,
    const char *classpath,
    const char *const *additional_options,
    int additional_option_count,
    char **error_message
);

TJRStatus tjr_runtime_dispatch(
    TJRRuntime *runtime,
    const char *request_json,
    char **response_json,
    char **error_message
);

/**
 * Releases native bookkeeping. The underlying Java VM remains alive because
 * DestroyJavaVM is unsafe for the app's long-lived, multithreaded lifecycle.
 */
void tjr_runtime_release(TJRRuntime *runtime);

void tjr_string_free(char *value);

TJRStatus tjr_gzip_decompress(
    const unsigned char *input,
    size_t input_size,
    unsigned char **output,
    size_t *output_size
);

TJRStatus tjr_gzip_compress(
    const unsigned char *input,
    size_t input_size,
    unsigned char **output,
    size_t *output_size
);

void tjr_buffer_free(unsigned char *value);

#ifdef __cplusplus
}
#endif

#endif
