cmake_minimum_required(VERSION 3.22.1)
project(parda_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is a sibling checkout, passed in by app/build.gradle.kts (-Pparda.llamaDir).
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at '${LLAMA_DIR}'. Clone it next to this repo or pass -Pparda.llamaDir=<path>.")
endif()

# Same CPU setup as llama.cpp's own Android example: KleidiAI kernels on arm64, and every CPU
# variant built as a loadable backend so the phone picks the best one (dotprod, i8mm, SVE).
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH "x86")
    set(GGML_CPU_KLEIDIAI OFF)
    set(GGML_OPENMP OFF)
endif()

add_subdirectory(${LLAMA_DIR} llama-build)

add_library(parda_llm SHARED parda_llm.cpp)
target_link_libraries(parda_llm llama android log)
