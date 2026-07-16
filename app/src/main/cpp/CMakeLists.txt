cmake_minimum_required(VERSION 3.22.1)
project(ankicopy_llm)

# ---------------------------------------------------------------------------
# llama.cpp is vendored as a git submodule at app/src/main/cpp/llama.cpp
# (see README setup step). We only need its core ggml/llama sources, no
# examples/server, to keep the APK size and build time sane on-device.
# ---------------------------------------------------------------------------
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(ankicopy_llm SHARED
    llm_bridge.cpp
)

target_include_directories(ankicopy_llm PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)

target_link_libraries(ankicopy_llm
    llama
    android
    log
)

# Granite models are small (1b/3b) - keep threads modest by default,
# actual thread count is passed at runtime from Kotlin based on CPU count.
target_compile_options(ankicopy_llm PRIVATE -O3 -ffast-math)
