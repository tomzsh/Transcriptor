cmake_minimum_required(VERSION 3.22.1)
project(transcriptor_whisper LANGUAGES C CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build Whisper and GGML as static libraries" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan GPU inference" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)

# Vulkan is provided by Android at runtime. Use the NDK import library for
# linking, NDK's glslc for shader compilation, and Vulkan-Hpp headers for the
# C++ wrapper used by ggml-vulkan. NDK r28 ships the C headers and SPIR-V
# headers, but not Vulkan-Hpp itself.
if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(TRANCRIPTOR_NDK_HOST_TAG windows-x86_64)
    set(TRANCRIPTOR_GLSLC_NAME glslc.exe)
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(TRANCRIPTOR_NDK_HOST_TAG darwin-x86_64)
    set(TRANCRIPTOR_GLSLC_NAME glslc)
else()
    set(TRANCRIPTOR_NDK_HOST_TAG linux-x86_64)
    set(TRANCRIPTOR_GLSLC_NAME glslc)
endif()

set(TRANCRIPTOR_ANDROID_SYSROOT "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${TRANCRIPTOR_NDK_HOST_TAG}/sysroot")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(TRANCRIPTOR_ANDROID_LIB_TRIPLE aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(TRANCRIPTOR_ANDROID_LIB_TRIPLE x86_64-linux-android)
else()
    message(FATAL_ERROR "Vulkan backend does not support Android ABI ${ANDROID_ABI}")
endif()
set(TRANCRIPTOR_VULKAN_LOADER "${TRANCRIPTOR_ANDROID_SYSROOT}/usr/lib/${TRANCRIPTOR_ANDROID_LIB_TRIPLE}/24/libvulkan.so")
set(TRANCRIPTOR_GLSLC "${CMAKE_ANDROID_NDK}/shader-tools/${TRANCRIPTOR_NDK_HOST_TAG}/${TRANCRIPTOR_GLSLC_NAME}")
set(TRANCRIPTOR_SPIRV_INCLUDE "${CMAKE_ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include")

if(NOT EXISTS "${TRANCRIPTOR_SPIRV_INCLUDE}/spirv/unified1/spirv.hpp")
    message(FATAL_ERROR "The Android NDK is missing its SPIRV-Headers include directory")
endif()
if(NOT EXISTS "${TRANCRIPTOR_VULKAN_LOADER}" OR NOT EXISTS "${TRANCRIPTOR_GLSLC}")
    message(FATAL_ERROR "The Android NDK is missing the Vulkan loader stub or glslc shader compiler")
endif()

FetchContent_Declare(
    vulkan_headers
    URL https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.3.275.tar.gz
    URL_HASH SHA256=7161da645dbd33fd4ea61eec08e0d77389a640010acbf4afc00234f84df9b314
)
# A local source override also lets developers build with an already-fetched,
# hash-verified archive when their shell blocks network access.
if(DEFINED ENV{TRANCRIPTOR_VULKAN_HEADERS_SOURCE_DIR} AND
   IS_DIRECTORY "$ENV{TRANCRIPTOR_VULKAN_HEADERS_SOURCE_DIR}")
    set(FETCHCONTENT_SOURCE_DIR_VULKAN_HEADERS "$ENV{TRANCRIPTOR_VULKAN_HEADERS_SOURCE_DIR}")
endif()
FetchContent_GetProperties(vulkan_headers)
if(NOT vulkan_headers_POPULATED)
    FetchContent_Populate(vulkan_headers)
endif()
set(TRANCRIPTOR_VULKAN_HEADERS_INCLUDE "${vulkan_headers_SOURCE_DIR}/include")

if(NOT EXISTS "${TRANCRIPTOR_VULKAN_HEADERS_INCLUDE}/vulkan/vulkan.hpp")
    message(FATAL_ERROR "Pinned Vulkan-Headers archive does not contain Vulkan-Hpp")
endif()
set(TRANCRIPTOR_VULKAN_CPP_INCLUDE "${TRANCRIPTOR_VULKAN_HEADERS_INCLUDE}")
# The NDK C headers are version 1.3.275; use matching pinned C++ and C headers,
# never the host's potentially newer /usr/include/vulkan tree.
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -I${TRANCRIPTOR_VULKAN_CPP_INCLUDE}")

set(Vulkan_INCLUDE_DIR "${TRANCRIPTOR_VULKAN_HEADERS_INCLUDE}" CACHE PATH "Android Vulkan headers" FORCE)
set(Vulkan_LIBRARY "${TRANCRIPTOR_VULKAN_LOADER}" CACHE FILEPATH "Android Vulkan loader stub" FORCE)
set(Vulkan_GLSLC_EXECUTABLE "${TRANCRIPTOR_GLSLC}" CACHE FILEPATH "Android NDK glslc" FORCE)
find_package(Vulkan COMPONENTS glslc REQUIRED)
# Cross-compilation's system include filters can drop Vulkan-Hpp from the
# imported loader target, so the isolated include root is also in CXX flags.

# ggml-vulkan currently calls vkGetPhysicalDeviceFeatures2 through the C
# loader symbol. Android only exports that symbol from API 28, while this app
# supports API 26. Route those calls through Vulkan-Hpp's dynamic dispatcher
# (initialized from vkGetInstanceProcAddr) so the shared libraries do not gain
# an API-28 symbol dependency and can still fall back cleanly on older devices.
function(transcriptor_patch_dynamic_features2 source_dir)
    set(vulkan_source "${source_dir}/ggml/src/ggml-vulkan/ggml-vulkan.cpp")
    if(NOT EXISTS "${vulkan_source}")
        message(FATAL_ERROR "Expected ggml Vulkan source was not found: ${vulkan_source}")
    endif()

    file(READ "${vulkan_source}" vulkan_code)
    set(vulkan_original "${vulkan_code}")
    string(REGEX MATCHALL "(^|[^.A-Za-z0-9_])vkGetPhysicalDeviceFeatures2\\(" direct_features2_calls "${vulkan_code}")
    list(LENGTH direct_features2_calls direct_features2_count)
    if(NOT direct_features2_count EQUAL 0 AND NOT direct_features2_count EQUAL 3)
        message(FATAL_ERROR "Pinned ggml Vulkan source changed: expected 3 direct vkGetPhysicalDeviceFeatures2 calls, found ${direct_features2_count}")
    endif()

    if(direct_features2_count EQUAL 3)
        string(REPLACE
            "vkGetPhysicalDeviceFeatures2(device->physical_device, &device_features2);"
            "VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceFeatures2(device->physical_device, &device_features2);"
            vulkan_code "${vulkan_code}")
        string(REPLACE
            "vkGetPhysicalDeviceFeatures2(physical_device, &device_features2);"
            "VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceFeatures2(physical_device, &device_features2);"
            vulkan_code "${vulkan_code}")
        string(REPLACE
            "vkGetPhysicalDeviceFeatures2(vkdev, &device_features2);"
            "VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceFeatures2(vkdev, &device_features2);"
            vulkan_code "${vulkan_code}")
    endif()
    # Upgrade an intermediate build tree if it already received the earlier
    # wrapper-method spelling during development.
    string(REPLACE
        "device->physical_device.getFeatures2(&device_features2);"
        "VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceFeatures2(device->physical_device, &device_features2);"
        vulkan_code "${vulkan_code}")
    string(REPLACE
        "physical_device.getFeatures2(&device_features2);"
        "VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceFeatures2(physical_device, &device_features2);"
        vulkan_code "${vulkan_code}")
    string(REPLACE
        "vkdev.getFeatures2(&device_features2);"
        "VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceFeatures2(vkdev, &device_features2);"
        vulkan_code "${vulkan_code}")

    if(vulkan_code MATCHES "(^|[^.A-Za-z0-9_])vkGetPhysicalDeviceFeatures2\\(")
        message(FATAL_ERROR "Failed to route all vkGetPhysicalDeviceFeatures2 calls through Vulkan-Hpp")
    endif()
    if(NOT vulkan_code STREQUAL vulkan_original)
        file(WRITE "${vulkan_source}" "${vulkan_code}")
    endif()
endfunction()

# ggml's Vulkan CMake currently uses the SPIRV-Headers package config only to
# add the include directory. Supply this config from headers already shipped
# in the selected NDK, also to llama.cpp's isolated CMake build.
set(TRANCRIPTOR_SPIRV_CMAKE_DIR "${CMAKE_CURRENT_BINARY_DIR}/spirv-headers-cmake")
file(MAKE_DIRECTORY "${TRANCRIPTOR_SPIRV_CMAKE_DIR}")
file(WRITE "${TRANCRIPTOR_SPIRV_CMAKE_DIR}/SPIRV-HeadersConfig.cmake"
    "set(SPIRV-Headers_FOUND TRUE)\nset(SPIRV-Headers_INCLUDE_DIRS \"${TRANCRIPTOR_SPIRV_INCLUDE}\")\ninclude_directories(BEFORE \"${TRANCRIPTOR_SPIRV_INCLUDE}\")\n"
)
set(SPIRV-Headers_DIR "${TRANCRIPTOR_SPIRV_CMAKE_DIR}" CACHE PATH "SPIRV-Headers config" FORCE)

# Keep local inference usable in debug builds without changing Android packaging flags.
add_compile_options("$<$<CONFIG:Debug>:-O2>" "$<$<CONFIG:Release>:-O3>")

FetchContent_Declare(
    whisper_cpp
    URL https://github.com/ggml-org/whisper.cpp/archive/d1be6fde11ac6e0407606b4e42fe72d34add8037.tar.gz
    URL_HASH SHA256=645445da884df6dbd52b30f82d7be7c01d1767c938714e477e786b88d529e9f4
)
FetchContent_GetProperties(whisper_cpp)
if(NOT whisper_cpp_POPULATED)
    FetchContent_Populate(whisper_cpp)
endif()
transcriptor_patch_dynamic_features2("${whisper_cpp_SOURCE_DIR}")
add_subdirectory("${whisper_cpp_SOURCE_DIR}" "${whisper_cpp_BINARY_DIR}")

add_library(transcriptor_whisper SHARED whisper_jni.cpp)
target_compile_features(transcriptor_whisper PRIVATE cxx_std_17)
target_compile_options(transcriptor_whisper PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
find_library(ANDROID_LOG_LIBRARY log)
target_link_libraries(transcriptor_whisper PRIVATE whisper Vulkan::Vulkan ${ANDROID_LOG_LIBRARY})
target_link_options(transcriptor_whisper PRIVATE -Wl,-z,max-page-size=16384 -Wl,--strip-debug)

# Whisper and llama.cpp each vendor a different ggml revision. Build llama.cpp in
# its own CMake project, then hide archive symbols in both JNI DSOs. Sharing CMake
# ggml targets OR exporting their symbols would mix incompatible implementations.
include(ExternalProject)
FetchContent_Declare(
    llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/79bce0c8812ddfe4716922d061b67edc2ed517e9.tar.gz
    URL_HASH SHA256=f5fc282b77bf8f9a063cc441481b7e2761621786656382ef57b0a6c30c961e25
)
FetchContent_GetProperties(llama_cpp)
if(NOT llama_cpp_POPULATED)
    FetchContent_Populate(llama_cpp)
endif()
transcriptor_patch_dynamic_features2("${llama_cpp_SOURCE_DIR}")

set(LLAMA_BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/llama-build")
set(LLAMA_ARCHIVES
    "${LLAMA_BINARY_DIR}/src/libllama.a"
    "${LLAMA_BINARY_DIR}/ggml/src/libggml.a"
    "${LLAMA_BINARY_DIR}/ggml/src/libggml-cpu.a"
    "${LLAMA_BINARY_DIR}/ggml/src/libggml-base.a"
    "${LLAMA_BINARY_DIR}/ggml/src/ggml-vulkan/libggml-vulkan.a"
)
ExternalProject_Add(transcriptor_llama_build
    SOURCE_DIR "${llama_cpp_SOURCE_DIR}"
    BINARY_DIR "${LLAMA_BINARY_DIR}"
    CMAKE_ARGS
        "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
        "-DANDROID_ABI=${ANDROID_ABI}"
        "-DANDROID_PLATFORM=${ANDROID_PLATFORM}"
        "-DANDROID_STL=${ANDROID_STL}"
        "-DVulkan_INCLUDE_DIR=${TRANCRIPTOR_VULKAN_HEADERS_INCLUDE}"
        "-DCMAKE_CXX_FLAGS=-I${TRANCRIPTOR_VULKAN_CPP_INCLUDE}"
        "-DVulkan_LIBRARY=${TRANCRIPTOR_VULKAN_LOADER}"
        "-DVulkan_GLSLC_EXECUTABLE=${TRANCRIPTOR_GLSLC}"
        "-DSPIRV-Headers_DIR=${TRANCRIPTOR_SPIRV_CMAKE_DIR}"
        "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DBUILD_SHARED_LIBS=OFF
        -DLLAMA_BUILD_IS_DEV=OFF
        -DLLAMA_BUILD_COMMON=OFF
        -DLLAMA_BUILD_TESTS=OFF
        -DLLAMA_BUILD_TOOLS=OFF
        -DLLAMA_BUILD_EXAMPLES=OFF
        -DLLAMA_BUILD_SERVER=OFF
        -DLLAMA_BUILD_APP=OFF
        -DLLAMA_BUILD_MTMD=OFF
        -DLLAMA_OPENSSL=OFF
        -DGGML_NATIVE=OFF
        -DGGML_OPENMP=OFF
        -DGGML_BACKEND_DL=OFF
        -DGGML_CPU=ON
        -DGGML_CPU_KLEIDIAI=OFF
        -DGGML_CUDA=OFF
        -DGGML_HIP=OFF
        -DGGML_VULKAN=ON
        -DGGML_OPENCL=OFF
        -DGGML_BLAS=OFF
        -DGGML_LLAMAFILE=OFF
        -DGGML_AVX=OFF
        -DGGML_AVX2=OFF
        -DGGML_FMA=OFF
        -DGGML_F16C=OFF
        -DGGML_AVX512=OFF
    BUILD_COMMAND "${CMAKE_COMMAND}" --build <BINARY_DIR> --target llama --parallel 2
    BUILD_BYPRODUCTS ${LLAMA_ARCHIVES}
    INSTALL_COMMAND ""
)

add_library(transcriptor_llama SHARED llama_jni.cpp)
add_dependencies(transcriptor_llama transcriptor_llama_build)
target_compile_features(transcriptor_llama PRIVATE cxx_std_17)
target_compile_options(transcriptor_llama PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_include_directories(transcriptor_llama PRIVATE "${llama_cpp_SOURCE_DIR}/include" "${llama_cpp_SOURCE_DIR}/ggml/include")
target_link_libraries(transcriptor_llama PRIVATE "-Wl,--start-group" ${LLAMA_ARCHIVES} "-Wl,--end-group" Vulkan::Vulkan ${ANDROID_LOG_LIBRARY} android dl m)
target_link_options(transcriptor_llama PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,--strip-debug)
target_link_options(transcriptor_whisper PRIVATE -Wl,--exclude-libs,ALL)
