// Platform independent tiled super-resolution on top of ncnn.
//
// Works with the Real-ESRGAN "realesr-animevideov3" family (SRVGGNetCompact): the network
// takes an RGB float image in [0,1] on blob "data" and returns `scale` times larger RGB on
// blob "output". Big images are processed tile by tile with a few pixels of context around
// every tile so no seams show up.
#pragma once

#include <atomic>
#include <cstdint>

#include "net.h"

namespace sr {

enum Result {
    OK = 0,
    ERR_ARGS = -1,       // bad sizes / null pointers
    ERR_CANCELLED = -2,  // cancel flag was raised
    ERR_INFER = -3       // ncnn failed (typically GPU out of memory)
};

struct Params {
    int scale = 2;   // model scale factor (output = input * scale)
    int tile = 200;  // tile edge in input pixels (without padding)
    int prepad = 10; // context pixels added around every tile
};

// RGBA8888 in -> RGBA8888 out. `dst` must hold (w*scale) x (h*scale) pixels; alpha is set to 255.
int upscale_rgba(const ncnn::Net& net, const Params& p,
                 const uint8_t* src, int w, int h, int src_stride,
                 uint8_t* dst, int dst_w, int dst_h, int dst_stride,
                 const std::atomic<bool>& cancel);

}  // namespace sr
