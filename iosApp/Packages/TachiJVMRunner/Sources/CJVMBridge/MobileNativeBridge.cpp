#include "MobileNativeBridge.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string>
#include <vector>

#if defined(__APPLE__)
#include <CoreFoundation/CoreFoundation.h>
#include <CoreGraphics/CoreGraphics.h>
#include <CoreText/CoreText.h>
#include <ImageIO/ImageIO.h>
#include <JavaScriptCore/JavaScriptCore.h>

extern "C" char *tachiyomiaz_webkit_command(
    const char *operation,
    int64_t handle,
    const char *argument1,
    const char *argument2
) __attribute__((weak_import));
#endif

namespace {

#if defined(__APPLE__)
JavaVM *mobile_vm = nullptr;
jclass mobile_bridge_type = nullptr;
jmethodID mobile_webkit_event_method = nullptr;

struct NativeBitmap {
    size_t width;
    size_t height;
    size_t bytes_per_row;
    std::vector<uint8_t> pixels;
    CGColorSpaceRef color_space;
    CGContextRef context;

    NativeBitmap(size_t bitmap_width, size_t bitmap_height)
        : width(bitmap_width),
          height(bitmap_height),
          bytes_per_row(bitmap_width * 4),
          pixels(bytes_per_row * bitmap_height, 0),
          color_space(CGColorSpaceCreateDeviceRGB()),
          context(nullptr) {
        context = CGBitmapContextCreate(
            pixels.data(),
            width,
            height,
            8,
            bytes_per_row,
            color_space,
            kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big
        );
    }

    ~NativeBitmap() {
        if (context != nullptr) {
            CGContextRelease(context);
        }
        if (color_space != nullptr) {
            CGColorSpaceRelease(color_space);
        }
    }

    bool valid() const {
        return context != nullptr;
    }
};

NativeBitmap *bitmap(jlong handle) {
    return reinterpret_cast<NativeBitmap *>(static_cast<intptr_t>(handle));
}

jlong handle(NativeBitmap *value) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(value));
}

// CGBitmapContext's supplied memory begins with the encoded image's top row.
// Quartz drawing coordinates still use a bottom-left origin, but raw Android
// Bitmap access must address the backing rows directly. Flipping the row here
// makes getPixels/setPixels disagree with ImageIO and permutes descrambler
// tiles when the finished bitmap is encoded.
size_t pixel_offset(const NativeBitmap *value, int x, int android_y) {
    return (static_cast<size_t>(android_y) * value->bytes_per_row) +
        (static_cast<size_t>(x) * 4);
}

bool valid_bitmap_dimensions(size_t width, size_t height) {
    constexpr size_t maximum_pixel_count =
        (256ULL * 1024ULL * 1024ULL) / 4ULL;
    return width > 0 && height > 0 &&
        width <= maximum_pixel_count &&
        height <= maximum_pixel_count / width;
}

void throw_runtime(JNIEnv *environment, const char *message) {
    jclass type = environment->FindClass("java/lang/RuntimeException");
    if (type != nullptr) {
        environment->ThrowNew(type, message);
        environment->DeleteLocalRef(type);
    }
}

void throw_quickjs(JNIEnv *environment, const char *message) {
    jclass type = environment->FindClass("app/cash/quickjs/QuickJsException");
    if (type == nullptr) {
        environment->ExceptionClear();
        throw_runtime(environment, message);
        return;
    }
    environment->ThrowNew(type, message);
    environment->DeleteLocalRef(type);
}

jlongArray bitmap_result(JNIEnv *environment, NativeBitmap *value) {
    if (value == nullptr || !value->valid()) {
        delete value;
        return nullptr;
    }
    const jlong values[] = {
        handle(value),
        static_cast<jlong>(value->width),
        static_cast<jlong>(value->height),
    };
    jlongArray result = environment->NewLongArray(3);
    if (result == nullptr) {
        delete value;
        return nullptr;
    }
    environment->SetLongArrayRegion(result, 0, 3, values);
    return result;
}

CFStringRef cf_string(JNIEnv *environment, jstring value) {
    if (value == nullptr) {
        return CFStringCreateWithCString(
            kCFAllocatorDefault,
            "",
            kCFStringEncodingUTF8
        );
    }
    const jsize length = environment->GetStringLength(value);
    const jchar *characters = environment->GetStringChars(value, nullptr);
    if (characters == nullptr) {
        return nullptr;
    }
    CFStringRef result = CFStringCreateWithCharacters(
        kCFAllocatorDefault,
        reinterpret_cast<const UniChar *>(characters),
        length
    );
    environment->ReleaseStringChars(value, characters);
    return result;
}

jstring java_string(JNIEnv *environment, JSStringRef value) {
    const size_t capacity = JSStringGetMaximumUTF8CStringSize(value);
    std::vector<char> utf8(capacity);
    JSStringGetUTF8CString(value, utf8.data(), capacity);
    return environment->NewStringUTF(utf8.data());
}

CTFontRef create_font(float size, bool bold, CFStringRef font_name = nullptr) {
    CTFontRef base = CTFontCreateWithName(
        font_name == nullptr || CFStringGetLength(font_name) == 0
            ? CFSTR("Helvetica")
            : font_name,
        std::max(1.0f, size),
        nullptr
    );
    if (!bold || base == nullptr) {
        return base;
    }
    CTFontRef styled = CTFontCreateCopyWithSymbolicTraits(
        base,
        0,
        nullptr,
        kCTFontBoldTrait,
        kCTFontBoldTrait
    );
    if (styled == nullptr) {
        return base;
    }
    CFRelease(base);
    return styled;
}

CFAttributedStringRef attributed_text(
    CFStringRef text,
    float size,
    bool bold,
    int32_t color,
    int style = 0,
    float stroke_width = 0,
    CFStringRef font_name = nullptr
) {
    CTFontRef font = create_font(size, bold, font_name);
    const CGFloat alpha = static_cast<CGFloat>((color >> 24) & 0xff) / 255.0;
    const CGFloat red = static_cast<CGFloat>((color >> 16) & 0xff) / 255.0;
    const CGFloat green = static_cast<CGFloat>((color >> 8) & 0xff) / 255.0;
    const CGFloat blue = static_cast<CGFloat>(color & 0xff) / 255.0;
    CGColorSpaceRef color_space = CGColorSpaceCreateDeviceRGB();
    const CGFloat components[] = {red, green, blue, alpha};
    CGColorRef foreground = CGColorCreate(color_space, components);
    CGColorSpaceRelease(color_space);
    const void *keys[] = {
        kCTFontAttributeName,
        kCTForegroundColorAttributeName,
        kCTStrokeColorAttributeName,
        kCTStrokeWidthAttributeName,
    };
    const void *values[] = {font, foreground, foreground, nullptr};
    CFNumberRef stroke = nullptr;
    CFIndex attribute_count = 2;
    if (style == 1 || style == 2) {
        // Core Text expresses glyph stroke width as a percentage of font size.
        // Positive values stroke only; negative values fill and stroke.
        CGFloat percentage =
            (std::max(0.0f, stroke_width) / std::max(1.0f, size)) * 100.0f;
        if (style == 2) {
            percentage = -percentage;
        }
        stroke = CFNumberCreate(
            kCFAllocatorDefault,
            kCFNumberCGFloatType,
            &percentage
        );
        values[3] = stroke;
        attribute_count = 4;
    }
    CFDictionaryRef attributes = CFDictionaryCreate(
        kCFAllocatorDefault,
        keys,
        values,
        attribute_count,
        &kCFTypeDictionaryKeyCallBacks,
        &kCFTypeDictionaryValueCallBacks
    );
    CFAttributedStringRef result = CFAttributedStringCreate(
        kCFAllocatorDefault,
        text,
        attributes
    );
    CFRelease(attributes);
    if (stroke != nullptr) {
        CFRelease(stroke);
    }
    CGColorRelease(foreground);
    CFRelease(font);
    return result;
}

void write_color(uint8_t *pixel, int32_t color) {
    pixel[0] = static_cast<uint8_t>((color >> 16) & 0xff);
    pixel[1] = static_cast<uint8_t>((color >> 8) & 0xff);
    pixel[2] = static_cast<uint8_t>(color & 0xff);
    pixel[3] = static_cast<uint8_t>((color >> 24) & 0xff);
}

int32_t read_color(const uint8_t *pixel) {
    return
        (static_cast<int32_t>(pixel[3]) << 24) |
        (static_cast<int32_t>(pixel[0]) << 16) |
        (static_cast<int32_t>(pixel[1]) << 8) |
        static_cast<int32_t>(pixel[2]);
}

bool read_int_array(
    JNIEnv *environment,
    jintArray array,
    jint *values,
    jsize count
) {
    if (array == nullptr || environment->GetArrayLength(array) < count) {
        return false;
    }
    environment->GetIntArrayRegion(array, 0, count, values);
    return !environment->ExceptionCheck();
}

bool read_float_array(
    JNIEnv *environment,
    jfloatArray array,
    jfloat *values,
    jsize count
) {
    if (array == nullptr || environment->GetArrayLength(array) < count) {
        return false;
    }
    environment->GetFloatArrayRegion(array, 0, count, values);
    return !environment->ExceptionCheck();
}

jlongArray JNICALL bitmap_create(JNIEnv *environment, jclass, jint width, jint height) {
    if (
        width <= 0 || height <= 0 ||
        !valid_bitmap_dimensions(
            static_cast<size_t>(width),
            static_cast<size_t>(height)
        )
    ) {
        throw_runtime(environment, "Invalid bitmap dimensions");
        return nullptr;
    }
    try {
        return bitmap_result(environment, new NativeBitmap(width, height));
    } catch (const std::bad_alloc &) {
        throw_runtime(environment, "Unable to allocate bitmap storage");
        return nullptr;
    }
}

jlongArray JNICALL bitmap_decode(
    JNIEnv *environment,
    jclass,
    jbyteArray data,
    jint offset,
    jint length
) {
    if (data == nullptr || offset < 0 || length <= 0) {
        return nullptr;
    }
    std::vector<uint8_t> encoded(static_cast<size_t>(length));
    environment->GetByteArrayRegion(
        data,
        offset,
        length,
        reinterpret_cast<jbyte *>(encoded.data())
    );
    CFDataRef input = CFDataCreate(
        kCFAllocatorDefault,
        encoded.data(),
        encoded.size()
    );
    CGImageSourceRef source = CGImageSourceCreateWithData(input, nullptr);
    CFRelease(input);
    if (source == nullptr) {
        return nullptr;
    }
    CGImageRef image = CGImageSourceCreateImageAtIndex(source, 0, nullptr);
    CFRelease(source);
    if (image == nullptr) {
        return nullptr;
    }
    const size_t decoded_width = CGImageGetWidth(image);
    const size_t decoded_height = CGImageGetHeight(image);
    if (!valid_bitmap_dimensions(decoded_width, decoded_height)) {
        CGImageRelease(image);
        throw_runtime(environment, "Decoded bitmap dimensions are too large");
        return nullptr;
    }
    NativeBitmap *result = nullptr;
    try {
        result = new NativeBitmap(decoded_width, decoded_height);
    } catch (const std::bad_alloc &) {
        CGImageRelease(image);
        throw_runtime(environment, "Unable to allocate decoded bitmap storage");
        return nullptr;
    }
    if (result != nullptr && result->valid()) {
        CGContextDrawImage(
            result->context,
            CGRectMake(0, 0, result->width, result->height),
            image
        );
    }
    CGImageRelease(image);
    return bitmap_result(environment, result);
}

jlongArray JNICALL bitmap_crop(
    JNIEnv *environment,
    jclass,
    jlong source_handle,
    jint x,
    jint y,
    jint width,
    jint height
) {
    NativeBitmap *source = bitmap(source_handle);
    if (
        source == nullptr || width <= 0 || height <= 0 ||
        x < 0 || y < 0 ||
        static_cast<size_t>(x) + static_cast<size_t>(width) > source->width ||
        static_cast<size_t>(y) + static_cast<size_t>(height) > source->height ||
        !valid_bitmap_dimensions(
            static_cast<size_t>(width),
            static_cast<size_t>(height)
        )
    ) {
        return nullptr;
    }
    NativeBitmap *result = nullptr;
    try {
        result = new NativeBitmap(width, height);
    } catch (const std::bad_alloc &) {
        throw_runtime(environment, "Unable to allocate cropped bitmap storage");
        return nullptr;
    }
    if (result == nullptr || !result->valid()) {
        return bitmap_result(environment, result);
    }
    for (int row = 0; row < height; ++row) {
        std::memcpy(
            result->pixels.data() + pixel_offset(result, 0, row),
            source->pixels.data() + pixel_offset(source, x, y + row),
            static_cast<size_t>(width) * 4
        );
    }
    return bitmap_result(environment, result);
}

void JNICALL bitmap_release(JNIEnv *, jclass, jlong value) {
    delete bitmap(value);
}

jbyteArray JNICALL bitmap_compress(
    JNIEnv *environment,
    jclass,
    jlong value,
    jint format,
    jint quality
) {
    NativeBitmap *source = bitmap(value);
    if (source == nullptr) {
        return nullptr;
    }
    CGImageRef image = CGBitmapContextCreateImage(source->context);
    if (image == nullptr) {
        return nullptr;
    }
    CFMutableDataRef output = CFDataCreateMutable(kCFAllocatorDefault, 0);
    CFStringRef type = format == 0 ? CFSTR("public.jpeg") : CFSTR("public.png");
    CGImageDestinationRef destination = CGImageDestinationCreateWithData(
        output,
        type,
        1,
        nullptr
    );
    if (destination == nullptr) {
        CGImageRelease(image);
        CFRelease(output);
        return nullptr;
    }
    CFDictionaryRef properties = nullptr;
    if (format == 0) {
        const double normalized = std::max(0, std::min(100, quality)) / 100.0;
        CFNumberRef quality_value = CFNumberCreate(
            kCFAllocatorDefault,
            kCFNumberDoubleType,
            &normalized
        );
        const void *keys[] = {kCGImageDestinationLossyCompressionQuality};
        const void *values[] = {quality_value};
        properties = CFDictionaryCreate(
            kCFAllocatorDefault,
            keys,
            values,
            1,
            &kCFTypeDictionaryKeyCallBacks,
            &kCFTypeDictionaryValueCallBacks
        );
        CFRelease(quality_value);
    }
    CGImageDestinationAddImage(destination, image, properties);
    const bool success = CGImageDestinationFinalize(destination);
    if (properties != nullptr) {
        CFRelease(properties);
    }
    CFRelease(destination);
    CGImageRelease(image);
    if (!success || CFDataGetLength(output) > INT32_MAX) {
        CFRelease(output);
        return nullptr;
    }
    const CFIndex length = CFDataGetLength(output);
    jbyteArray result = environment->NewByteArray(static_cast<jsize>(length));
    if (result != nullptr) {
        environment->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(length),
            reinterpret_cast<const jbyte *>(CFDataGetBytePtr(output))
        );
    }
    CFRelease(output);
    return result;
}

void JNICALL bitmap_erase(JNIEnv *, jclass, jlong value, jint color) {
    NativeBitmap *target = bitmap(value);
    if (target == nullptr) {
        return;
    }
    for (size_t position = 0; position < target->pixels.size(); position += 4) {
        write_color(target->pixels.data() + position, color);
    }
}

jint JNICALL bitmap_get_pixel(JNIEnv *, jclass, jlong value, jint x, jint y) {
    NativeBitmap *target = bitmap(value);
    return read_color(
        target->pixels.data() + pixel_offset(target, x, y)
    );
}

void JNICALL bitmap_set_pixel(
    JNIEnv *, jclass, jlong value, jint x, jint y, jint color
) {
    NativeBitmap *target = bitmap(value);
    write_color(
        target->pixels.data() + pixel_offset(target, x, y),
        color
    );
}

void transfer_pixels(
    JNIEnv *environment,
    jlong value,
    jintArray pixels,
    jint offset,
    jint stride,
    jint x,
    jint y,
    jint width,
    jint height,
    bool write
) {
    NativeBitmap *target = bitmap(value);
    std::vector<jint> row(static_cast<size_t>(width));
    for (int line = 0; line < height; ++line) {
        const jint array_offset = offset + (line * stride);
        if (write) {
            environment->GetIntArrayRegion(
                pixels,
                array_offset,
                width,
                row.data()
            );
            for (int column = 0; column < width; ++column) {
                write_color(
                    target->pixels.data() + pixel_offset(
                        target,
                        x + column,
                        y + line
                    ),
                    row[column]
                );
            }
        } else {
            for (int column = 0; column < width; ++column) {
                row[column] = read_color(
                    target->pixels.data() + pixel_offset(
                        target,
                        x + column,
                        y + line
                    )
                );
            }
            environment->SetIntArrayRegion(
                pixels,
                array_offset,
                width,
                row.data()
            );
        }
    }
}

void JNICALL bitmap_get_pixels(
    JNIEnv *environment,
    jclass,
    jlong value,
    jintArray pixels,
    jintArray parameters
) {
    jint values[6];
    if (!read_int_array(environment, parameters, values, 6)) {
        return;
    }
    transfer_pixels(
        environment,
        value,
        pixels,
        values[0],
        values[1],
        values[2],
        values[3],
        values[4],
        values[5],
        false
    );
}

void JNICALL bitmap_set_pixels(
    JNIEnv *environment,
    jclass,
    jlong value,
    jintArray pixels,
    jintArray parameters
) {
    jint values[6];
    if (!read_int_array(environment, parameters, values, 6)) {
        return;
    }
    transfer_pixels(
        environment,
        value,
        pixels,
        values[0],
        values[1],
        values[2],
        values[3],
        values[4],
        values[5],
        true
    );
}

void JNICALL bitmap_copy_pixels(
    JNIEnv *environment,
    jclass,
    jlong destination_handle,
    jlong source_handle,
    jintArray rectangles
) {
    NativeBitmap *destination = bitmap(destination_handle);
    NativeBitmap *source = bitmap(source_handle);
    if (destination == nullptr || source == nullptr) {
        return;
    }
    jint coordinates[8];
    if (!read_int_array(environment, rectangles, coordinates, 8)) {
        return;
    }
    const int source_left = coordinates[0];
    const int source_top = coordinates[1];
    const int source_right = coordinates[2];
    const int source_bottom = coordinates[3];
    const int destination_left = coordinates[4];
    const int destination_top = coordinates[5];
    const int destination_right = coordinates[6];
    const int destination_bottom = coordinates[7];
    const int width = source_right - source_left;
    const int height = source_bottom - source_top;
    if (
        width <= 0 || height <= 0 ||
        destination_right - destination_left != width ||
        destination_bottom - destination_top != height ||
        source_left < 0 || source_top < 0 ||
        destination_left < 0 || destination_top < 0 ||
        source_right > static_cast<int>(source->width) ||
        source_bottom > static_cast<int>(source->height) ||
        destination_right > static_cast<int>(destination->width) ||
        destination_bottom > static_cast<int>(destination->height)
    ) {
        return;
    }
    const std::vector<uint8_t> source_copy = destination == source
        ? source->pixels
        : std::vector<uint8_t>();
    const uint8_t *source_pixels = source_copy.empty()
        ? source->pixels.data()
        : source_copy.data();
    for (int row = 0; row < height; ++row) {
        std::memcpy(
            destination->pixels.data() + pixel_offset(
                destination,
                destination_left,
                destination_top + row
            ),
            source_pixels + pixel_offset(
                source,
                source_left,
                source_top + row
            ),
            static_cast<size_t>(width) * 4
        );
    }
}

void JNICALL canvas_draw_bitmap(
    JNIEnv *environment,
    jclass,
    jlong destination_handle,
    jlong source_handle,
    jintArray rectangles,
    jfloatArray matrix
) {
    NativeBitmap *destination = bitmap(destination_handle);
    NativeBitmap *source = bitmap(source_handle);
    jint coordinates[8];
    jfloat transform[6];
    if (
        destination == nullptr || source == nullptr ||
        !read_int_array(environment, rectangles, coordinates, 8) ||
        !read_float_array(environment, matrix, transform, 6)
    ) {
        return;
    }
    const int source_left = coordinates[0];
    const int source_top = coordinates[1];
    const int source_right = coordinates[2];
    const int source_bottom = coordinates[3];
    const int destination_left = coordinates[4];
    const int destination_top = coordinates[5];
    const int destination_right = coordinates[6];
    const int destination_bottom = coordinates[7];
    const float a = transform[0];
    const float b = transform[1];
    const float c = transform[2];
    const float d = transform[3];
    const float tx = transform[4];
    const float ty = transform[5];
    const int source_width = source_right - source_left;
    const int source_height = source_bottom - source_top;
    const int destination_width = destination_right - destination_left;
    const int destination_height = destination_bottom - destination_top;
    if (
        source_width <= 0 || source_height <= 0 ||
        destination_width <= 0 || destination_height <= 0
    ) {
        return;
    }
    const float determinant = (a * d) - (b * c);
    if (std::abs(determinant) < 1.0e-8f) {
        return;
    }
    const auto transformed_x = [=](float x, float y) {
        return (a * x) + (c * y) + tx;
    };
    const auto transformed_y = [=](float x, float y) {
        return (b * x) + (d * y) + ty;
    };
    const float x1 = transformed_x(destination_left, destination_top);
    const float y1 = transformed_y(destination_left, destination_top);
    const float x2 = transformed_x(destination_right, destination_top);
    const float y2 = transformed_y(destination_right, destination_top);
    const float x3 = transformed_x(destination_left, destination_bottom);
    const float y3 = transformed_y(destination_left, destination_bottom);
    const float x4 = transformed_x(destination_right, destination_bottom);
    const float y4 = transformed_y(destination_right, destination_bottom);
    const int first_x = std::max(
        0,
        static_cast<int>(std::floor(std::min({x1, x2, x3, x4})))
    );
    const int last_x = std::min(
        static_cast<int>(destination->width),
        static_cast<int>(std::ceil(std::max({x1, x2, x3, x4})))
    );
    const int first_y = std::max(
        0,
        static_cast<int>(std::floor(std::min({y1, y2, y3, y4})))
    );
    const int last_y = std::min(
        static_cast<int>(destination->height),
        static_cast<int>(std::ceil(std::max({y1, y2, y3, y4})))
    );
    const std::vector<uint8_t> source_copy = destination == source
        ? source->pixels
        : std::vector<uint8_t>();
    const uint8_t *source_pixels = source_copy.empty()
        ? source->pixels.data()
        : source_copy.data();
    for (int output_y = first_y; output_y < last_y; ++output_y) {
        for (int output_x = first_x; output_x < last_x; ++output_x) {
            const float transformed_dx = (output_x + 0.5f) - tx;
            const float transformed_dy = (output_y + 0.5f) - ty;
            const float local_x =
                ((d * transformed_dx) - (c * transformed_dy)) / determinant;
            const float local_y =
                ((a * transformed_dy) - (b * transformed_dx)) / determinant;
            if (
                local_x < destination_left || local_x >= destination_right ||
                local_y < destination_top || local_y >= destination_bottom
            ) {
                continue;
            }
            const int input_x = source_left + static_cast<int>(
                ((local_x - destination_left) * source_width) /
                destination_width
            );
            const int input_y = source_top + static_cast<int>(
                ((local_y - destination_top) * source_height) /
                destination_height
            );
            if (
                input_x < 0 || input_y < 0 ||
                input_x >= static_cast<int>(source->width) ||
                input_y >= static_cast<int>(source->height)
            ) {
                continue;
            }
            std::memcpy(
                destination->pixels.data() + pixel_offset(
                    destination,
                    output_x,
                    output_y
                ),
                source_pixels + pixel_offset(source, input_x, input_y),
                4
            );
        }
    }
}

void JNICALL canvas_draw_text(
    JNIEnv *environment,
    jclass,
    jlong value,
    jstring text,
    jintArray style_values,
    jstring font_name,
    jfloatArray geometry
) {
    NativeBitmap *target = bitmap(value);
    jint style[3];
    jfloat values[10];
    if (
        target == nullptr ||
        !read_int_array(environment, style_values, style, 3) ||
        !read_float_array(environment, geometry, values, 10)
    ) {
        return;
    }
    const int color = style[0];
    const bool bold = style[1] != 0;
    const int paint_style = style[2];
    const float x = values[0];
    const float baseline = values[1];
    const float size = values[2];
    const float stroke_width = values[3];
    const float a = values[4];
    const float b = values[5];
    const float c = values[6];
    const float d = values[7];
    const float tx = values[8];
    const float ty = values[9];
    CFStringRef string = cf_string(environment, text);
    if (string == nullptr) {
        if (string != nullptr) CFRelease(string);
        return;
    }
    CFStringRef font_name_cf = cf_string(environment, font_name);
    CFAttributedStringRef attributed = attributed_text(
        string,
        size,
        bold,
        color,
        paint_style,
        stroke_width,
        font_name_cf
    );
    CTLineRef line = CTLineCreateWithAttributedString(attributed);
    CGContextRef context = CGBitmapContextCreate(
        target->pixels.data(),
        target->width,
        target->height,
        8,
        target->bytes_per_row,
        target->color_space,
        kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big
    );
    CGContextSaveGState(context);
    const CGFloat height = static_cast<CGFloat>(target->height);
    const CGAffineTransform transform = CGAffineTransformMake(
        a,
        -b,
        -c,
        d,
        (c * height) + tx,
        (height * (1 - d)) - ty
    );
    CGContextConcatCTM(context, transform);
    CGContextSetTextPosition(context, x, height - baseline);
    CTLineDraw(line, context);
    CGContextRestoreGState(context);
    CGContextRelease(context);
    CFRelease(line);
    CFRelease(attributed);
    CFRelease(font_name_cf);
    CFRelease(string);
}

jstring JNICALL text_register_font(
    JNIEnv *environment,
    jclass,
    jstring path
) {
    CFStringRef path_string = cf_string(environment, path);
    if (path_string == nullptr) {
        return nullptr;
    }
    CFURLRef url = CFURLCreateWithFileSystemPath(
        kCFAllocatorDefault,
        path_string,
        kCFURLPOSIXPathStyle,
        false
    );
    CFRelease(path_string);
    if (url == nullptr) {
        return nullptr;
    }
    CFErrorRef error = nullptr;
    CTFontManagerRegisterFontsForURL(url, kCTFontManagerScopeProcess, &error);
    if (error != nullptr) {
        CFRelease(error);
    }
    CFArrayRef descriptors = CTFontManagerCreateFontDescriptorsFromURL(url);
    CFRelease(url);
    if (descriptors == nullptr || CFArrayGetCount(descriptors) == 0) {
        if (descriptors != nullptr) {
            CFRelease(descriptors);
        }
        return nullptr;
    }
    CTFontDescriptorRef descriptor = reinterpret_cast<CTFontDescriptorRef>(
        CFArrayGetValueAtIndex(descriptors, 0)
    );
    CFStringRef name = reinterpret_cast<CFStringRef>(
        CTFontDescriptorCopyAttribute(descriptor, kCTFontNameAttribute)
    );
    CFRelease(descriptors);
    if (name == nullptr) {
        return nullptr;
    }
    const CFIndex length = CFStringGetLength(name);
    std::vector<UniChar> characters(static_cast<size_t>(length));
    CFStringGetCharacters(name, CFRangeMake(0, length), characters.data());
    jstring result = environment->NewString(
        reinterpret_cast<const jchar *>(characters.data()),
        static_cast<jsize>(length)
    );
    CFRelease(name);
    return result;
}

jfloat JNICALL text_measure(
    JNIEnv *environment,
    jclass,
    jstring text,
    jfloat size,
    jboolean bold,
    jstring font_name
) {
    CFStringRef string = cf_string(environment, text);
    if (string == nullptr) {
        return 0;
    }
    CFStringRef font_name_cf = cf_string(environment, font_name);
    CFAttributedStringRef attributed = attributed_text(
        string,
        size,
        bold == JNI_TRUE,
        0xff000000,
        0,
        0,
        font_name_cf
    );
    CTLineRef line = CTLineCreateWithAttributedString(attributed);
    const double width = CTLineGetTypographicBounds(
        line,
        nullptr,
        nullptr,
        nullptr
    );
    CFRelease(line);
    CFRelease(attributed);
    CFRelease(font_name_cf);
    CFRelease(string);
    return static_cast<jfloat>(width);
}

jfloatArray JNICALL text_font_metrics(
    JNIEnv *environment,
    jclass,
    jfloat size,
    jboolean bold,
    jstring font_name
) {
    CFStringRef font_name_cf = cf_string(environment, font_name);
    CTFontRef font = create_font(size, bold == JNI_TRUE, font_name_cf);
    const jfloat values[] = {
        static_cast<jfloat>(CTFontGetAscent(font)),
        static_cast<jfloat>(CTFontGetDescent(font)),
        static_cast<jfloat>(CTFontGetLeading(font)),
    };
    CFRelease(font);
    CFRelease(font_name_cf);
    jfloatArray result = environment->NewFloatArray(3);
    if (result != nullptr) {
        environment->SetFloatArrayRegion(result, 0, 3, values);
    }
    return result;
}

jintArray JNICALL text_line_ends(
    JNIEnv *environment,
    jclass,
    jstring text,
    jfloat width,
    jfloat size,
    jboolean bold,
    jstring font_name
) {
    CFStringRef string = cf_string(environment, text);
    if (string == nullptr) {
        return nullptr;
    }
    CFStringRef font_name_cf = cf_string(environment, font_name);
    CFAttributedStringRef attributed = attributed_text(
        string,
        size,
        bold == JNI_TRUE,
        0xff000000,
        0,
        0,
        font_name_cf
    );
    CTTypesetterRef typesetter = CTTypesetterCreateWithAttributedString(attributed);
    const CFIndex length = CFStringGetLength(string);
    std::vector<jint> ends;
    CFIndex position = 0;
    if (length == 0) {
        ends.push_back(0);
    }
    while (position < length) {
        CFIndex count = CTTypesetterSuggestLineBreak(typesetter, position, width);
        if (count <= 0) {
            count = 1;
        }
        CFIndex hard_break = position;
        while (hard_break < position + count && hard_break < length) {
            UniChar character = CFStringGetCharacterAtIndex(string, hard_break);
            hard_break++;
            if (character == '\n') {
                count = hard_break - position;
                break;
            }
        }
        position = std::min(length, position + count);
        ends.push_back(static_cast<jint>(position));
    }
    CFRelease(typesetter);
    CFRelease(attributed);
    CFRelease(font_name_cf);
    CFRelease(string);
    jintArray result = environment->NewIntArray(static_cast<jsize>(ends.size()));
    if (result != nullptr && !ends.empty()) {
        environment->SetIntArrayRegion(
            result,
            0,
            static_cast<jsize>(ends.size()),
            ends.data()
        );
    }
    return result;
}

jlong JNICALL pdf_open(JNIEnv *environment, jclass, jstring path) {
    CFStringRef path_string = cf_string(environment, path);
    if (path_string == nullptr) {
        return 0;
    }
    CFURLRef url = CFURLCreateWithFileSystemPath(
        kCFAllocatorDefault,
        path_string,
        kCFURLPOSIXPathStyle,
        false
    );
    CFRelease(path_string);
    if (url == nullptr) {
        return 0;
    }
    CGPDFDocumentRef document = CGPDFDocumentCreateWithURL(url);
    CFRelease(url);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(document));
}

jint JNICALL pdf_page_count(JNIEnv *, jclass, jlong value) {
    CGPDFDocumentRef document = reinterpret_cast<CGPDFDocumentRef>(
        static_cast<intptr_t>(value)
    );
    return document == nullptr
        ? 0
        : static_cast<jint>(CGPDFDocumentGetNumberOfPages(document));
}

jintArray JNICALL pdf_page_size(
    JNIEnv *environment,
    jclass,
    jlong value,
    jint page_index
) {
    CGPDFDocumentRef document = reinterpret_cast<CGPDFDocumentRef>(
        static_cast<intptr_t>(value)
    );
    if (document == nullptr || page_index < 0) {
        return nullptr;
    }
    CGPDFPageRef page = CGPDFDocumentGetPage(document, page_index + 1);
    if (page == nullptr) {
        return nullptr;
    }
    const CGRect box = CGPDFPageGetBoxRect(page, kCGPDFMediaBox);
    const int rotation = std::abs(CGPDFPageGetRotationAngle(page)) % 180;
    const CGFloat page_width = rotation == 90
        ? CGRectGetHeight(box)
        : CGRectGetWidth(box);
    const CGFloat page_height = rotation == 90
        ? CGRectGetWidth(box)
        : CGRectGetHeight(box);
    const jint values[] = {
        static_cast<jint>(std::ceil(page_width)),
        static_cast<jint>(std::ceil(page_height)),
    };
    jintArray result = environment->NewIntArray(2);
    if (result != nullptr) {
        environment->SetIntArrayRegion(result, 0, 2, values);
    }
    return result;
}

jboolean JNICALL pdf_render(
    JNIEnv *environment,
    jclass,
    jlong value,
    jlong bitmap_handle,
    jintArray parameters
) {
    CGPDFDocumentRef document = reinterpret_cast<CGPDFDocumentRef>(
        static_cast<intptr_t>(value)
    );
    NativeBitmap *target = bitmap(bitmap_handle);
    jint values[5];
    if (!read_int_array(environment, parameters, values, 5)) {
        return JNI_FALSE;
    }
    const int page_index = values[0];
    const int left = values[1];
    const int top = values[2];
    const int right = values[3];
    const int bottom = values[4];
    if (
        document == nullptr || target == nullptr || target->context == nullptr ||
        page_index < 0 || left >= right || top >= bottom
    ) {
        return JNI_FALSE;
    }
    CGPDFPageRef page = CGPDFDocumentGetPage(document, page_index + 1);
    if (page == nullptr) {
        return JNI_FALSE;
    }
    const CGRect destination = CGRectMake(
        left,
        static_cast<CGFloat>(target->height) - bottom,
        right - left,
        bottom - top
    );
    CGContextSaveGState(target->context);
    CGContextClipToRect(target->context, destination);
    const CGAffineTransform transform = CGPDFPageGetDrawingTransform(
        page,
        kCGPDFMediaBox,
        destination,
        0,
        true
    );
    CGContextConcatCTM(target->context, transform);
    CGContextDrawPDFPage(target->context, page);
    CGContextRestoreGState(target->context);
    return JNI_TRUE;
}

void JNICALL pdf_close(JNIEnv *, jclass, jlong value) {
    CGPDFDocumentRef document = reinterpret_cast<CGPDFDocumentRef>(
        static_cast<intptr_t>(value)
    );
    if (document != nullptr) {
        CGPDFDocumentRelease(document);
    }
}

jlong JNICALL javascript_create(JNIEnv *, jclass) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(JSGlobalContextCreate(nullptr)));
}

jobject JNICALL javascript_evaluate(
    JNIEnv *environment,
    jclass,
    jlong value,
    jstring script
) {
    JSGlobalContextRef context = reinterpret_cast<JSGlobalContextRef>(
        static_cast<intptr_t>(value)
    );
    CFStringRef source_cf = cf_string(environment, script);
    if (context == nullptr || source_cf == nullptr) {
        if (source_cf != nullptr) CFRelease(source_cf);
        return nullptr;
    }
    JSStringRef source = JSStringCreateWithCFString(source_cf);
    CFRelease(source_cf);
    JSValueRef exception = nullptr;
    JSValueRef result = JSEvaluateScript(
        context,
        source,
        nullptr,
        nullptr,
        1,
        &exception
    );
    JSStringRelease(source);
    if (exception != nullptr) {
        JSStringRef message = JSValueToStringCopy(context, exception, nullptr);
        const size_t capacity = JSStringGetMaximumUTF8CStringSize(message);
        std::vector<char> utf8(capacity);
        JSStringGetUTF8CString(message, utf8.data(), capacity);
        JSStringRelease(message);
        throw_quickjs(environment, utf8.data());
        return nullptr;
    }
    if (result == nullptr || JSValueIsNull(context, result) || JSValueIsUndefined(context, result)) {
        return nullptr;
    }
    if (JSValueIsString(context, result)) {
        JSStringRef string = JSValueToStringCopy(context, result, nullptr);
        jstring output = java_string(environment, string);
        JSStringRelease(string);
        return output;
    }
    if (JSValueIsBoolean(context, result)) {
        jclass type = environment->FindClass("java/lang/Boolean");
        jmethodID method = environment->GetStaticMethodID(
            type,
            "valueOf",
            "(Z)Ljava/lang/Boolean;"
        );
        jobject output = environment->CallStaticObjectMethod(
            type,
            method,
            JSValueToBoolean(context, result) ? JNI_TRUE : JNI_FALSE
        );
        environment->DeleteLocalRef(type);
        return output;
    }
    if (JSValueIsNumber(context, result)) {
        const double number = JSValueToNumber(context, result, nullptr);
        const bool integral = std::floor(number) == number &&
            number >= INT32_MIN && number <= INT32_MAX;
        const char *class_name = integral ? "java/lang/Integer" : "java/lang/Double";
        const char *signature = integral
            ? "(I)Ljava/lang/Integer;"
            : "(D)Ljava/lang/Double;";
        jclass type = environment->FindClass(class_name);
        jmethodID method = environment->GetStaticMethodID(type, "valueOf", signature);
        jobject output = integral
            ? environment->CallStaticObjectMethod(type, method, static_cast<jint>(number))
            : environment->CallStaticObjectMethod(type, method, static_cast<jdouble>(number));
        environment->DeleteLocalRef(type);
        return output;
    }
    JSValueRef json_exception = nullptr;
    JSStringRef json = JSValueCreateJSONString(context, result, 0, &json_exception);
    if (json == nullptr) {
        return nullptr;
    }
    jstring output = java_string(environment, json);
    JSStringRelease(json);
    return output;
}

void JNICALL javascript_close(JNIEnv *, jclass, jlong value) {
    JSGlobalContextRef context = reinterpret_cast<JSGlobalContextRef>(
        static_cast<intptr_t>(value)
    );
    if (context != nullptr) {
        JSGlobalContextRelease(context);
    }
}

jstring JNICALL webkit_command(
    JNIEnv *environment,
    jclass,
    jstring operation,
    jlong value,
    jstring argument1,
    jstring argument2
) {
    if (tachiyomiaz_webkit_command == nullptr) {
        return environment->NewStringUTF(
            "__UNAVAILABLE__WKWebView bridge is not installed"
        );
    }
    const char *operation_utf8 = operation == nullptr
        ? ""
        : environment->GetStringUTFChars(operation, nullptr);
    const char *argument1_utf8 = argument1 == nullptr
        ? nullptr
        : environment->GetStringUTFChars(argument1, nullptr);
    const char *argument2_utf8 = argument2 == nullptr
        ? nullptr
        : environment->GetStringUTFChars(argument2, nullptr);
    if (
        operation_utf8 == nullptr ||
        (argument1 != nullptr && argument1_utf8 == nullptr) ||
        (argument2 != nullptr && argument2_utf8 == nullptr)
    ) {
        if (operation != nullptr && operation_utf8 != nullptr) {
            environment->ReleaseStringUTFChars(operation, operation_utf8);
        }
        if (argument1 != nullptr && argument1_utf8 != nullptr) {
            environment->ReleaseStringUTFChars(argument1, argument1_utf8);
        }
        if (argument2 != nullptr && argument2_utf8 != nullptr) {
            environment->ReleaseStringUTFChars(argument2, argument2_utf8);
        }
        return nullptr;
    }
    char *result = tachiyomiaz_webkit_command(
        operation_utf8,
        static_cast<int64_t>(value),
        argument1_utf8,
        argument2_utf8
    );
    if (operation != nullptr) {
        environment->ReleaseStringUTFChars(operation, operation_utf8);
    }
    if (argument1 != nullptr) {
        environment->ReleaseStringUTFChars(argument1, argument1_utf8);
    }
    if (argument2 != nullptr) {
        environment->ReleaseStringUTFChars(argument2, argument2_utf8);
    }
    if (result == nullptr) {
        return nullptr;
    }
    jstring output = environment->NewStringUTF(result);
    std::free(result);
    return output;
}

const JNINativeMethod methods[] = {
    {const_cast<char *>("bitmapCreate"), const_cast<char *>("(II)[J"), reinterpret_cast<void *>(&bitmap_create)},
    {const_cast<char *>("bitmapDecode"), const_cast<char *>("([BII)[J"), reinterpret_cast<void *>(&bitmap_decode)},
    {const_cast<char *>("bitmapCrop"), const_cast<char *>("(JIIII)[J"), reinterpret_cast<void *>(&bitmap_crop)},
    {const_cast<char *>("bitmapRelease"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(&bitmap_release)},
    {const_cast<char *>("bitmapCompress"), const_cast<char *>("(JII)[B"), reinterpret_cast<void *>(&bitmap_compress)},
    {const_cast<char *>("bitmapErase"), const_cast<char *>("(JI)V"), reinterpret_cast<void *>(&bitmap_erase)},
    {const_cast<char *>("bitmapGetPixel"), const_cast<char *>("(JII)I"), reinterpret_cast<void *>(&bitmap_get_pixel)},
    {const_cast<char *>("bitmapSetPixel"), const_cast<char *>("(JIII)V"), reinterpret_cast<void *>(&bitmap_set_pixel)},
    {const_cast<char *>("bitmapGetPixels"), const_cast<char *>("(J[I[I)V"), reinterpret_cast<void *>(&bitmap_get_pixels)},
    {const_cast<char *>("bitmapSetPixels"), const_cast<char *>("(J[I[I)V"), reinterpret_cast<void *>(&bitmap_set_pixels)},
    {const_cast<char *>("bitmapCopyPixels"), const_cast<char *>("(JJ[I)V"), reinterpret_cast<void *>(&bitmap_copy_pixels)},
    {const_cast<char *>("canvasDrawBitmap"), const_cast<char *>("(JJ[I[F)V"), reinterpret_cast<void *>(&canvas_draw_bitmap)},
    {const_cast<char *>("canvasDrawText"), const_cast<char *>("(JLjava/lang/String;[ILjava/lang/String;[F)V"), reinterpret_cast<void *>(&canvas_draw_text)},
    {const_cast<char *>("textRegisterFont"), const_cast<char *>("(Ljava/lang/String;)Ljava/lang/String;"), reinterpret_cast<void *>(&text_register_font)},
    {const_cast<char *>("textMeasure"), const_cast<char *>("(Ljava/lang/String;FZLjava/lang/String;)F"), reinterpret_cast<void *>(&text_measure)},
    {const_cast<char *>("textFontMetrics"), const_cast<char *>("(FZLjava/lang/String;)[F"), reinterpret_cast<void *>(&text_font_metrics)},
    {const_cast<char *>("textLineEnds"), const_cast<char *>("(Ljava/lang/String;FFZLjava/lang/String;)[I"), reinterpret_cast<void *>(&text_line_ends)},
    {const_cast<char *>("pdfOpen"), const_cast<char *>("(Ljava/lang/String;)J"), reinterpret_cast<void *>(&pdf_open)},
    {const_cast<char *>("pdfPageCount"), const_cast<char *>("(J)I"), reinterpret_cast<void *>(&pdf_page_count)},
    {const_cast<char *>("pdfPageSize"), const_cast<char *>("(JI)[I"), reinterpret_cast<void *>(&pdf_page_size)},
    {const_cast<char *>("pdfRender"), const_cast<char *>("(JJ[I)Z"), reinterpret_cast<void *>(&pdf_render)},
    {const_cast<char *>("pdfClose"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(&pdf_close)},
    {const_cast<char *>("javascriptCreate"), const_cast<char *>("()J"), reinterpret_cast<void *>(&javascript_create)},
    {const_cast<char *>("javascriptEvaluate"), const_cast<char *>("(JLjava/lang/String;)Ljava/lang/Object;"), reinterpret_cast<void *>(&javascript_evaluate)},
    {const_cast<char *>("javascriptClose"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(&javascript_close)},
    {const_cast<char *>("webkitCommand"), const_cast<char *>("(Ljava/lang/String;JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;"), reinterpret_cast<void *>(&webkit_command)},
};
#endif

} // namespace

#if defined(__APPLE__)
extern "C" char *tachiyomiaz_jvm_webkit_event(
    int64_t handle,
    const char *event,
    const char *argument1,
    const char *argument2
) {
    if (
        mobile_vm == nullptr || mobile_bridge_type == nullptr ||
        mobile_webkit_event_method == nullptr
    ) {
        return strdup("");
    }

    JNIEnv *environment = nullptr;
    bool attached = false;
    const jint get_result = mobile_vm->GetEnv(
        reinterpret_cast<void **>(&environment),
        JNI_VERSION_1_8
    );
    if (get_result == JNI_EDETACHED) {
        if (
            mobile_vm->AttachCurrentThread(
                reinterpret_cast<void **>(&environment),
                nullptr
            ) != JNI_OK
        ) {
            return strdup("");
        }
        attached = true;
    } else if (get_result != JNI_OK) {
        return strdup("");
    }

    jstring java_event = environment->NewStringUTF(event == nullptr ? "" : event);
    jstring java_argument1 = environment->NewStringUTF(
        argument1 == nullptr ? "" : argument1
    );
    jstring java_argument2 = environment->NewStringUTF(
        argument2 == nullptr ? "" : argument2
    );
    jobject result = nullptr;
    if (
        java_event != nullptr && java_argument1 != nullptr &&
        java_argument2 != nullptr
    ) {
        result = environment->CallStaticObjectMethod(
            mobile_bridge_type,
            mobile_webkit_event_method,
            static_cast<jlong>(handle),
            java_event,
            java_argument1,
            java_argument2
        );
    }

    std::string output;
    if (environment->ExceptionCheck()) {
        environment->ExceptionDescribe();
        environment->ExceptionClear();
    } else if (result != nullptr) {
        const char *utf8 = environment->GetStringUTFChars(
            static_cast<jstring>(result),
            nullptr
        );
        if (utf8 != nullptr) {
            output = utf8;
            environment->ReleaseStringUTFChars(static_cast<jstring>(result), utf8);
        }
    }

    if (result != nullptr) environment->DeleteLocalRef(result);
    if (java_event != nullptr) environment->DeleteLocalRef(java_event);
    if (java_argument1 != nullptr) environment->DeleteLocalRef(java_argument1);
    if (java_argument2 != nullptr) environment->DeleteLocalRef(java_argument2);
    if (attached) mobile_vm->DetachCurrentThread();
    return strdup(output.c_str());
}
#endif

bool tjr_register_mobile_natives(
    JNIEnv *environment,
    std::string &error
) {
#if defined(__APPLE__)
    jclass type = environment->FindClass("app/tachiaz/compat/NativeBridge");
    if (type == nullptr) {
        environment->ExceptionClear();
        error = "Unable to load the mobile Android native bridge";
        return false;
    }
    if (environment->GetJavaVM(&mobile_vm) != JNI_OK) {
        environment->DeleteLocalRef(type);
        error = "Unable to retain the Java VM for WebKit callbacks";
        return false;
    }
    mobile_bridge_type = static_cast<jclass>(environment->NewGlobalRef(type));
    mobile_webkit_event_method = environment->GetStaticMethodID(
        type,
        "dispatchWebKitEvent",
        "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
    );
    if (mobile_bridge_type == nullptr || mobile_webkit_event_method == nullptr) {
        environment->ExceptionClear();
        environment->DeleteLocalRef(type);
        error = "Unable to register the WebKit callback dispatcher";
        return false;
    }
    const jint result = environment->RegisterNatives(
        type,
        methods,
        static_cast<jint>(sizeof(methods) / sizeof(methods[0]))
    );
    environment->DeleteLocalRef(type);
    if (result != JNI_OK) {
        environment->ExceptionClear();
        error = "Unable to register the mobile Android native bridge";
        return false;
    }
    return true;
#else
    (void)environment;
    error = "The mobile Android native bridge requires an Apple platform";
    return false;
#endif
}
