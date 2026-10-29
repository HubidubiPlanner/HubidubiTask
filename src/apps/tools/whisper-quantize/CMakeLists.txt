# whisper.cpp's quantize tool, for the computer that builds the app
# (whisper-model.sh). whisper.cpp is brought in as a part, not built on its
# own, so nothing is written into its tree. Scalar code only, without fused
# multiply-adds, so that a Mac and F-Droid's Linux make the same model.
cmake_minimum_required(VERSION 3.22)
project(whisper_quantize C CXX)

set(WHISPER_SRC "" CACHE PATH "The whisper.cpp tree")
foreach(o WHISPER_BUILD_EXAMPLES)
  set(${o} ON CACHE BOOL "" FORCE)
endforeach()
foreach(o BUILD_SHARED_LIBS WHISPER_BUILD_TESTS WHISPER_BUILD_SERVER WHISPER_SDL2 WHISPER_CURL
          GGML_NATIVE GGML_AVX GGML_AVX2 GGML_AVX512 GGML_FMA GGML_F16C GGML_BMI2 GGML_SSE42
          GGML_METAL GGML_BLAS GGML_ACCELERATE GGML_OPENMP GGML_CCACHE)
  set(${o} OFF CACHE BOOL "" FORCE)
endforeach()
add_compile_options(-ffp-contract=off)

add_subdirectory("${WHISPER_SRC}" whisper.cpp)
