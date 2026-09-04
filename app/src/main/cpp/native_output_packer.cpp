#include <jni.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace {

void throw_java(JNIEnv* env, const char* class_name, const char* message) {
    jclass type = env->FindClass(class_name);
    if (type != nullptr) {
        env->ThrowNew(type, message);
    }
}

std::uint8_t normalized_to_byte(float value) {
    if (!(value > 0.0F)) {
        return 0;
    }
    if (value >= 1.0F) {
        return 255;
    }
    return static_cast<std::uint8_t>(static_cast<int>(value * 255.0F + 0.5F));
}

#if defined(__aarch64__)
uint8x8_t normalized_to_byte_8(const float* source) {
    const float32x4_t zero = vdupq_n_f32(0.0F);
    const float32x4_t one = vdupq_n_f32(1.0F);
    const float32x4_t scale = vdupq_n_f32(255.0F);
    const float32x4_t half = vdupq_n_f32(0.5F);
    float32x4_t low = vld1q_f32(source);
    float32x4_t high = vld1q_f32(source + 4);
    low = vbslq_f32(vcgtq_f32(low, zero), low, zero);
    high = vbslq_f32(vcgtq_f32(high, zero), high, zero);
    low = vminq_f32(low, one);
    high = vminq_f32(high, one);
    const uint32x4_t low_u32 = vcvtq_u32_f32(vaddq_f32(vmulq_f32(low, scale), half));
    const uint32x4_t high_u32 = vcvtq_u32_f32(vaddq_f32(vmulq_f32(high, scale), half));
    const uint16x8_t u16 = vcombine_u16(vqmovn_u32(low_u32), vqmovn_u32(high_u32));
    return vqmovn_u16(u16);
}
#endif

void pack_output(
        const float* output,
        const std::uint8_t* input_rgba,
        int input_width,
        int input_height,
        int output_width,
        int output_height,
        std::uint8_t* packed_rgba) {
    const std::size_t output_pixels =
            static_cast<std::size_t>(output_width) * static_cast<std::size_t>(output_height);
    const float* red = output;
    const float* green = output + output_pixels;
    const float* blue = output + output_pixels * 2U;
    std::array<std::uint8_t, 4096> alpha_values{};

    for (int y = 0; y < output_height; ++y) {
        const int input_y = std::min(input_height - 1, y * input_height / output_height);
        const std::uint8_t* alpha_row =
                input_rgba + static_cast<std::size_t>(input_y * input_width) * 4U;
        int input_x = 0;
        int alpha_accumulator = 0;
        for (int x = 0; x < output_width; ++x) {
            alpha_values[static_cast<std::size_t>(x)] =
                    alpha_row[static_cast<std::size_t>(input_x) * 4U + 3U];
            alpha_accumulator += input_width;
            while (alpha_accumulator >= output_width) {
                alpha_accumulator -= output_width;
                input_x = std::min(input_width - 1, input_x + 1);
            }
        }
        const std::size_t row_pixel = static_cast<std::size_t>(y * output_width);
        int x = 0;
#if defined(__aarch64__)
        for (; x + 8 <= output_width; x += 8) {
            const std::size_t pixel = row_pixel + static_cast<std::size_t>(x);
            uint8x8x4_t rgba;
            rgba.val[0] = normalized_to_byte_8(red + pixel);
            rgba.val[1] = normalized_to_byte_8(green + pixel);
            rgba.val[2] = normalized_to_byte_8(blue + pixel);
            rgba.val[3] = vld1_u8(alpha_values.data() + x);
            vst4_u8(packed_rgba + pixel * 4U, rgba);
        }
#endif
        for (; x < output_width; ++x) {
            const std::size_t pixel = row_pixel + static_cast<std::size_t>(x);
            packed_rgba[pixel * 4U] = normalized_to_byte(red[pixel]);
            packed_rgba[pixel * 4U + 1U] = normalized_to_byte(green[pixel]);
            packed_rgba[pixel * 4U + 2U] = normalized_to_byte(blue[pixel]);
            packed_rgba[pixel * 4U + 3U] =
                    alpha_values[static_cast<std::size_t>(x)];
        }
    }
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_dev_aisystems_quicksrplayerlab_NativeOutputPacker_nativePack(
        JNIEnv* env,
        jclass,
        jfloatArray output_array,
        jbyteArray input_rgba_array,
        jint input_width,
        jint input_height,
        jint output_width,
        jint output_height,
        jobject packed_rgba_buffer) {
    if (output_array == nullptr || input_rgba_array == nullptr || packed_rgba_buffer == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "QuickSR native pack received null input");
        return;
    }
    if (input_width <= 0 || input_width > 4096 || input_height <= 0 || input_height > 4096
            || output_width <= 0 || output_width > 4096
            || output_height <= 0 || output_height > 4096) {
        throw_java(env, "java/lang/IllegalArgumentException", "QuickSR native dimensions are invalid");
        return;
    }
    const std::int64_t input_pixels =
            static_cast<std::int64_t>(input_width) * static_cast<std::int64_t>(input_height);
    const std::int64_t output_pixels =
            static_cast<std::int64_t>(output_width) * static_cast<std::int64_t>(output_height);
    const std::int64_t expected_output_floats = output_pixels * 3;
    const std::int64_t expected_input_bytes = input_pixels * 4;
    const std::int64_t expected_output_bytes = output_pixels * 4;
    if (expected_output_floats > std::numeric_limits<jsize>::max()
            || expected_input_bytes > std::numeric_limits<jsize>::max()
            || env->GetArrayLength(output_array) != expected_output_floats
            || env->GetArrayLength(input_rgba_array) != expected_input_bytes
            || env->GetDirectBufferCapacity(packed_rgba_buffer) != expected_output_bytes) {
        throw_java(env, "java/lang/IllegalArgumentException", "QuickSR native buffer size mismatch");
        return;
    }
    auto* packed_rgba = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(packed_rgba_buffer));
    if (packed_rgba == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "QuickSR native output must be direct");
        return;
    }

    auto* output = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(output_array, nullptr));
    if (output == nullptr) {
        throw_java(env, "java/lang/OutOfMemoryError", "QuickSR native output tensor pin failed");
        return;
    }
    auto* input_rgba = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(input_rgba_array, nullptr));
    if (input_rgba == nullptr) {
        env->ReleasePrimitiveArrayCritical(output_array, output, JNI_ABORT);
        throw_java(env, "java/lang/OutOfMemoryError", "QuickSR native input alpha pin failed");
        return;
    }

    pack_output(
            output,
            reinterpret_cast<const std::uint8_t*>(input_rgba),
            input_width,
            input_height,
            output_width,
            output_height,
            packed_rgba);

    env->ReleasePrimitiveArrayCritical(input_rgba_array, input_rgba, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(output_array, output, JNI_ABORT);
}
