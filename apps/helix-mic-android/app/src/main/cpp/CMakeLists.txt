cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build whisper.cpp as a static library.
# The source lives under whisper.cpp/ as a git submodule (or vendored copy).
set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")

if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "whisper.cpp source is missing.\n"
        "Run: git submodule add https://github.com/ggerganov/whisper.cpp "
        "apps/helix-mic-android/app/src/main/cpp/whisper.cpp\n"
        "then: cd apps/helix-mic-android/app/src/main/cpp/whisper.cpp && git checkout v1.7.2"
    )
endif()

# Disable everything we don't need from whisper.cpp.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

# CRITICAL: Android Gradle passes -O0 -g for debug builds, which destroys
# whisper/ggml performance (we saw 60s for 3s of audio). Force -O3 -DNDEBUG
# on whisper.cpp sources regardless of top-level build type. Our own JNI
# shim is small so the release flags on it are fine too.
set(CMAKE_C_FLAGS_DEBUG   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)
add_compile_options(-O3 -DNDEBUG)

# NEON is always present on arm64-v8a — whisper/ggml will pick it up.
add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper_cpp_build EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED
    whisper_jni.cpp
)

target_link_libraries(whisper_jni
    PRIVATE
    whisper
    log
    android
)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_DIR}
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)
