cmake_minimum_required(VERSION 3.22)
project(sdr2hdr_sr LANGUAGES CXX)

# ncnn (static, Vulkan enabled) is downloaded by the Gradle task `fetchNcnn`
# (see app/build.gradle.kts) and passed in as -DNCNN_ROOT=<dir containing arm64-v8a/...>.
if(NOT DEFINED NCNN_ROOT)
    message(FATAL_ERROR "NCNN_ROOT is not set. Run ./gradlew :app:fetchNcnn or build through Gradle.")
endif()

set(ncnn_DIR "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn")
if(NOT EXISTS "${ncnn_DIR}/ncnnConfig.cmake")
    message(FATAL_ERROR "ncnn package for ABI ${ANDROID_ABI} not found at ${ncnn_DIR}")
endif()
find_package(ncnn REQUIRED)

add_library(sdr2hdr_sr SHARED
    sr_jni.cpp
    sr_core.cpp)

target_compile_features(sdr2hdr_sr PRIVATE cxx_std_17)
target_compile_options(sdr2hdr_sr PRIVATE -O3 -ffunction-sections -fdata-sections -fvisibility=hidden)
# ncnn is built with OpenMP; link libomp statically so no extra .so has to be packaged.
target_link_options(sdr2hdr_sr PRIVATE -static-openmp -Wl,--gc-sections -Wl,--exclude-libs,ALL)

target_link_libraries(sdr2hdr_sr PRIVATE ncnn android jnigraphics log)
