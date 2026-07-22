cmake_minimum_required(VERSION 3.22.1)
project(offlinellm_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is fetched by CI into this directory (or use FetchContent)
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp)
if (NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_DIR}. CI must clone it before build.")
endif()

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama_build EXCLUDE_FROM_ALL)

add_library(offlinellm_jni SHARED
    offlinellm_jni.cpp
)

target_include_directories(offlinellm_jni PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
    ${LLAMA_DIR}/common
)

# Prefer modern targets; fall back to llama if needed
if (TARGET llama AND TARGET common)
    target_link_libraries(offlinellm_jni
        llama
        common
        android
        log
    )
elseif (TARGET llama)
    target_link_libraries(offlinellm_jni
        llama
        android
        log
    )
else()
    message(FATAL_ERROR "llama target not found after add_subdirectory")
endif()

target_compile_options(offlinellm_jni PRIVATE -O3 -fvisibility=hidden)
