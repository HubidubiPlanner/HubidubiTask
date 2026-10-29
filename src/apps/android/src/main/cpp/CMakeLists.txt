# whisper.cpp in the phone app: speech to text on the phone itself, for voice
# notes (capture/Whisper.kt). ggml picks the best CPU library for the phone at
# run time (GGML_BACKEND_DL); on arm64 there is one per generation of cores.
cmake_minimum_required(VERSION 3.22)
project(hubidubi_whisper C CXX)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../.." ABSOLUTE)

# The same bytes wherever this is built: F-Droid builds it on Linux and
# compares with the APK built on a Mac. So no paths, compiler banner, build
# id or git hash end up in the libraries.
add_compile_options("-ffile-prefix-map=${REPO_ROOT}=." "-ffile-prefix-map=${ANDROID_NDK}=/ndk" -fno-ident)
add_link_options("LINKER:--build-id=none" "LINKER:--hash-style=gnu")
set(GIT_EXE /usr/bin/true CACHE FILEPATH "" FORCE)
# Nor a .comment section: the NDK's own runtime pieces carry the banner of
# the compiler that built them, which differs between the NDK for the Mac
# and the one for Linux. Dropped right after each library is linked.
foreach(rule CMAKE_C_CREATE_SHARED_LIBRARY CMAKE_CXX_CREATE_SHARED_LIBRARY
             CMAKE_C_CREATE_SHARED_MODULE CMAKE_CXX_CREATE_SHARED_MODULE)
  set(${rule} "${${rule}} && \"${CMAKE_OBJCOPY}\" --remove-section .comment <TARGET>")
endforeach()

# CACHE FORCE: whisper.cpp's own CMakeLists predates CMP0077.
foreach(o BUILD_SHARED_LIBS GGML_BACKEND_DL)
  set(${o} ON CACHE BOOL "" FORCE)
endforeach()
# KleidiAI would be downloaded while configuring; the rest isn't needed here.
foreach(o GGML_NATIVE GGML_OPENMP GGML_CCACHE GGML_CPU_KLEIDIAI
          WHISPER_BUILD_TESTS WHISPER_BUILD_EXAMPLES WHISPER_BUILD_SERVER WHISPER_ALL_WARNINGS)
  set(${o} OFF CACHE BOOL "" FORCE)
endforeach()
if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
  # Every x86_64 Android device has SSE4.2.
  set(GGML_SSE42 ON CACHE BOOL "" FORCE)
endif()

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../whisper.cpp" whisper.cpp)

add_library(hubidubi_whisper SHARED whisper_jni.cpp)
target_link_libraries(hubidubi_whisper PRIVATE whisper ggml android log)
