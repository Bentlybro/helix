cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build whisper.cpp as a static library (git submodule).
set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")

if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "whisper.cpp source is missing.\n"
        "Run: git submodule add https://github.com/ggerganov/whisper.cpp "
        "apps/helix-mic-android/app/src/main/cpp/whisper.cpp\n"
        "then: cd apps/helix-mic-android/app/src/main/cpp/whisper.cpp && git checkout v1.8.4"
    )
endif()

# Disable everything we don't need.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

# GPU: Vulkan disabled — Adreno driver crashes on ggml compute shaders.
# Re-enable when Qualcomm fixes their shader compiler.
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)


# CRITICAL: Force -O3 on whisper.cpp regardless of build type.
# Android Gradle passes -O0 for debug builds which destroys performance.
set(CMAKE_C_FLAGS_DEBUG   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)
add_compile_options(-O3 -DNDEBUG)

add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper_cpp_build EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.cpp)

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
