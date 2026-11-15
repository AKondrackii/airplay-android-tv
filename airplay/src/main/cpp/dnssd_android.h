/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef DNSSD_ANDROID_H
#define DNSSD_ANDROID_H

#include "third_party/uxplay/dnssd.h"

#ifdef __cplusplus
extern "C" {
#endif

#define DNSSD_ANDROID_MAX_TXT 32

typedef struct dnssd_android_service_s {
    const char *name;
    const char *type;
    unsigned short port;
    int count;
    const char *keys[DNSSD_ANDROID_MAX_TXT];
    const char *values[DNSSD_ANDROID_MAX_TXT];
} dnssd_android_service_t;

/* Returns 0 and fills `out` if the service (DNSSD_SERVICE_RAOP / _AIRPLAY) was registered. */
int dnssd_android_get_service(dnssd_t *d, int service, dnssd_android_service_t *out);

#ifdef __cplusplus
}
#endif
#endif
