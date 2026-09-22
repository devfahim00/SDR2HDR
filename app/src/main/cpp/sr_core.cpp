#include "sr_core.h"

#include <algorithm>

namespace sr {

static inline uint8_t to_u8(float v) {
    v = v * 255.f + 0.5f;
    if (v < 0.f) return 0;
    if (v > 255.f) return 255;
    return (uint8_t)v;
}

int upscale_rgba(const ncnn::Net& net, const Params& p,
                 const uint8_t* src, int w, int h, int src_stride,
                 uint8_t* dst, int dst_w, int dst_h, int dst_stride,
                 const std::atomic<bool>& cancel) {
    const int s = p.scale;
    if (!src || !dst || w <= 0 || h <= 0 || s < 1 || s > 8) return ERR_ARGS;
    if (dst_w != w * s || dst_h != h * s) return ERR_ARGS;
    if (src_stride < w * 4 || dst_stride < dst_w * 4) return ERR_ARGS;

    const int tile = std::max(16, p.tile);
    const int pad = std::max(0, p.prepad);
    const float norm[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};

    for (int cy0 = 0; cy0 < h; cy0 += tile) {
        const int cy1 = std::min(cy0 + tile, h);
        const int ry0 = std::max(cy0 - pad, 0);
        const int ry1 = std::min(cy1 + pad, h);

        for (int cx0 = 0; cx0 < w; cx0 += tile) {
            if (cancel.load(std::memory_order_relaxed)) return ERR_CANCELLED;

            const int cx1 = std::min(cx0 + tile, w);
            const int rx0 = std::max(cx0 - pad, 0);
            const int rx1 = std::min(cx1 + pad, w);

            // Tile plus context. Where the context would leave the image it is simply
            // clipped: the convolutions then zero-pad exactly like a whole-image pass.
            ncnn::Mat in = ncnn::Mat::from_pixels_roi(src, ncnn::Mat::PIXEL_RGBA2RGB, w, h, src_stride,
                                                     rx0, ry0, rx1 - rx0, ry1 - ry0);
            if (in.empty()) return ERR_INFER;
            in.substract_mean_normalize(0, norm);

            ncnn::Mat out;
            {
                ncnn::Extractor ex = net.create_extractor();
                if (ex.input("data", in) != 0) return ERR_INFER;
                if (ex.extract("output", out) != 0) return ERR_INFER;
            }
            if (out.c != 3 || out.w != (rx1 - rx0) * s || out.h != (ry1 - ry0) * s) return ERR_INFER;

            // Copy the tile core (without the context border) into the destination.
            const int ox = (cx0 - rx0) * s;  // core offset inside `out`
            const int oy = (cy0 - ry0) * s;
            const int cw = (cx1 - cx0) * s;
            const int ch = (cy1 - cy0) * s;
            const int dx = cx0 * s;
            const int dy = cy0 * s;

            const float* c0 = out.channel(0);
            const float* c1 = out.channel(1);
            const float* c2 = out.channel(2);
            const int ow = out.w;
            for (int y = 0; y < ch; y++) {
                const float* r0 = c0 + (size_t)(oy + y) * ow + ox;
                const float* r1 = c1 + (size_t)(oy + y) * ow + ox;
                const float* r2 = c2 + (size_t)(oy + y) * ow + ox;
                uint8_t* d = dst + (size_t)(dy + y) * dst_stride + (size_t)dx * 4;
                for (int x = 0; x < cw; x++) {
                    d[0] = to_u8(r0[x]);
                    d[1] = to_u8(r1[x]);
                    d[2] = to_u8(r2[x]);
                    d[3] = 255;
                    d += 4;
                }
            }
        }
    }
    return OK;
}

}  // namespace sr
