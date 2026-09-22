package com.devfahim00.sdr2hdr

import android.content.Context
import android.graphics.Bitmap

/**
 * On-device AI super-resolution: Real-ESRGAN (realesr-animevideov3 / SRVGGNetCompact) running on
 * ncnn. Uses the Vulkan GPU when the device has one and falls back to the CPU otherwise.
 * The native side lives in app/src/main/cpp.
 */
object AiUpscaler {

    /** Scale factors we ship a model for. */
    val SCALES = intArrayOf(2, 3, 4)

    /** Tile edge (input pixels) handed to the network. */
    private const val TILE_GPU = 256
    private const val TILE_CPU = 128

    /** False when the native library cannot be loaded (e.g. 32-bit only device). */
    val isSupported: Boolean by lazy {
        try {
            System.loadLibrary("sdr2hdr_sr")
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Result of [load]. */
    enum class Backend { GPU, CPU, FAILED }

    fun load(context: Context, scale: Int, useGpu: Boolean): Backend {
        if (!isSupported || scale !in SCALES) return Backend.FAILED
        val rc = nativeLoad(
            context.applicationContext.assets,
            "realesr-animevideov3-x$scale",
            scale,
            useGpu,
            if (useGpu) TILE_GPU else TILE_CPU
        )
        return when (rc) {
            0 -> Backend.GPU
            1 -> Backend.CPU
            else -> Backend.FAILED
        }
    }

    /** Both bitmaps must be ARGB_8888; [output] exactly scale x larger. 0 = ok, -2 = cancelled. */
    fun upscale(input: Bitmap, output: Bitmap): Int = nativeUpscale(input, output)

    fun setCancel(cancel: Boolean) {
        if (isSupported) nativeSetCancel(cancel)
    }

    fun usingGpu(): Boolean = isSupported && nativeUsingGpu()

    fun device(): String = if (isSupported) nativeDevice() else "n/a"

    fun release() {
        if (isSupported) nativeRelease()
    }

    @JvmStatic external fun nativeLoad(
        assets: android.content.res.AssetManager,
        model: String,
        scale: Int,
        useGpu: Boolean,
        tile: Int
    ): Int

    @JvmStatic external fun nativeUpscale(input: Bitmap, output: Bitmap): Int
    @JvmStatic external fun nativeSetCancel(cancel: Boolean)
    @JvmStatic external fun nativeUsingGpu(): Boolean
    @JvmStatic external fun nativeDevice(): String
    @JvmStatic external fun nativeRelease()
}
