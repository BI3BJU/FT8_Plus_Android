cmake_minimum_required(VERSION 3.18.1)
project("ft8_jni")

set(CMAKE_CXX_STANDARD 11)

set(FT8_SOURCES
    ft8_lib/common/monitor.c
    ft8_lib/common/wave.c
    ft8_lib/fft/kiss_fft.c
    ft8_lib/fft/kiss_fftr.c
    ft8_lib/ft8/decode.c
    ft8_lib/ft8/constants.c
    ft8_lib/ft8/crc.c
    ft8_lib/ft8/ldpc.c
    ft8_lib/ft8/message.c
    ft8_lib/ft8/text.c
    ft8_lib/ft8/encode.c           # 新增这一行
)

add_library(ft8_static STATIC ${FT8_SOURCES})
target_include_directories(ft8_static PUBLIC
    ft8_lib
    ft8_lib/common
    ft8_lib/fft
    ft8_lib/ft8
)

target_link_libraries(ft8_static log m)

add_library(ft8_jni SHARED jni/ft8_jni.cpp)
target_link_libraries(ft8_jni ft8_static)
target_include_directories(ft8_jni PRIVATE ft8_lib/ft8 ft8_lib/common)
# --- Optimization Flags ---
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3 -ffast-math")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math")
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3 -ffast-math")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3 -ffast-math")


