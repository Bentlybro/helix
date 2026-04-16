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

# GPU acceleration via Vulkan.
# Pre-generated shaders avoid cross-compilation issues with vulkan-shaders-gen.
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_VULKAN_SHADERS_GEN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/generated-vulkan-shaders" CACHE STRING "" FORCE)

# Android NDK provides libvulkan.so at runtime but no CMake package.
# Provide Vulkan::Vulkan target and standalone Vulkan-Headers (v1.3.268)
# so ggml can find everything it needs.
if(ANDROID)
    set(Vulkan_FOUND TRUE)
    set(VULKAN_FOUND TRUE)
    set(_vk_headers "${CMAKE_CURRENT_SOURCE_DIR}/vulkan-headers/include")
    if(NOT TARGET Vulkan::Vulkan)
        add_library(Vulkan::Vulkan INTERFACE IMPORTED)
        set_target_properties(Vulkan::Vulkan PROPERTIES
            INTERFACE_LINK_LIBRARIES "vulkan"
            INTERFACE_INCLUDE_DIRECTORIES "${_vk_headers}"
        )
    endif()
    include_directories(BEFORE SYSTEM "${_vk_headers}")
    # glslc from NDK for shader compilation and extension probing.
    set(Vulkan_GLSLC_EXECUTABLE
        "${ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe"
        CACHE STRING "" FORCE)
endif()

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
