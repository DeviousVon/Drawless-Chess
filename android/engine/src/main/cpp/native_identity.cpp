#include <cstdint>

#if defined(__GNUC__)
#define DRAWLESS_EXPORT __attribute__((visibility("default")))
#else
#define DRAWLESS_EXPORT
#endif

#ifndef DRAWLESS_UPSTREAM_REVISION
#error "DRAWLESS_UPSTREAM_REVISION must be supplied by the pinned Android build"
#endif

#ifndef DRAWLESS_UPSTREAM_TREE
#error "DRAWLESS_UPSTREAM_TREE must be supplied by the pinned Android build"
#endif

#ifndef DRAWLESS_PATCH_VERSION
#error "DRAWLESS_PATCH_VERSION must be supplied by the pinned Android build"
#endif

#ifndef DRAWLESS_PATCHED_TREE
#error "DRAWLESS_PATCHED_TREE must be supplied by the pinned Android build"
#endif

#ifndef DRAWLESS_BRIDGE_ABI_VERSION
#error "DRAWLESS_BRIDGE_ABI_VERSION must be supplied by the pinned Android build"
#endif

#ifndef DRAWLESS_PATCH_SERIES_SHA256
#error "DRAWLESS_PATCH_SERIES_SHA256 must be supplied by the pinned Android build"
#endif

extern "C" {

DRAWLESS_EXPORT const char* drawless_fairy_upstream_revision() {
    return DRAWLESS_UPSTREAM_REVISION;
}

DRAWLESS_EXPORT const char* drawless_fairy_upstream_tree() {
    return DRAWLESS_UPSTREAM_TREE;
}

DRAWLESS_EXPORT const char* drawless_fairy_patched_tree() {
    return DRAWLESS_PATCHED_TREE;
}

DRAWLESS_EXPORT const char* drawless_fairy_patch_series_sha256() {
    return DRAWLESS_PATCH_SERIES_SHA256;
}

DRAWLESS_EXPORT std::int32_t drawless_fairy_patch_version() {
    return DRAWLESS_PATCH_VERSION;
}

DRAWLESS_EXPORT std::int32_t drawless_fairy_bridge_abi_version() {
    return DRAWLESS_BRIDGE_ABI_VERSION;
}

DRAWLESS_EXPORT const char* drawless_fairy_android_abi() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__x86_64__)
    return "x86_64";
#else
    return "unsupported";
#endif
}

}  // extern "C"
