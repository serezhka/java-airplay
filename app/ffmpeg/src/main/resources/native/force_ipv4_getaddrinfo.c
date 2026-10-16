/*
 * LD_PRELOAD helper: force getaddrinfo to request AF_INET only.
 *
 * Some CDN edges reset IPv6 (AAAA) connections from this host. Segment URLs then
 * fail while the playback clock keeps moving. Resolving those hosts as IPv4
 * reaches an edge that answers.
 *
 * Build: cc -shared -fPIC -o libforce_ipv4_getaddrinfo.so force_ipv4_getaddrinfo.c -ldl
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <netdb.h>
#include <string.h>
#include <stdlib.h>

typedef int (*getaddrinfo_fn)(const char *, const char *, const struct addrinfo *, struct addrinfo **);

int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints, struct addrinfo **res) {
    static getaddrinfo_fn real_getaddrinfo = NULL;
    if (!real_getaddrinfo) {
        real_getaddrinfo = (getaddrinfo_fn) dlsym(RTLD_NEXT, "getaddrinfo");
    }
    if (!real_getaddrinfo) {
        return EAI_SYSTEM;
    }

    /* Opt out: AIRPLAY_ALLOW_IPV6=1 keeps default dual-stack resolution. */
    const char *allow = getenv("AIRPLAY_ALLOW_IPV6");
    if (allow != NULL && allow[0] != '\0' && strcmp(allow, "0") != 0) {
        return real_getaddrinfo(node, service, hints, res);
    }

    struct addrinfo forced;
    memset(&forced, 0, sizeof(forced));
    if (hints != NULL) {
        forced = *hints;
    }
    forced.ai_family = AF_INET;
    return real_getaddrinfo(node, service, &forced, res);
}
