/* Runtime statistics (D1). The whole file is empty unless built with -DMK_STATS.
 *
 * Counters are relaxed atomics so that threads can share them; the peak of the live set is
 * maintained with a CAS loop. The summary is printed to stderr at exit, one `mk-stats:` line per
 * group of key=value pairs (docs/benchmarks.md parses them). */
#include "internal.h"

#ifdef MK_STATS

#if defined(_MSC_VER) && !defined(__clang__)
/* no GNU atomics: plain counters (the statistics of a multi-threaded MSVC build may be off) */
#define STAT_ADD(p, n) (*(p) += (n))
#define STAT_SUB(p, n) (*(p) -= (n))
#define STAT_LOAD(p) (*(p))
#define STAT_STORE(p, v) (*(p) = (v))
#else
#define STAT_ADD(p, n) __atomic_fetch_add((p), (n), __ATOMIC_RELAXED)
#define STAT_SUB(p, n) __atomic_fetch_sub((p), (n), __ATOMIC_RELAXED)
#define STAT_LOAD(p) __atomic_load_n((p), __ATOMIC_RELAXED)
#define STAT_STORE(p, v) __atomic_store_n((p), (v), __ATOMIC_RELAXED)
#endif

static MkStats stats;
static int registered;

static void raise_peak(uint64_t *peak, uint64_t value) {
    uint64_t seen = STAT_LOAD(peak);
    while (value > seen) {
#if defined(_MSC_VER) && !defined(__clang__)
        *peak = value;
        return;
#else
        if (__atomic_compare_exchange_n(peak, &seen, value, 0, __ATOMIC_RELAXED, __ATOMIC_RELAXED)) return;
#endif
    }
}

static void register_dump(void) {
    int expected = 0;
#if defined(_MSC_VER) && !defined(__clang__)
    if (registered) return;
    registered = 1;
#else
    if (!__atomic_compare_exchange_n(&registered, &expected, 1, 0, __ATOMIC_RELAXED, __ATOMIC_RELAXED)) return;
#endif
    (void)expected;
    atexit(mk_stats_dump);
}

void mk_stats_on_alloc(size_t bytes) {
    if (!STAT_LOAD(&registered)) register_dump();
    STAT_ADD(&stats.allocs, 1);
    STAT_ADD(&stats.bytes_total, bytes);
    uint64_t live = STAT_ADD(&stats.live, 1) + 1;
    uint64_t blive = STAT_ADD(&stats.bytes_live, bytes) + bytes;
    raise_peak(&stats.peak_live, live);
    raise_peak(&stats.bytes_peak, blive);
}

void mk_stats_on_free(size_t bytes) {
    STAT_ADD(&stats.frees, 1);
    STAT_SUB(&stats.live, 1);
    STAT_SUB(&stats.bytes_live, bytes);
}

void mk_stats_on_free_n(size_t objects, size_t bytes) {
    STAT_ADD(&stats.frees, objects);
    STAT_SUB(&stats.live, objects);
    STAT_SUB(&stats.bytes_live, bytes);
}

void mk_stats_on_retain(int kind) {
    if (!STAT_LOAD(&registered)) register_dump();
    STAT_ADD(&stats.retains, 1);
    if (kind == 0) STAT_ADD(&stats.retains_noop, 1);
    else if (kind == 2) STAT_ADD(&stats.retains_atomic, 1);
}

void mk_stats_on_release(int kind) {
    if (!STAT_LOAD(&registered)) register_dump();
    STAT_ADD(&stats.releases, 1);
    if (kind == 0) STAT_ADD(&stats.releases_noop, 1);
    else if (kind == 2) STAT_ADD(&stats.releases_atomic, 1);
}

void mk_stats_get(MkStats *out) {
    if (out == NULL) return;
    out->allocs = STAT_LOAD(&stats.allocs);
    out->frees = STAT_LOAD(&stats.frees);
    out->live = STAT_LOAD(&stats.live);
    out->peak_live = STAT_LOAD(&stats.peak_live);
    out->bytes_total = STAT_LOAD(&stats.bytes_total);
    out->bytes_live = STAT_LOAD(&stats.bytes_live);
    out->bytes_peak = STAT_LOAD(&stats.bytes_peak);
    out->retains = STAT_LOAD(&stats.retains);
    out->retains_atomic = STAT_LOAD(&stats.retains_atomic);
    out->retains_noop = STAT_LOAD(&stats.retains_noop);
    out->releases = STAT_LOAD(&stats.releases);
    out->releases_atomic = STAT_LOAD(&stats.releases_atomic);
    out->releases_noop = STAT_LOAD(&stats.releases_noop);
}

void mk_stats_reset(void) {
    STAT_STORE(&stats.allocs, 0);
    STAT_STORE(&stats.frees, 0);
    STAT_STORE(&stats.bytes_total, 0);
    STAT_STORE(&stats.peak_live, STAT_LOAD(&stats.live));
    STAT_STORE(&stats.bytes_peak, STAT_LOAD(&stats.bytes_live));
    STAT_STORE(&stats.retains, 0);
    STAT_STORE(&stats.retains_atomic, 0);
    STAT_STORE(&stats.retains_noop, 0);
    STAT_STORE(&stats.releases, 0);
    STAT_STORE(&stats.releases_atomic, 0);
    STAT_STORE(&stats.releases_noop, 0);
}

void mk_stats_dump(void) {
    MkStats s;
    mk_stats_get(&s);
    unsigned long long plain_r = (unsigned long long)(s.retains - s.retains_atomic - s.retains_noop);
    unsigned long long plain_l = (unsigned long long)(s.releases - s.releases_atomic - s.releases_noop);
    fprintf(stderr, "mk-stats: allocs=%llu frees=%llu live_at_exit=%llu peak_live=%llu\n",
            (unsigned long long)s.allocs, (unsigned long long)s.frees, (unsigned long long)s.live,
            (unsigned long long)s.peak_live);
    fprintf(stderr, "mk-stats: bytes_total=%llu bytes_live_at_exit=%llu bytes_peak=%llu\n",
            (unsigned long long)s.bytes_total, (unsigned long long)s.bytes_live,
            (unsigned long long)s.bytes_peak);
    fprintf(stderr,
            "mk-stats: retains=%llu retains_nonatomic=%llu retains_atomic=%llu retains_noop=%llu\n",
            (unsigned long long)s.retains, plain_r, (unsigned long long)s.retains_atomic,
            (unsigned long long)s.retains_noop);
    fprintf(stderr,
            "mk-stats: releases=%llu releases_nonatomic=%llu releases_atomic=%llu releases_noop=%llu\n",
            (unsigned long long)s.releases, plain_l, (unsigned long long)s.releases_atomic,
            (unsigned long long)s.releases_noop);
}

#endif /* MK_STATS */

/* keep the translation unit non-empty for strict ISO C */
typedef int mk_stats_translation_unit_not_empty;
