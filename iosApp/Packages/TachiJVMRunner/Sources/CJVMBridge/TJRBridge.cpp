#include "TJRBridge.h"
#include "MobileNativeBridge.h"
#include "jni.h"

#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <new>
#include <string>
#include <vector>
#include <zlib.h>

#if defined(__APPLE__)
#include <TargetConditionals.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <os/log.h>
#include <unistd.h>
#endif

#if defined(__APPLE__) && TARGET_OS_IPHONE
extern "C" void loadfunctions();
extern "C" void JDK_Canonicalize();
extern "C" void JIMAGE_Open();
extern "C" void JIMAGE_Close();
extern "C" void JIMAGE_FindResource();
extern "C" void JIMAGE_GetResource();
extern "C" void ZIP_Open();
extern "C" void ZIP_Close();
extern "C" void ZIP_FindEntry();
extern "C" void ZIP_ReadEntry();
extern "C" void ZIP_FreeEntry();
extern "C" void ZIP_CRC32();
extern "C" void ZIP_GZip_InitParams();
extern "C" void ZIP_GZip_Fully();
extern "C" void JNU_NewStringPlatform();
extern "C" void GetStringPlatformChars();
extern "C" void JNI_OnLoad_java();
extern "C" void JNI_OnLoad_jimage();
extern "C" void JNI_OnLoad_zip();
extern "C" void JNI_OnLoad_net();
extern "C" void JNI_OnLoad_nio();
extern "C" void* tachiyomiaz_lookup_static_symbol(const char* name);

// Referencing the symbol-keeper entry point makes the static linker include
// symbol_keeper.o and, through its relocations, all class-library JNI natives.
// The function body only prints the retained addresses; executing it performs
// no registration and needlessly runs hundreds of stdio calls on the UI thread.
static void retain_ios_jvm_symbols() {
    using StaticSymbol = void (*)();
    StaticSymbol volatile anchors[] = {
        &loadfunctions,
        &JDK_Canonicalize,
        &JIMAGE_Open,
        &JIMAGE_Close,
        &JIMAGE_FindResource,
        &JIMAGE_GetResource,
        &ZIP_Open,
        &ZIP_Close,
        &ZIP_FindEntry,
        &ZIP_ReadEntry,
        &ZIP_FreeEntry,
        &ZIP_CRC32,
        &ZIP_GZip_InitParams,
        &ZIP_GZip_Fully,
        &JNU_NewStringPlatform,
        &GetStringPlatformChars,
        &JNI_OnLoad_java,
        &JNI_OnLoad_jimage,
        &JNI_OnLoad_zip,
        &JNI_OnLoad_net,
        &JNI_OnLoad_nio,
    };
    for (StaticSymbol anchor : anchors) {
        (void)anchor;
    }
}

static bool validate_ios_static_symbol_lookup(std::string &error) {
    const char *required_symbols[] = {
        "JNI_CreateJavaVM",
        "JDK_Canonicalize",
        "JIMAGE_Open",
        "JIMAGE_Close",
        "JIMAGE_FindResource",
        "JIMAGE_GetResource",
        "VerifyClassForMajorVersion",
        "ZIP_Open",
        "ZIP_Close",
        "ZIP_FindEntry",
        "ZIP_ReadEntry",
        "ZIP_FreeEntry",
        "ZIP_CRC32",
        "JNU_NewStringPlatform",
        "GetStringPlatformChars",
        "Java_java_lang_System_registerNatives",
        "Java_jdk_internal_loader_NativeLibraries_findBuiltinLib",
    };
    std::vector<std::string> missing_symbols;
    for (const char *symbol : required_symbols) {
        if (tachiyomiaz_lookup_static_symbol(symbol) == nullptr) {
            missing_symbols.emplace_back(symbol);
        }
    }
    if (missing_symbols.empty()) {
        return true;
    }

    error = "Static JVM lookup is missing:";
    for (const std::string &symbol : missing_symbols) {
        error += " ";
        error += symbol;
    }
    return false;
}
#endif

struct TJRRuntime {
    JavaVM *vm;
    std::vector<void *> library_handles;
};

namespace {

#if defined(__APPLE__) && TARGET_OS_IPHONE
int ios_jvm_output_fd = -1;
int ios_jvm_saved_stdout = -1;
int ios_jvm_saved_stderr = -1;

void log_ios_jvm_output(const char *reason) {
    if (reason != nullptr) {
        os_log_with_type(
            OS_LOG_DEFAULT,
            OS_LOG_TYPE_FAULT,
            "[TachiJVMRunner/JVM] %{public}s",
            reason
        );
    }
    if (ios_jvm_output_fd < 0) {
        os_log_with_type(
            OS_LOG_DEFAULT,
            OS_LOG_TYPE_FAULT,
            "[TachiJVMRunner/JVM] JVM output capture was unavailable"
        );
        return;
    }

    std::fflush(stdout);
    std::fflush(stderr);
    fsync(ios_jvm_output_fd);

    char buffer[768];
    off_t offset = 0;
    bool found_output = false;
    while (true) {
        const ssize_t count = pread(
            ios_jvm_output_fd,
            buffer,
            sizeof(buffer) - 1,
            offset
        );
        if (count <= 0) {
            break;
        }
        found_output = true;
        buffer[count] = '\0';
        os_log_with_type(
            OS_LOG_DEFAULT,
            OS_LOG_TYPE_FAULT,
            "[TachiJVMRunner/JVM] %{public}s",
            buffer
        );
        offset += count;
    }
    if (!found_output) {
        os_log_with_type(
            OS_LOG_DEFAULT,
            OS_LOG_TYPE_FAULT,
            "[TachiJVMRunner/JVM] HotSpot exited without captured output"
        );
    }
}

bool start_ios_jvm_output_capture() {
    const char *temporary_directory = std::getenv("TMPDIR");
    if (temporary_directory == nullptr || temporary_directory[0] == '\0') {
        temporary_directory = "/tmp";
    }

    char output_path[PATH_MAX];
    const int path_length = std::snprintf(
        output_path,
        sizeof(output_path),
        "%s/%s",
        temporary_directory,
        "tachiyomiaz-jvm-startup.log"
    );
    if (path_length <= 0 || static_cast<size_t>(path_length) >= sizeof(output_path)) {
        return false;
    }

    ios_jvm_output_fd = open(
        output_path,
        O_CREAT | O_TRUNC | O_RDWR,
        S_IRUSR | S_IWUSR
    );
    if (ios_jvm_output_fd < 0) {
        return false;
    }
    // If launchd supplied closed stdio descriptors, open() may reuse 1 or 2.
    // Move the capture file out of the stdio range before recording which
    // original descriptors exist, otherwise dup() would save the log file as
    // though it were the app's original stdout/stderr.
    if (ios_jvm_output_fd <= STDERR_FILENO) {
        const int relocated_fd = fcntl(
            ios_jvm_output_fd,
            F_DUPFD_CLOEXEC,
            STDERR_FILENO + 1
        );
        close(ios_jvm_output_fd);
        ios_jvm_output_fd = relocated_fd;
        if (ios_jvm_output_fd < 0) {
            return false;
        }
    }

    ios_jvm_saved_stdout = dup(STDOUT_FILENO);
    ios_jvm_saved_stderr = dup(STDERR_FILENO);
    // Distribution builds may launch with stdout and stderr already closed.
    // That is not a capture failure: dup2 below can attach the log file to
    // those descriptor numbers. A negative saved descriptor simply means it
    // must be closed, rather than restored, when initialization completes.
    if (dup2(ios_jvm_output_fd, STDOUT_FILENO) < 0) {
        if (ios_jvm_saved_stdout >= 0) close(ios_jvm_saved_stdout);
        if (ios_jvm_saved_stderr >= 0) close(ios_jvm_saved_stderr);
        close(ios_jvm_output_fd);
        ios_jvm_saved_stdout = -1;
        ios_jvm_saved_stderr = -1;
        ios_jvm_output_fd = -1;
        return false;
    }
    if (dup2(ios_jvm_output_fd, STDERR_FILENO) < 0) {
        if (ios_jvm_saved_stdout >= 0) {
            dup2(ios_jvm_saved_stdout, STDOUT_FILENO);
            close(ios_jvm_saved_stdout);
        } else {
            close(STDOUT_FILENO);
        }
        if (ios_jvm_saved_stderr >= 0) close(ios_jvm_saved_stderr);
        close(ios_jvm_output_fd);
        ios_jvm_saved_stdout = -1;
        ios_jvm_saved_stderr = -1;
        ios_jvm_output_fd = -1;
        return false;
    }

    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    return true;
}

void stop_ios_jvm_output_capture(bool report_output) {
    std::fflush(stdout);
    std::fflush(stderr);
    if (ios_jvm_saved_stdout >= 0) {
        dup2(ios_jvm_saved_stdout, STDOUT_FILENO);
        close(ios_jvm_saved_stdout);
        ios_jvm_saved_stdout = -1;
    } else {
        close(STDOUT_FILENO);
    }
    if (ios_jvm_saved_stderr >= 0) {
        dup2(ios_jvm_saved_stderr, STDERR_FILENO);
        close(ios_jvm_saved_stderr);
        ios_jvm_saved_stderr = -1;
    } else {
        close(STDERR_FILENO);
    }
    if (report_output) {
        log_ios_jvm_output("JNI_CreateJavaVM returned after emitting output");
    }
    if (ios_jvm_output_fd >= 0) {
        close(ios_jvm_output_fd);
        ios_jvm_output_fd = -1;
    }
}

jint JNICALL ios_jvm_vfprintf_hook(
    FILE *stream,
    const char *format,
    va_list arguments
) {
    char buffer[1024];
    va_list copy;
    va_copy(copy, arguments);
    const int formatted_length = std::vsnprintf(
        buffer,
        sizeof(buffer),
        format,
        copy
    );
    va_end(copy);
    if (formatted_length > 0) {
        os_log_with_type(
            OS_LOG_DEFAULT,
            OS_LOG_TYPE_FAULT,
            "[TachiJVMRunner/JVM] %{public}s",
            buffer
        );
    }

    FILE *destination = stream != nullptr ? stream : stderr;
    const int result = std::vfprintf(destination, format, arguments);
    std::fflush(destination);
    return static_cast<jint>(result);
}

void JNICALL ios_jvm_exit_hook(jint code) {
    char reason[96];
    std::snprintf(
        reason,
        sizeof(reason),
        "HotSpot requested process exit with code %d",
        static_cast<int>(code)
    );
    log_ios_jvm_output(reason);
}

void JNICALL ios_jvm_abort_hook() {
    log_ios_jvm_output("HotSpot aborted during JVM initialization");
}
#endif

char *copy_string(const std::string &value) {
    char *result = static_cast<char *>(std::malloc(value.size() + 1));
    if (result == nullptr) {
        return nullptr;
    }
    std::memcpy(result, value.c_str(), value.size() + 1);
    return result;
}

void set_error(char **destination, const std::string &message) {
    if (destination != nullptr) {
        *destination = copy_string(message);
    }
}

#if defined(__APPLE__)
void *load_framework(
    const std::string &directory,
    const std::string &name,
    std::string &error
) {
    const std::string path = directory + "/" + name + ".framework/" + name;
    void *handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        const char *loader_error = dlerror();
        error = "Unable to load " + path;
        if (loader_error != nullptr) {
            error += ": ";
            error += loader_error;
        }
    }
    return handle;
}
#endif

std::string java_exception_message(JNIEnv *environment) {
    jthrowable exception = environment->ExceptionOccurred();
    if (exception == nullptr) {
        return "Java raised an unknown exception";
    }
    environment->ExceptionClear();

    std::string message = "Java exception";
    jclass throwable_class = environment->FindClass("java/lang/Throwable");
    if (throwable_class == nullptr) {
        environment->ExceptionClear();
        return message;
    }

    jmethodID to_string = environment->GetMethodID(
        throwable_class,
        "toString",
        "()Ljava/lang/String;"
    );
    if (to_string == nullptr) {
        environment->ExceptionClear();
        environment->DeleteLocalRef(throwable_class);
        return message;
    }

    auto text = static_cast<jstring>(
        environment->CallObjectMethod(exception, to_string)
    );
    if (environment->ExceptionCheck()) {
        environment->ExceptionClear();
    } else if (text != nullptr) {
        const char *utf8 = environment->GetStringUTFChars(text, nullptr);
        if (utf8 != nullptr) {
            message = utf8;
            environment->ReleaseStringUTFChars(text, utf8);
        }
        environment->DeleteLocalRef(text);
    }

    environment->DeleteLocalRef(throwable_class);
    environment->DeleteLocalRef(exception);
    return message;
}

jstring new_utf8_string(
    JNIEnv *environment,
    const char *value,
    std::string &error
) {
    const size_t length = std::strlen(value);
    if (length > static_cast<size_t>(INT32_MAX)) {
        error = "The Java request is too large";
        return nullptr;
    }

    jbyteArray bytes = environment->NewByteArray(static_cast<jsize>(length));
    if (bytes == nullptr) {
        error = "Unable to allocate Java request bytes";
        return nullptr;
    }
    environment->SetByteArrayRegion(
        bytes,
        0,
        static_cast<jsize>(length),
        reinterpret_cast<const jbyte *>(value)
    );
    if (environment->ExceptionCheck()) {
        environment->DeleteLocalRef(bytes);
        error = java_exception_message(environment);
        return nullptr;
    }

    jclass string_class = environment->FindClass("java/lang/String");
    if (string_class == nullptr) {
        environment->DeleteLocalRef(bytes);
        error = java_exception_message(environment);
        return nullptr;
    }
    jmethodID constructor = environment->GetMethodID(
        string_class,
        "<init>",
        "([BLjava/lang/String;)V"
    );
    jstring charset = environment->NewStringUTF("UTF-8");
    if (constructor == nullptr || charset == nullptr) {
        environment->DeleteLocalRef(string_class);
        environment->DeleteLocalRef(bytes);
        if (charset != nullptr) {
            environment->DeleteLocalRef(charset);
        }
        error = environment->ExceptionCheck()
            ? java_exception_message(environment)
            : "Unable to resolve the Java UTF-8 string constructor";
        return nullptr;
    }

    auto result = static_cast<jstring>(
        environment->NewObject(string_class, constructor, bytes, charset)
    );
    environment->DeleteLocalRef(charset);
    environment->DeleteLocalRef(string_class);
    environment->DeleteLocalRef(bytes);
    if (result == nullptr || environment->ExceptionCheck()) {
        error = environment->ExceptionCheck()
            ? java_exception_message(environment)
            : "Unable to create the Java request string";
        return nullptr;
    }
    return result;
}

bool string_to_utf8(
    JNIEnv *environment,
    jstring value,
    std::string &result,
    std::string &error
) {
    jclass string_class = environment->FindClass("java/lang/String");
    if (string_class == nullptr) {
        error = java_exception_message(environment);
        return false;
    }
    jmethodID get_bytes = environment->GetMethodID(
        string_class,
        "getBytes",
        "(Ljava/lang/String;)[B"
    );
    jstring charset = environment->NewStringUTF("UTF-8");
    if (get_bytes == nullptr || charset == nullptr) {
        environment->DeleteLocalRef(string_class);
        if (charset != nullptr) {
            environment->DeleteLocalRef(charset);
        }
        error = environment->ExceptionCheck()
            ? java_exception_message(environment)
            : "Unable to resolve Java UTF-8 encoding";
        return false;
    }

    auto bytes = static_cast<jbyteArray>(
        environment->CallObjectMethod(value, get_bytes, charset)
    );
    environment->DeleteLocalRef(charset);
    environment->DeleteLocalRef(string_class);
    if (bytes == nullptr || environment->ExceptionCheck()) {
        error = environment->ExceptionCheck()
            ? java_exception_message(environment)
            : "Unable to encode the Java response";
        return false;
    }

    const jsize length = environment->GetArrayLength(bytes);
    result.resize(static_cast<size_t>(length));
    if (length > 0) {
        environment->GetByteArrayRegion(
            bytes,
            0,
            length,
            reinterpret_cast<jbyte *>(&result[0])
        );
    }
    environment->DeleteLocalRef(bytes);
    if (environment->ExceptionCheck()) {
        error = java_exception_message(environment);
        return false;
    }
    return true;
}

TJRStatus acquire_environment(
    TJRRuntime *runtime,
    JNIEnv **environment,
    bool *attached,
    char **error_message
) {
    *attached = false;
    const jint get_result = runtime->vm->GetEnv(
        reinterpret_cast<void **>(environment),
        JNI_VERSION_1_8
    );
    if (get_result == JNI_OK) {
        return TJRStatusOK;
    }
    if (get_result != JNI_EDETACHED) {
        set_error(error_message, "Unable to obtain a JNI environment");
        return TJRStatusThreadAttachFailed;
    }

    const jint attach_result = runtime->vm->AttachCurrentThread(
        reinterpret_cast<void **>(environment),
        nullptr
    );
    if (attach_result != JNI_OK) {
        set_error(error_message, "Unable to attach the current thread to Java");
        return TJRStatusThreadAttachFailed;
    }
    *attached = true;
    return TJRStatusOK;
}

} // namespace

TJRRuntime *tjr_runtime_create(
    const char *java_home,
    const char *frameworks_directory,
    const char *classpath,
    const char *const *additional_options,
    int additional_option_count,
    char **error_message
) {
    if (error_message != nullptr) {
        *error_message = nullptr;
    }
    if (
        java_home == nullptr ||
        frameworks_directory == nullptr ||
        classpath == nullptr ||
        additional_option_count < 0
    ) {
        set_error(error_message, "Invalid JVM runtime configuration");
        return nullptr;
    }

#if !defined(__APPLE__)
    (void)additional_options;
    set_error(
        error_message,
        "The embedded JVM bridge currently supports Apple platforms only"
    );
    return nullptr;
#else
    auto *runtime = new (std::nothrow) TJRRuntime();
    if (runtime == nullptr) {
        set_error(error_message, "Unable to allocate JVM runtime");
        return nullptr;
    }
    runtime->vm = nullptr;

    setenv("JAVA_HOME", java_home, 1);

    using CreateJavaVM = jint (*)(JavaVM **, void **, void *);
#if TARGET_OS_IPHONE
    os_log_with_type(
        OS_LOG_DEFAULT,
        OS_LOG_TYPE_ERROR,
        "[TachiJVMRunner] retaining static JNI symbols"
    );
    retain_ios_jvm_symbols();
    os_log_with_type(
        OS_LOG_DEFAULT,
        OS_LOG_TYPE_ERROR,
        "[TachiJVMRunner] static JNI symbols retained"
    );
    std::string static_lookup_error;
    if (!validate_ios_static_symbol_lookup(static_lookup_error)) {
        os_log_with_type(
            OS_LOG_DEFAULT,
            OS_LOG_TYPE_FAULT,
            "[TachiJVMRunner] %{public}s",
            static_lookup_error.c_str()
        );
        set_error(error_message, static_lookup_error);
        delete runtime;
        return nullptr;
    }
    os_log_with_type(
        OS_LOG_DEFAULT,
        OS_LOG_TYPE_ERROR,
        "[TachiJVMRunner] direct static JVM lookup validated"
    );
    CreateJavaVM create_vm = &JNI_CreateJavaVM;
#else
    const char *required_libraries[] = {
        "libffi.8",
        "libjvm",
        "libverify",
        "libjava",
        "libnet",
    };
    void *jvm_handle = nullptr;
    for (const char *library : required_libraries) {
        std::string loader_error;
        void *handle = load_framework(
            frameworks_directory,
            library,
            loader_error
        );
        if (handle == nullptr) {
            set_error(error_message, loader_error);
            for (void *loaded_handle : runtime->library_handles) {
                dlclose(loaded_handle);
            }
            delete runtime;
            return nullptr;
        }
        runtime->library_handles.push_back(handle);
        if (std::strcmp(library, "libjvm") == 0) {
            jvm_handle = handle;
        }
    }
    auto create_vm = reinterpret_cast<CreateJavaVM>(
        dlsym(jvm_handle, "JNI_CreateJavaVM")
    );
    if (create_vm == nullptr) {
        set_error(error_message, "libjvm does not export JNI_CreateJavaVM");
        for (void *loaded_handle : runtime->library_handles) {
            dlclose(loaded_handle);
        }
        delete runtime;
        return nullptr;
    }
#endif

    std::vector<std::string> option_values = {
        std::string("-Djava.home=") + java_home,
        std::string("-Djava.class.path=") + classpath,
        std::string("-Djava.library.path=") + frameworks_directory,
        "-Djava.awt.headless=true",
        "-Xrs",
        "-Xms16m",
        "-Xmx128m",
        "-Xss512k",
#if TARGET_OS_IPHONE
        // Desktop HotSpot reserves a 1 GiB compressed class space by
        // default. iOS refuses a single reservation that large even though
        // the pages would only be committed on demand. JDK 26 treats
        // UseCompressedClassPointers as obsolete, so size the reservation
        // explicitly instead of attempting to disable compressed pointers.
        "-XX:CompressedClassSpaceSize=64m",
        "-XX:InitialCodeCacheSize=4m",
        "-XX:ReservedCodeCacheSize=32m",
        "-XX:+DisplayVMOutputToStderr",
#endif
    };
    for (int index = 0; index < additional_option_count; ++index) {
        if (
            additional_options != nullptr &&
            additional_options[index] != nullptr
        ) {
            option_values.emplace_back(additional_options[index]);
        }
    }

    size_t special_option_count = 0;
#if TARGET_OS_IPHONE
    special_option_count = 3;
#endif
    std::vector<JavaVMOption> options(
        option_values.size() + special_option_count
    );
    size_t option_index = 0;
#if TARGET_OS_IPHONE
    options[option_index++] = {
        const_cast<char *>("vfprintf"),
        reinterpret_cast<void *>(&ios_jvm_vfprintf_hook),
    };
    options[option_index++] = {
        const_cast<char *>("exit"),
        reinterpret_cast<void *>(&ios_jvm_exit_hook),
    };
    options[option_index++] = {
        const_cast<char *>("abort"),
        reinterpret_cast<void *>(&ios_jvm_abort_hook),
    };
#endif
    for (size_t index = 0; index < option_values.size(); ++index) {
        options[option_index].optionString =
            const_cast<char *>(option_values[index].c_str());
        options[option_index].extraInfo = nullptr;
        ++option_index;
    }

    JavaVMInitArgs arguments;
    arguments.version = JNI_VERSION_1_8;
    arguments.nOptions = static_cast<jint>(options.size());
    arguments.options = options.data();
    arguments.ignoreUnrecognized = JNI_TRUE;

    JNIEnv *environment = nullptr;
#if TARGET_OS_IPHONE
    const bool capturing_jvm_output = start_ios_jvm_output_capture();
    if (!capturing_jvm_output) {
        os_log_with_type(
            OS_LOG_DEFAULT,
            OS_LOG_TYPE_FAULT,
            "[TachiJVMRunner] could not capture JVM startup output"
        );
    }
    os_log_with_type(
        OS_LOG_DEFAULT,
        OS_LOG_TYPE_ERROR,
        "[TachiJVMRunner] entering JNI_CreateJavaVM"
    );
#else
    std::fprintf(stderr, "[TachiJVMRunner] creating Java VM\n");
    std::fflush(stderr);
#endif
    const jint result = create_vm(
        &runtime->vm,
        reinterpret_cast<void **>(&environment),
        &arguments
    );
#if TARGET_OS_IPHONE
    stop_ios_jvm_output_capture(result != JNI_OK);
    os_log_with_type(
        OS_LOG_DEFAULT,
        OS_LOG_TYPE_ERROR,
        "[TachiJVMRunner] JNI_CreateJavaVM returned %{public}d",
        static_cast<int>(result)
    );
#else
    std::fprintf(
        stderr,
        "[TachiJVMRunner] Java VM creation returned %d\n",
        static_cast<int>(result)
    );
    std::fflush(stderr);
#endif
    if (
        result != JNI_OK ||
        runtime->vm == nullptr ||
        environment == nullptr
    ) {
        set_error(
            error_message,
            "JNI_CreateJavaVM failed with code " + std::to_string(result)
        );
        for (void *loaded_handle : runtime->library_handles) {
            dlclose(loaded_handle);
        }
        delete runtime;
        return nullptr;
    }

    std::string mobile_native_error;
    if (!tjr_register_mobile_natives(environment, mobile_native_error)) {
        set_error(error_message, mobile_native_error);
        for (void *loaded_handle : runtime->library_handles) {
            dlclose(loaded_handle);
        }
        delete runtime;
        return nullptr;
    }

    return runtime;
#endif
}

TJRStatus tjr_runtime_dispatch(
    TJRRuntime *runtime,
    const char *request_json,
    char **response_json,
    char **error_message
) {
    if (response_json != nullptr) {
        *response_json = nullptr;
    }
    if (error_message != nullptr) {
        *error_message = nullptr;
    }
    if (
        runtime == nullptr ||
        runtime->vm == nullptr ||
        request_json == nullptr
    ) {
        set_error(error_message, "Invalid dispatch arguments");
        return TJRStatusInvalidArgument;
    }

    JNIEnv *environment = nullptr;
    bool attached = false;
    const TJRStatus acquire_status = acquire_environment(
        runtime,
        &environment,
        &attached,
        error_message
    );
    if (acquire_status != TJRStatusOK) {
        return acquire_status;
    }

    TJRStatus status = TJRStatusOK;
    jclass host_class = environment->FindClass(
        "app/tachiaz/runtime/ExtensionHost"
    );
    if (host_class == nullptr) {
        set_error(error_message, java_exception_message(environment));
        status = TJRStatusJavaClassNotFound;
        goto cleanup;
    }

    {
        jmethodID dispatch_method = environment->GetStaticMethodID(
            host_class,
            "dispatch",
            "(Ljava/lang/String;)Ljava/lang/String;"
        );
        if (dispatch_method == nullptr) {
            set_error(error_message, java_exception_message(environment));
            status = TJRStatusJavaMethodNotFound;
            goto cleanup;
        }

        std::string conversion_error;
        jstring request = new_utf8_string(
            environment,
            request_json,
            conversion_error
        );
        if (request == nullptr) {
            set_error(error_message, conversion_error);
            status = TJRStatusAllocationFailed;
            goto cleanup;
        }

        auto response = static_cast<jstring>(
            environment->CallStaticObjectMethod(
                host_class,
                dispatch_method,
                request
            )
        );
        environment->DeleteLocalRef(request);
        if (environment->ExceptionCheck()) {
            set_error(error_message, java_exception_message(environment));
            status = TJRStatusJavaException;
            goto cleanup;
        }
        if (response == nullptr) {
            set_error(error_message, "ExtensionHost returned a null response");
            status = TJRStatusJavaException;
            goto cleanup;
        }

        std::string utf8;
        std::string response_error;
        const bool converted = string_to_utf8(
            environment,
            response,
            utf8,
            response_error
        );
        environment->DeleteLocalRef(response);
        if (!converted) {
            set_error(error_message, response_error);
            status = TJRStatusAllocationFailed;
            goto cleanup;
        }
        if (response_json != nullptr) {
            *response_json = copy_string(utf8);
        }
        if (
            response_json != nullptr &&
            *response_json == nullptr
        ) {
            set_error(error_message, "Unable to allocate the native response");
            status = TJRStatusAllocationFailed;
        }
    }

cleanup:
    if (host_class != nullptr) {
        environment->DeleteLocalRef(host_class);
    }
    if (attached) {
        runtime->vm->DetachCurrentThread();
    }
    return status;
}

void tjr_runtime_release(TJRRuntime *runtime) {
    if (runtime == nullptr) {
        return;
    }
    // Deliberately keep the JVM and its frameworks loaded until process exit.
    delete runtime;
}

void tjr_string_free(char *value) {
    std::free(value);
}

TJRStatus tjr_gzip_decompress(
    const unsigned char *input,
    size_t input_size,
    unsigned char **output,
    size_t *output_size
) {
    if (input == nullptr || input_size == 0 || output == nullptr ||
        output_size == nullptr) {
        return TJRStatusInvalidArgument;
    }

    *output = nullptr;
    *output_size = 0;

    z_stream stream = {};
    stream.next_in = const_cast<Bytef *>(input);
    stream.avail_in = static_cast<uInt>(input_size);
    if (inflateInit2(&stream, MAX_WBITS + 16) != Z_OK) {
        return TJRStatusCompressionFailed;
    }

    std::vector<unsigned char> decompressed;
    unsigned char chunk[64 * 1024];
    constexpr size_t max_decompressed_size = 256 * 1024 * 1024;
    int result = Z_OK;
    while (result == Z_OK) {
        stream.next_out = chunk;
        stream.avail_out = sizeof(chunk);
        result = inflate(&stream, Z_NO_FLUSH);
        const size_t produced = sizeof(chunk) - stream.avail_out;
        if (produced > max_decompressed_size - decompressed.size()) {
            inflateEnd(&stream);
            return TJRStatusCompressionFailed;
        }
        decompressed.insert(
            decompressed.end(),
            chunk,
            chunk + produced
        );
    }
    inflateEnd(&stream);

    if (result != Z_STREAM_END) {
        return TJRStatusCompressionFailed;
    }

    auto *buffer = static_cast<unsigned char *>(
        std::malloc(decompressed.empty() ? 1 : decompressed.size())
    );
    if (buffer == nullptr) {
        return TJRStatusAllocationFailed;
    }
    if (!decompressed.empty()) {
        std::memcpy(buffer, decompressed.data(), decompressed.size());
    }
    *output = buffer;
    *output_size = decompressed.size();
    return TJRStatusOK;
}

TJRStatus tjr_gzip_compress(
    const unsigned char *input,
    size_t input_size,
    unsigned char **output,
    size_t *output_size
) {
    if (
        (input == nullptr && input_size != 0) ||
        output == nullptr ||
        output_size == nullptr
    ) {
        return TJRStatusInvalidArgument;
    }

    *output = nullptr;
    *output_size = 0;

    z_stream stream = {};
    stream.next_in = const_cast<Bytef *>(input);
    stream.avail_in = static_cast<uInt>(input_size);
    if (
        deflateInit2(
            &stream,
            Z_DEFAULT_COMPRESSION,
            Z_DEFLATED,
            MAX_WBITS + 16,
            8,
            Z_DEFAULT_STRATEGY
        ) != Z_OK
    ) {
        return TJRStatusCompressionFailed;
    }

    std::vector<unsigned char> compressed;
    unsigned char chunk[64 * 1024];
    int result = Z_OK;
    while (result == Z_OK) {
        stream.next_out = chunk;
        stream.avail_out = sizeof(chunk);
        result = deflate(&stream, Z_FINISH);
        const size_t produced = sizeof(chunk) - stream.avail_out;
        compressed.insert(compressed.end(), chunk, chunk + produced);
    }
    deflateEnd(&stream);

    if (result != Z_STREAM_END) {
        return TJRStatusCompressionFailed;
    }

    auto *buffer = static_cast<unsigned char *>(
        std::malloc(compressed.empty() ? 1 : compressed.size())
    );
    if (buffer == nullptr) {
        return TJRStatusAllocationFailed;
    }
    if (!compressed.empty()) {
        std::memcpy(buffer, compressed.data(), compressed.size());
    }
    *output = buffer;
    *output_size = compressed.size();
    return TJRStatusOK;
}

void tjr_buffer_free(unsigned char *value) {
    std::free(value);
}
