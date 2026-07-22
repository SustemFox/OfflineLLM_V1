cmake_minimum_required(VERSION 3.22.1)
project(offlinellm_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

set(OFFLINELLM_LLAMA_TAG "b10079" CACHE STRING "llama.cpp git tag/branch")
option(OFFLINELLM_ENABLE_OPENCL "Build llama.cpp with GGML_OPENCL" ON)

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
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

set(OFFLINELLM_OPENCL_BUILT 0)
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
        message(WARNING "OpenCL deps missing at ${OPENCL_ROOT} — building CPU-only")
        set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
    endif()
else()
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_SRC} llama_build EXCLUDE_FROM_ALL)

add_library(offlinellm_jni SHARED offlinellm_jni.cpp)

target_include_directories(offlinellm_jni PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
)

if (TARGET llama)
    target_link_libraries(offlinellm_jni llama android log)
else()
    message(FATAL_ERROR "llama CMake target not found")
endif()

if (OFFLINELLM_OPENCL_BUILT)
    target_include_directories(offlinellm_jni PRIVATE ${OPENCL_INC_DIR})
    target_link_libraries(offlinellm_jni ${OPENCL_ICD_LIB})
    target_link_options(offlinellm_jni PRIVATE "-Wl,--no-as-needed")
endif()

target_compile_options(offlinellm_jni PRIVATE -O3 -fvisibility=hidden)

target_compile_definitions(offlinellm_jni PRIVATE
    OFFLINELLM_LLAMA_TAG="${OFFLINELLM_LLAMA_TAG}"
    OFFLINELLM_OPENCL_BUILT=${OFFLINELLM_OPENCL_BUILT}
)