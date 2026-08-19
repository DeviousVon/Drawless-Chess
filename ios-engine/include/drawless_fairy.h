#ifndef DRAWLESS_FAIRY_H
#define DRAWLESS_FAIRY_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef uint64_t drawless_fairy_handle;
typedef void (*drawless_fairy_timeout_callback)(void* context, int32_t fired);

drawless_fairy_handle drawless_fairy_create(
    const char* variant_config_path,
    char* error_buffer,
    size_t error_capacity);

int32_t drawless_fairy_start(
    drawless_fairy_handle handle,
    char* error_buffer,
    size_t error_capacity);

int64_t drawless_fairy_write(
    drawless_fairy_handle handle,
    const uint8_t* bytes,
    size_t length,
    char* error_buffer,
    size_t error_capacity);

/* Returns -1 at clean EOF and -2 on a transport error. */
int64_t drawless_fairy_read(
    drawless_fairy_handle handle,
    uint8_t* bytes,
    size_t length,
    char* error_buffer,
    size_t error_capacity);

int64_t drawless_fairy_read_error(
    drawless_fairy_handle handle,
    uint8_t* bytes,
    size_t length,
    char* error_buffer,
    size_t error_capacity);

int32_t drawless_fairy_close(
    drawless_fairy_handle handle,
    char* error_buffer,
    size_t error_capacity);

/*
 * A cancellable native timer used by the Kotlin UCI controller. Cancellation
 * wakes the timer and invokes the callback with fired == 0 so its Kotlin
 * StableRef can be released promptly. drain waits until every callback exits.
 */
uint64_t drawless_fairy_timeout_schedule(
    uint64_t delay_millis,
    drawless_fairy_timeout_callback callback,
    void* context);
int32_t drawless_fairy_timeout_cancel(uint64_t timeout_handle);
void drawless_fairy_timeout_drain(void);

const char* drawless_fairy_upstream_revision(void);
const char* drawless_fairy_upstream_tree(void);
const char* drawless_fairy_patched_tree(void);
const char* drawless_fairy_patch_series_sha256(void);
int32_t drawless_fairy_patch_version(void);
int32_t drawless_fairy_bridge_abi_version(void);
const char* drawless_fairy_apple_abi(void);

#ifdef __cplusplus
}
#endif

#endif
