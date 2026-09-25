# C++ build: compiles whisper.cpp + our small bridge (jni.cpp) into libvmreader.so
cmake_minimum_required(VERSION 3.22.1)
project(vmreader)

set(CMAKE_BUILD_TYPE Release)                       # always optimized (fast on phone)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE) # put everything inside one .so
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE) # cross-compiling for phone
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

# whisper.cpp source is downloaded into this folder by the GitHub build
add_subdirectory(whisper.cpp EXCLUDE_FROM_ALL)

add_library(vmreader SHARED jni.cpp)
target_link_libraries(vmreader whisper android log)
