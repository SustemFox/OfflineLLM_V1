cmake_minimum_required(VERSION 3.22.1)
project(offlinellm_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

set(OFFLINELLM_LLAMA_TAG "b10079" CACHE STRING "llama.cpp git tag/branch")
option(OFFLINELLM_ENABLE_OPENCL "Build llama.cpp with GGML_OPENCL" ON)
option(OFFLINELLM_ENABLE_VULKAN "Build llama.cpp with GGML_VULKAN" ON)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp)
if (EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(STATUS "Using local llama.cpp at ${LLAMA_DIR}")
    set(LLAMA_SRC ${LLAMA_DIR})
else()
    message(STATUS "Fetching llama.cpp ${OFFLINELLM_LLAMA_TAG}")
    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        ${OFFLINELLM_LLAMA_TAG}
        GIT_SHALLOW    TRUE
    )
    FetchContent_GetProperties(llama_cpp)
    if (NOT llama_cpp_POPULATED)
        FetchContent_Populate(llama_cpp)
    endif()
    set(LLAMA_SRC ${llama_cpp_SOURCE_DIR})
endif()

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

# Defaults; may be turned on below
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)

set(OFFLINELLM_OPENCL_BUILT 0)
set(OFFLINELLM_VULKAN_BUILT 0)

set(OPENCL_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencl")
set(OPENCL_ICD_LIB "${OPENCL_ROOT}/lib/libOpenCL.so")
set(OPENCL_INC_DIR "${OPENCL_ROOT}/include")

if (OFFLINELLM_ENABLE_OPENCL)
    if (EXISTS "${OPENCL_ICD_LIB}" AND EXISTS "${OPENCL_INC_DIR}/CL/cl.h")
        message(STATUS "OpenCL deps found — enabling GGML_OPENCL")
        set(OpenCL_INCLUDE_DIR "${OPENCL_INC_DIR}" CACHE PATH "" FORCE)
        set(OpenCL_LIBRARY "${OPENCL_ICD_LIB}" CACHE FILEPATH "" FORCE)
        set(OpenCL_FOUND TRUE CACHE BOOL "" FORCE)
        set(GGML_OPENCL ON CACHE BOOL "" FORCE)
        set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
        set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
        set(OFFLINELLM_OPENCL_BUILT 1)
    else()
        message(WARNING "OpenCL deps missing at ${OPENCL_ROOT} — OpenCL off")
        set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
    endif()
endif()

# --- Vulkan (host glslc + SPIRV-Headers for shader gen; NDK libvulkan at link) ---
set(VULKAN_HOST_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/vulkan-host")
if (OFFLINELLM_ENABLE_VULKAN)
    # glslc: staged by Gradle or on PATH
    if (NOT Vulkan_GLSLC_EXECUTABLE)
        if (EXISTS "${VULKAN_HOST_ROOT}/bin/glslc")
            set(Vulkan_GLSLC_EXECUTABLE "${VULKAN_HOST_ROOT}/bin/glslc" CACHE FILEPATH "" FORCE)
        else()
            find_program(Vulkan_GLSLC_EXECUTABLE glslc)
        endif()
    endif()

    # SPIRV-Headers cmake package (Gradle stages install prefix)
    set(SPIRV_HEADERS_PREFIX "${VULKAN_HOST_ROOT}/spirv-headers")
    if (EXISTS "${SPIRV_HEADERS_PREFIX}/share/cmake/SPIRV-Headers")
        list(APPEND CMAKE_PREFIX_PATH "${SPIRV_HEADERS_PREFIX}")
    elseif (EXISTS "${SPIRV_HEADERS_PREFIX}/lib/cmake/SPIRV-Headers")
        list(APPEND CMAKE_PREFIX_PATH "${SPIRV_HEADERS_PREFIX}")
    endif()

    # Android sysroot Vulkan headers + lib
    find_library(ANDROID_VULKAN_LIB vulkan)
    find_path(ANDROID_VULKAN_INCLUDE vulkan/vulkan.h)

    if (Vulkan_GLSLC_EXECUTABLE AND ANDROID_VULKAN_LIB)
        message(STATUS "Vulkan: glslc=${Vulkan_GLSLC_EXECUTABLE}")
        message(STATUS "Vulkan: lib=${ANDROID_VULKAN_LIB}")
        set(Vulkan_LIBRARY "${ANDROID_VULKAN_LIB}" CACHE FILEPATH "" FORCE)
        # Prefer staged Vulkan-Headers (C++ vulkan.hpp); fall back to NDK C headers
        set(_vk_hpp_inc "${VULKAN_HOST_ROOT}/include")
        if (EXISTS "${_vk_hpp_inc}/vulkan/vulkan.hpp")
            set(Vulkan_INCLUDE_DIR "${_vk_hpp_inc}" CACHE PATH "" FORCE)
            include_directories(BEFORE "${_vk_hpp_inc}")
            message(STATUS "Vulkan headers (hpp): ${_vk_hpp_inc}")
        elseif (ANDROID_VULKAN_INCLUDE)
            set(Vulkan_INCLUDE_DIR "${ANDROID_VULKAN_INCLUDE}" CACHE PATH "" FORCE)
            message(STATUS "Vulkan headers (NDK): ${ANDROID_VULKAN_INCLUDE}")
        endif()
        # SPIRV-Headers CONFIG package (must be visible to ggml-vulkan subdirectory)
        set(_spirv_cfg "${SPIRV_HEADERS_PREFIX}/share/cmake/SPIRV-Headers")
        if (EXISTS "${_spirv_cfg}/SPIRV-HeadersConfig.cmake")
            set(SPIRV-Headers_DIR "${_spirv_cfg}" CACHE PATH "" FORCE)
            list(APPEND CMAKE_PREFIX_PATH "${SPIRV_HEADERS_PREFIX}")
            # Also export into the cache for nested find_package
            set(CMAKE_PREFIX_PATH "${CMAKE_PREFIX_PATH}" CACHE STRING "" FORCE)
            message(STATUS "SPIRV-Headers_DIR=${SPIRV-Headers_DIR}")
        else()
            message(WARNING "SPIRV-HeadersConfig.cmake missing under ${SPIRV_HEADERS_PREFIX}")
        endif()
        set(ENV{VULKAN_SDK} "")
        set(GGML_VULKAN ON CACHE BOOL "" FORCE)
        set(OFFLINELLM_VULKAN_BUILT 1)
        message(STATUS "Enabling GGML_VULKAN")
    else()
        message(WARNING "Vulkan host/device deps incomplete (glslc='${Vulkan_GLSLC_EXECUTABLE}', lib='${ANDROID_VULKAN_LIB}') — Vulkan off")
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
        set(OFFLINELLM_VULKAN_BUILT 0)
    endif()
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

# Global includes so nested ggml-vulkan sources see headers even if target props lag
if (OFFLINELLM_VULKAN_BUILT)
    if (EXISTS "${VULKAN_HOST_ROOT}/include/vulkan/vulkan.hpp")
        include_directories(BEFORE "${VULKAN_HOST_ROOT}/include")
    endif()
    if (EXISTS "${SPIRV_HEADERS_PREFIX}/include/spirv/unified1/spirv.hpp")
        include_directories(BEFORE "${SPIRV_HEADERS_PREFIX}/include")
    endif()
endif()

add_subdirectory(${LLAMA_SRC} llama_build EXCLUDE_FROM_ALL)

add_library(offlinellm_jni SHARED offlinellm_jni.cpp)

target_include_directories(offlinellm_jni PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
)

if (NOT TARGET llama)
    message(FATAL_ERROR "llama CMake target not found")
endif()

# Base libs
target_link_libraries(offlinellm_jni PRIVATE llama android log)

if (OFFLINELLM_OPENCL_BUILT)
    target_include_directories(offlinellm_jni PRIVATE ${OPENCL_INC_DIR})
    target_link_libraries(offlinellm_jni PRIVATE ${OPENCL_ICD_LIB})
    target_link_options(offlinellm_jni PRIVATE "-Wl,--no-as-needed")
endif()

if (OFFLINELLM_VULKAN_BUILT)
    set(_vk_inc "${VULKAN_HOST_ROOT}/include")
    set(_spirv_inc "${SPIRV_HEADERS_PREFIX}/include")
    if (EXISTS "${_vk_inc}/vulkan/vulkan.hpp")
        target_include_directories(offlinellm_jni PRIVATE "${_vk_inc}")
        if (TARGET ggml-vulkan)
            target_include_directories(ggml-vulkan PRIVATE "${_vk_inc}")
        endif()
    endif()
    if (EXISTS "${_spirv_inc}/spirv/unified1/spirv.hpp")
        target_include_directories(offlinellm_jni PRIVATE "${_spirv_inc}")
        if (TARGET ggml-vulkan)
            target_include_directories(ggml-vulkan PRIVATE "${_spirv_inc}")
        endif()
        message(STATUS "SPIRV include: ${_spirv_inc}")
    endif()

    # Static ggml-vulkan pulls Vulkan symbols; force libvulkan AFTER llama group.
    # lld + --as-needed otherwise drops libvulkan before seeing static archive refs.
    if (TARGET ggml-vulkan AND ANDROID_VULKAN_LIB)
        target_link_libraries(ggml-vulkan PUBLIC ${ANDROID_VULKAN_LIB})
    endif()

    # Explicit group: resolve circular/static refs between llama stack and libvulkan
    if (ANDROID_VULKAN_LIB)
        target_link_options(offlinellm_jni PRIVATE
            "-Wl,--no-as-needed"
            "-Wl,--start-group"
        )
        target_link_libraries(offlinellm_jni PRIVATE
            ${ANDROID_VULKAN_LIB}
            vulkan
        )
        target_link_options(offlinellm_jni PRIVATE
            "-Wl,--end-group"
        )
        # Final hammer: append raw flags so vulkan is last on the link line
        set_property(TARGET offlinellm_jni APPEND_STRING PROPERTY
            LINK_FLAGS " -Wl,--no-as-needed ${ANDROID_VULKAN_LIB} -lvulkan ")
        message(STATUS "Linking JNI with Vulkan: ${ANDROID_VULKAN_LIB}")
    endif()
endif()

target_compile_options(offlinellm_jni PRIVATE -O3 -fvisibility=hidden)

target_compile_definitions(offlinellm_jni PRIVATE
    OFFLINELLM_LLAMA_TAG="${OFFLINELLM_LLAMA_TAG}"
    OFFLINELLM_OPENCL_BUILT=${OFFLINELLM_OPENCL_BUILT}
    OFFLINELLM_VULKAN_BUILT=${OFFLINELLM_VULKAN_BUILT}
)
