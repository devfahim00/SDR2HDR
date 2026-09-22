// JNI bridge between com.devfahim00.sdr2hdr.AiUpscaler and the ncnn based upscaler.
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>

#include "cpu.h"
#include "gpu.h"
#include "net.h"
#include "sr_core.h"

#define LOG_TAG "SdrSR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_mutex;                 // guards everything below
ncnn::Net* g_net = nullptr;
AAssetManager* g_mgr = nullptr;
jobject g_mgr_ref = nullptr;        // keeps the Java AssetManager (and thus g_mgr) alive
std::string g_base;                 // asset path without extension
int g_scale = 0;
int g_tile = 200;
bool g_gpu = false;
bool g_gpu_instance = false;
std::string g_device = "CPU";
std::atomic<bool> g_cancel{false};

constexpr int kMinTile = 64;
constexpr int kPrepad = 10;

void destroy_net_locked() {
    delete g_net;
    g_net = nullptr;
}

// Builds the network on the GPU (if requested and present) or on the CPU.
bool create_net_locked(bool want_gpu) {
    destroy_net_locked();

    bool gpu = false;
    if (want_gpu) {
        if (!g_gpu_instance && ncnn::create_gpu_instance() == 0) g_gpu_instance = true;
        gpu = g_gpu_instance && ncnn::get_gpu_count() > 0;
    }

    auto* net = new ncnn::Net();
    net->opt.use_vulkan_compute = gpu;
    if (gpu) {
        net->set_vulkan_device(ncnn::get_default_gpu_index());
    } else {
        // All cores, not just the "big" cluster: this runs a handful of times per second at
        // most (once per video frame), so the little cores' extra throughput is worth more
        // than avoiding their lower per-core clock.
        net->opt.num_threads = std::max(1, ncnn::get_cpu_count());
    }

    const std::string param = g_base + ".param";
    const std::string bin = g_base + ".bin";
    if (net->load_param(g_mgr, param.c_str()) != 0 || net->load_model(g_mgr, bin.c_str()) != 0) {
        LOGE("failed to load model %s", g_base.c_str());
        delete net;
        return false;
    }

    g_net = net;
    g_gpu = gpu;
    g_device = gpu ? std::string(ncnn::get_gpu_info(ncnn::get_default_gpu_index()).device_name()) : "CPU";
    LOGI("model %s ready on %s", g_base.c_str(), g_device.c_str());
    return true;
}

}  // namespace

extern "C" {

// Returns 0 = ready on GPU, 1 = ready on CPU, negative = error.
JNIEXPORT jint JNICALL
Java_com_devfahim00_sdr2hdr_AiUpscaler_nativeLoad(JNIEnv* env, jclass, jobject assetManager,
                                                  jstring jname, jint scale, jboolean useGpu,
                                                  jint tile) {
    std::lock_guard<std::mutex> lock(g_mutex);
    destroy_net_locked();

    if (!assetManager || !jname) return -1;
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) return -1;

    if (g_mgr_ref) env->DeleteGlobalRef(g_mgr_ref);
    g_mgr_ref = env->NewGlobalRef(assetManager);
    g_mgr = mgr;

    const char* name = env->GetStringUTFChars(jname, nullptr);
    g_base = std::string("models/") + (name ? name : "");
    if (name) env->ReleaseStringUTFChars(jname, name);

    g_scale = scale;
    g_tile = std::max(kMinTile, (int)tile);
    g_cancel.store(false);

    if (!create_net_locked(useGpu == JNI_TRUE)) return -2;
    return g_gpu ? 0 : 1;
}

// Upscales `inBmp` into `outBmp` (both ARGB_8888; out must be exactly scale x larger).
// 0 = ok, -2 = cancelled, other negative = failure.
JNIEXPORT jint JNICALL
Java_com_devfahim00_sdr2hdr_AiUpscaler_nativeUpscale(JNIEnv* env, jclass, jobject inBmp,
                                                     jobject outBmp) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_net) return -10;

    AndroidBitmapInfo ii{}, oi{};
    if (AndroidBitmap_getInfo(env, inBmp, &ii) < 0 || AndroidBitmap_getInfo(env, outBmp, &oi) < 0) return -11;
    if (ii.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || oi.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -12;

    void* ip = nullptr;
    void* op = nullptr;
    if (AndroidBitmap_lockPixels(env, inBmp, &ip) < 0) return -13;
    if (AndroidBitmap_lockPixels(env, outBmp, &op) < 0) {
        AndroidBitmap_unlockPixels(env, inBmp);
        return -13;
    }

    int tile = g_tile;
    int rc = sr::ERR_INFER;
    for (;;) {
        sr::Params p;
        p.scale = g_scale;
        p.tile = tile;
        p.prepad = kPrepad;
        rc = sr::upscale_rgba(*g_net, p, static_cast<const uint8_t*>(ip), (int)ii.width, (int)ii.height,
                              (int)ii.stride, static_cast<uint8_t*>(op), (int)oi.width, (int)oi.height,
                              (int)oi.stride, g_cancel);
        if (rc != sr::ERR_INFER) break;

        if (tile > kMinTile) {
            // usually GPU memory pressure: retry the frame with smaller tiles
            tile = std::max(kMinTile, tile / 2);
            g_tile = tile;
            LOGW("inference failed, retrying with tile=%d", tile);
            continue;
        }
        if (g_gpu) {
            // Vulkan cannot cope even with the smallest tile: continue on the CPU.
            LOGW("GPU inference failed at tile=%d, falling back to CPU", tile);
            if (create_net_locked(false)) continue;
        }
        break;
    }

    AndroidBitmap_unlockPixels(env, outBmp);
    AndroidBitmap_unlockPixels(env, inBmp);
    return rc;
}

JNIEXPORT void JNICALL
Java_com_devfahim00_sdr2hdr_AiUpscaler_nativeSetCancel(JNIEnv*, jclass, jboolean cancel) {
    g_cancel.store(cancel == JNI_TRUE);
}

// Lets the caller start with a bigger (or smaller) tile once the real frame size is known —
// e.g. the whole frame in one tile needs no padding and no extra ncnn::Extractor calls at all.
// nativeUpscale still halves this on GPU-memory failure and falls back to CPU as before, so
// callers can safely pass a generous starting value.
JNIEXPORT void JNICALL
Java_com_devfahim00_sdr2hdr_AiUpscaler_nativeSetTile(JNIEnv*, jclass, jint tile) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_tile = std::max(kMinTile, (int)tile);
}

JNIEXPORT jboolean JNICALL
Java_com_devfahim00_sdr2hdr_AiUpscaler_nativeUsingGpu(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_net && g_gpu ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_devfahim00_sdr2hdr_AiUpscaler_nativeDevice(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_device.c_str());
}

JNIEXPORT void JNICALL
Java_com_devfahim00_sdr2hdr_AiUpscaler_nativeRelease(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    destroy_net_locked();
    g_mgr = nullptr;
    if (g_mgr_ref) {
        env->DeleteGlobalRef(g_mgr_ref);
        g_mgr_ref = nullptr;
    }
}

}  // extern "C"
