// C wrapper around Apple's reference ALAC decoder (Apache-2.0).
// SPDX-License-Identifier: GPL-3.0-or-later
#include <new>
#include <cstdint>
#include <cstring>

#include "ALACDecoder.h"
#include "ALACBitUtilities.h"
#include "alac_bridge.h"

namespace {
// AirPlay ALAC magic cookie for 44100 Hz / 16 bit / stereo, 352 frames per packet
// (identical to the one UxPlay passes to GStreamer).
const uint8_t kAirPlayCookie[] = {
    0x00, 0x00, 0x00, 0x24, 'a', 'l', 'a', 'c', 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x01, 0x60, 0x00, 0x10, 0x28, 0x0a, 0x0e, 0x02, 0x00, 0xff,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xac, 0x44,
};
}  // namespace

struct alac_bridge_s {
    ALACDecoder decoder;
    uint32_t frames_per_packet;
    uint32_t channels;
};

extern "C" alac_bridge_t *alac_bridge_create(void) {
    auto *b = new (std::nothrow) alac_bridge_s();
    if (!b) return nullptr;
    uint8_t cookie[sizeof(kAirPlayCookie)];
    memcpy(cookie, kAirPlayCookie, sizeof(cookie));
    if (b->decoder.Init(cookie, sizeof(cookie)) != 0) {
        delete b;
        return nullptr;
    }
    b->frames_per_packet = b->decoder.mConfig.frameLength;
    b->channels = b->decoder.mConfig.numChannels;
    return b;
}

extern "C" void alac_bridge_destroy(alac_bridge_t *b) {
    delete b;
}

extern "C" int alac_bridge_decode(alac_bridge_t *b, const uint8_t *in, int in_len,
                                  int16_t *out, int out_capacity_samples) {
    if (!b || !in || in_len <= 0 || !out) return -1;
    const uint32_t needed = b->frames_per_packet * b->channels;
    if (out_capacity_samples < static_cast<int>(needed)) return -1;
    // BitBuffer takes a non-const pointer but only reads from it.
    BitBuffer bits;
    BitBufferInit(&bits, const_cast<uint8_t *>(in), static_cast<uint32_t>(in_len));
    uint32_t frames = 0;
    int32_t status = b->decoder.Decode(&bits, reinterpret_cast<uint8_t *>(out),
                                       b->frames_per_packet, b->channels, &frames);
    if (status != 0 || frames > b->frames_per_packet) return -1;
    return static_cast<int>(frames * b->channels);
}
