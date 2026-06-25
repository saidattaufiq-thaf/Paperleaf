cmake_minimum_required(VERSION 3.10)
project(paperleaf_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fvisibility=hidden")

add_library(paperleaf_native SHARED
    native_draw.cpp
)

find_library(log-lib log)

target_include_directories(paperleaf_native PRIVATE ${ANDROID_NDK}/sources/android/native_app_glue)

target_link_libraries(paperleaf_native
    ${log-lib}
)
