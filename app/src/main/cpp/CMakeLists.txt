cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni)

include(FetchContent)

# Download whisper.cpp v1.5.5 (stable, simple build structure)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG v1.5.5
    GIT_SHALLOW TRUE
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(whisper)

# JNI bridge library
add_library(whisper_jni SHARED whisper_jni.cpp)

target_link_libraries(whisper_jni
    PRIVATE
    whisper
    log
)

target_include_directories(whisper_jni PRIVATE
    ${whisper_SOURCE_DIR}
)
