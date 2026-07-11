cmake_minimum_required(VERSION 3.22.1)

project(drawless_fairy_android LANGUAGES CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "drawless_fairy is an Android-only native target")
endif()

set(DRAWLESS_ALLOWED_ABIS arm64-v8a x86_64)
if(NOT ANDROID_ABI IN_LIST DRAWLESS_ALLOWED_ABIS)
    message(FATAL_ERROR
        "Unsupported ABI '${ANDROID_ABI}'. Version 1 permits only arm64-v8a and x86_64.")
endif()

foreach(REQUIRED_VALUE
        FAIRY_SOURCE_DIR
        DRAWLESS_NATIVE_ROOT
        DRAWLESS_UPSTREAM_REVISION
        DRAWLESS_UPSTREAM_TREE
        DRAWLESS_PATCHED_TREE
        DRAWLESS_PATCH_SERIES_SHA256
        DRAWLESS_PATCH_VERSION
        DRAWLESS_BRIDGE_ABI_VERSION)
    if(NOT DEFINED ${REQUIRED_VALUE} OR "${${REQUIRED_VALUE}}" STREQUAL "")
        message(FATAL_ERROR "Missing required CMake value: ${REQUIRED_VALUE}")
    endif()
endforeach()

# AGP passes -D path values with native separators. Normalize them before any list
# expansion so Windows backslashes cannot be reparsed as CMake escape sequences.
cmake_path(SET FAIRY_SOURCE_DIR NORMALIZE "${FAIRY_SOURCE_DIR}")
cmake_path(SET DRAWLESS_NATIVE_ROOT NORMALIZE "${DRAWLESS_NATIVE_ROOT}")

if(NOT EXISTS "${FAIRY_SOURCE_DIR}/src/uci.cpp")
    message(FATAL_ERROR
        "Pinned Fairy-Stockfish source is absent. Run scripts/native-fetch-fairy.sh first.")
endif()

if(NOT EXISTS "${FAIRY_SOURCE_DIR}/.drawless-source-state.properties")
    message(FATAL_ERROR
        "Fairy source state is unverified. Recreate it with scripts/native-fetch-fairy.sh.")
endif()

find_package(Git REQUIRED)
execute_process(
    COMMAND "${GIT_EXECUTABLE}" -C "${FAIRY_SOURCE_DIR}" rev-parse HEAD
    RESULT_VARIABLE GIT_REVISION_RESULT
    OUTPUT_VARIABLE GIT_REVISION
    OUTPUT_STRIP_TRAILING_WHITESPACE
)
if(NOT GIT_REVISION_RESULT EQUAL 0 OR
   NOT "${GIT_REVISION}" STREQUAL "${DRAWLESS_UPSTREAM_REVISION}")
    message(FATAL_ERROR "Fairy checkout revision does not match the native lock")
endif()

execute_process(
    COMMAND "${GIT_EXECUTABLE}" -C "${FAIRY_SOURCE_DIR}" rev-parse "HEAD^{tree}"
    RESULT_VARIABLE GIT_TREE_RESULT
    OUTPUT_VARIABLE GIT_TREE
    OUTPUT_STRIP_TRAILING_WHITESPACE
)
if(NOT GIT_TREE_RESULT EQUAL 0 OR
   NOT "${GIT_TREE}" STREQUAL "${DRAWLESS_UPSTREAM_TREE}")
    message(FATAL_ERROR "Fairy upstream tree does not match the native lock")
endif()

execute_process(
    COMMAND "${GIT_EXECUTABLE}" -C "${FAIRY_SOURCE_DIR}" write-tree
    RESULT_VARIABLE GIT_PATCHED_TREE_RESULT
    OUTPUT_VARIABLE GIT_PATCHED_TREE
    OUTPUT_STRIP_TRAILING_WHITESPACE
)
if(NOT GIT_PATCHED_TREE_RESULT EQUAL 0 OR
   NOT "${GIT_PATCHED_TREE}" STREQUAL "${DRAWLESS_PATCHED_TREE}")
    message(FATAL_ERROR "Fairy patched tree does not match the native lock")
endif()

execute_process(
    COMMAND "${GIT_EXECUTABLE}" -C "${FAIRY_SOURCE_DIR}" diff --quiet
    RESULT_VARIABLE GIT_UNSTAGED_RESULT
)
if(NOT GIT_UNSTAGED_RESULT EQUAL 0)
    message(FATAL_ERROR "Fairy checkout contains unstaged source modifications")
endif()

set(DRAWLESS_SOURCE_STATE "${FAIRY_SOURCE_DIR}/.drawless-source-state.properties")
file(STRINGS "${DRAWLESS_SOURCE_STATE}" DRAWLESS_SOURCE_STATE_LINES)
foreach(EXPECTED_STATE_LINE
        "upstreamRevision=${DRAWLESS_UPSTREAM_REVISION}"
        "upstreamTree=${DRAWLESS_UPSTREAM_TREE}"
        "patchedTree=${DRAWLESS_PATCHED_TREE}"
        "patchVersion=${DRAWLESS_PATCH_VERSION}"
        "patchesApplied=true"
        "patchSeriesSha256=${DRAWLESS_PATCH_SERIES_SHA256}")
    list(FIND DRAWLESS_SOURCE_STATE_LINES "${EXPECTED_STATE_LINE}" STATE_LINE_INDEX)
    if(STATE_LINE_INDEX EQUAL -1)
        message(FATAL_ERROR "Fairy source-state mismatch: ${EXPECTED_STATE_LINE}")
    endif()
endforeach()

set(FAIRY_SOURCE_MANIFEST "${DRAWLESS_NATIVE_ROOT}/source-manifest.txt")
if(NOT EXISTS "${FAIRY_SOURCE_MANIFEST}")
    message(FATAL_ERROR "Missing Fairy source manifest: ${FAIRY_SOURCE_MANIFEST}")
endif()

file(STRINGS "${FAIRY_SOURCE_MANIFEST}" FAIRY_SOURCE_LINES)
set(FAIRY_SOURCES)
foreach(RELATIVE_SOURCE IN LISTS FAIRY_SOURCE_LINES)
    string(STRIP "${RELATIVE_SOURCE}" RELATIVE_SOURCE)
    if(RELATIVE_SOURCE STREQUAL "" OR RELATIVE_SOURCE MATCHES "^#")
        continue()
    endif()

    set(ABSOLUTE_SOURCE "${FAIRY_SOURCE_DIR}/src/${RELATIVE_SOURCE}")
    if(NOT EXISTS "${ABSOLUTE_SOURCE}")
        message(FATAL_ERROR "Pinned source manifest entry is missing: ${ABSOLUTE_SOURCE}")
    endif()
    list(APPEND FAIRY_SOURCES "${ABSOLUTE_SOURCE}")
endforeach()

add_library(drawless_fairy SHARED
    ${FAIRY_SOURCES}
    native_bridge.cpp
    native_identity.cpp
)

target_include_directories(drawless_fairy PRIVATE
    "${FAIRY_SOURCE_DIR}/src"
)

target_compile_features(drawless_fairy PRIVATE cxx_std_17)
target_compile_options(drawless_fairy PRIVATE
    -Wall
    -Wcast-qual
    -fno-exceptions
    -fno-strict-aliasing
)

target_compile_definitions(drawless_fairy PRIVATE
    IS_64BIT
    USE_PTHREADS
    NNUE_EMBEDDING_OFF
    DRAWLESS_UPSTREAM_REVISION="${DRAWLESS_UPSTREAM_REVISION}"
    DRAWLESS_UPSTREAM_TREE="${DRAWLESS_UPSTREAM_TREE}"
    DRAWLESS_PATCHED_TREE="${DRAWLESS_PATCHED_TREE}"
    DRAWLESS_PATCH_SERIES_SHA256="${DRAWLESS_PATCH_SERIES_SHA256}"
    DRAWLESS_PATCH_VERSION=${DRAWLESS_PATCH_VERSION}
    DRAWLESS_BRIDGE_ABI_VERSION=${DRAWLESS_BRIDGE_ABI_VERSION}
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(drawless_fairy PRIVATE USE_NEON USE_POPCNT)
elseif(ANDROID_ABI STREQUAL "x86_64")
    target_compile_definitions(drawless_fairy PRIVATE USE_SSE2 NO_PREFETCH)
    target_compile_options(drawless_fairy PRIVATE -msse2)
endif()

set_target_properties(drawless_fairy PROPERTIES
    CXX_EXTENSIONS OFF
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
    OUTPUT_NAME drawless_fairy
)

target_link_options(drawless_fairy PRIVATE -Wl,--no-gc-sections)
target_link_options(drawless_fairy PRIVATE
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/native_exports.map
)

find_library(ANDROID_LOG_LIBRARY log REQUIRED)
target_link_libraries(drawless_fairy PRIVATE
    ${ANDROID_LOG_LIBRARY}
)
