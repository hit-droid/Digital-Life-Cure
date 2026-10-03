cmake_minimum_required(VERSION 3.18.1)
project("maidendungeon")

set(CMAKE_CXX_STANDARD 17)

add_library(CubismCore STATIC IMPORTED)
set_target_properties(CubismCore PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/Core/lib/android/${ANDROID_ABI}/libLive2DCubismCore.a
)

set(FRAMEWORK_SRC_DIR ${CMAKE_SOURCE_DIR}/Framework/src)
set(LAPP_SRC_DIR ${CMAKE_SOURCE_DIR}/LApp)

file(GLOB_RECURSE FRAMEWORK_SRCS
    ${FRAMEWORK_SRC_DIR}/*.cpp
)
list(FILTER FRAMEWORK_SRCS EXCLUDE REGEX ".*/D3D9/.*")
list(FILTER FRAMEWORK_SRCS EXCLUDE REGEX ".*/D3D11/.*")
list(FILTER FRAMEWORK_SRCS EXCLUDE REGEX ".*/Metal/.*")
list(FILTER FRAMEWORK_SRCS EXCLUDE REGEX ".*/Vulkan/.*")

set(LAPP_SRCS
    ${LAPP_SRC_DIR}/JniBridgeC.cpp
    ${LAPP_SRC_DIR}/LAppAllocator.cpp
    ${LAPP_SRC_DIR}/LAppDefine.cpp
    ${LAPP_SRC_DIR}/LAppDelegate.cpp
    ${LAPP_SRC_DIR}/LAppLive2DManager.cpp
    ${LAPP_SRC_DIR}/LAppModel.cpp
    ${LAPP_SRC_DIR}/LAppPal.cpp
    ${LAPP_SRC_DIR}/LAppSprite.cpp
    ${LAPP_SRC_DIR}/LAppTextureManager.cpp
    ${LAPP_SRC_DIR}/LAppView.cpp
    ${LAPP_SRC_DIR}/TouchManager.cpp
)

add_library(maidendungeon SHARED
    ${FRAMEWORK_SRCS}
    ${LAPP_SRCS}
)

target_include_directories(maidendungeon PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/Core
    ${CMAKE_SOURCE_DIR}/Framework
    ${CMAKE_SOURCE_DIR}/Framework/src
    ${CMAKE_SOURCE_DIR}/LApp
)

target_link_libraries(maidendungeon
    CubismCore
    GLESv2
    android
    log
)

# Android 15 起系统支持 16 KB 内存页，Android 16（API 36）设备默认启用。
# 按 4 KB 对齐链接出的 .so 在这些设备上会直接加载失败，表现为「装上打不开 / 启动即崩」。
# 这里显式要求 16 KB 对齐：16 KB 对齐的库在 4 KB 页设备上同样可用，二者通吃。
# （NDK r27+ 已默认 16 KB，此参数对高版本 NDK 无副作用，作为兼容低版本 NDK 的兜底。）
target_link_options(maidendungeon PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_compile_definitions(maidendungeon PRIVATE CSM_TARGET_ANDROID_ES2)