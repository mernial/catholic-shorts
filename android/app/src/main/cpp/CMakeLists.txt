cmake_minimum_required(VERSION 3.22.1)
project(talkhelper_whisper C CXX)

# 고급 받아쓰기(Whisper large-v3-turbo)를 휴대폰 안에서 돌리는 네이티브 라이브러리.
# 64비트 ARM(arm64-v8a)에서만 실제 엔진을 넣고, 다른 기기용으로는 빈 껍데기를 넣어 앱 설치는 되게 함.

# 디버그 빌드에서도 받아쓰기는 최적화해서 빌드 (최적화를 끄면 수십 배 느려짐)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
add_compile_options(-O3)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    # 2018년 이후 대부분의 휴대폰 CPU가 지원 (앱은 실행 전에 /proc/cpuinfo 로 확인)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

    include(FetchContent)
    FetchContent_Declare(whisper
        URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.7.6.tar.gz)
    FetchContent_MakeAvailable(whisper)

    add_library(whisperjni SHARED whisper_jni.cpp)
    target_link_libraries(whisperjni PRIVATE whisper log)
else ()
    add_library(whisperjni SHARED whisper_stub.cpp)
endif ()
