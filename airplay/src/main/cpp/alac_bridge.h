/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef ALAC_BRIDGE_H
#define ALAC_BRIDGE_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct alac_bridge_s alac_bridge_t;
alac_bridge_t *alac_bridge_create(void);
void alac_bridge_destroy(alac_bridge_t *b);
/* Decodes one ALAC packet into interleaved 16-bit PCM. Returns samples written (frames*channels) or -1. */
int alac_bridge_decode(alac_bridge_t *b, const uint8_t *in, int in_len, int16_t *out, int out_capacity_samples);
#ifdef __cplusplus
}
#endif
#endif
