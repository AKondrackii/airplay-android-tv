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

// The reference decoder only checks the bit position once per sample and then reads a
// few bytes past it (up to ~41 bits plus a 32-bit look-ahead). Decoding straight from the
// RTP buffer crashed whenever a packet ended at a page boundary, so packets are copied
// into a buffer with zeroed slack behind them.
constexpr int kMaxPacket = 16 * 1024;
constexpr int kSlack = 256;

struct alac_bridge_s {
    ALACDecoder decoder;
    uint32_t frames_per_packet;
    uint32_t channels;
    uint8_t in[kMaxPacket + kSlack];
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
    if (in_len > kMaxPacket) return -1;
    memcpy(b->in, in, static_cast<size_t>(in_len));
    memset(b->in + in_len, 0, kSlack);
    BitBuffer bits;
    BitBufferInit(&bits, b->in, static_cast<uint32_t>(in_len));
    uint32_t frames = 0;
    int32_t status = b->decoder.Decode(&bits, reinterpret_cast<uint8_t *>(out),
                                       b->frames_per_packet, b->channels, &frames);
    if (status != 0 || frames > b->frames_per_packet) return -1;
    return static_cast<int>(frames * b->channels);
}
