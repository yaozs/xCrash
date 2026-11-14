cmake_minimum_required(VERSION 3.10)

#######################################
# global
#######################################

# 说明：原配置为 -Weverything -Werror。NDK r27 (clang 18) 相比 NDK r21 (clang 11)
# 新增了大量诊断项（-Wcast-function-type-strict、-Wunsafe-buffer-usage 等），
# 沿用它会导致整个库无法编译。这里降级为常用告警集，且不再把告警当错误。
add_compile_options(
        -std=c11
        -Wall)

# 16 KB 页大小对齐。Android 15 起系统支持 16 KB 页，Google Play 自 2025-11 起
# 要求 targetSdk >= 35 的应用在 64 位设备上必须兼容 16 KB 页。
# 未对齐的 so 在 16 KB 页设备上会被动态链接器直接拒绝加载。
set(XCRASH_PAGE_SIZE_LINK_OPTIONS
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")

#######################################
# libxcrash.so
#######################################

file(GLOB XCRASH_SRC
        xcrash/*.c
        common/*.c
        dl/*.c)

add_library(xcrash SHARED
        ${XCRASH_SRC})

target_include_directories(xcrash PUBLIC
        xcrash
        xcrash_dumper
        common
        dl)

target_link_libraries(xcrash
        log
        dl)

target_link_options(xcrash PRIVATE ${XCRASH_PAGE_SIZE_LINK_OPTIONS})

if(USEASAN)

target_compile_options(xcrash PUBLIC
        -fsanitize=address
        -fno-omit-frame-pointer)

set_target_properties(xcrash PROPERTIES
        LINK_FLAGS " \
        -fsanitize=address")

else()

target_compile_options(xcrash PUBLIC
        -Oz
        -flto
        -ffunction-sections
        -fdata-sections)

set_target_properties(xcrash PROPERTIES
        LINK_FLAGS " \
        -O3 \
        -flto \
        -Wl,--exclude-libs,ALL \
        -Wl,--gc-sections \
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/xcrash.exports")

endif()

#######################################
# libxcrash_dumper.so
#######################################

file(GLOB XCRASH_DUMPER_SRC
        xcrash_dumper/*.c
        common/*.c)

set(LZME_SRC
        lzma/7zCrc.c
        lzma/7zCrcOpt.c
        lzma/CpuArch.c
        lzma/Bra.c
        lzma/Bra86.c
        lzma/BraIA64.c
        lzma/Delta.c
        lzma/Lzma2Dec.c
        lzma/LzmaDec.c
        lzma/Sha256.c
        lzma/Xz.c
        lzma/XzCrc64.c
        lzma/XzCrc64Opt.c
        lzma/XzDec.c)

set_source_files_properties(${LZME_SRC} PROPERTIES
        COMPILE_FLAGS " \
        -D_7ZIP_ST \
        -Wno-enum-conversion \
        -Wno-reserved-id-macro \
        -Wno-undef \
        -Wno-missing-prototypes \
        -Wno-missing-variable-declarations \
        -Wno-cast-align \
        -Wno-sign-conversion \
        -Wno-assign-enum \
        -Wno-unused-macros \
        -Wno-padded \
        -Wno-cast-qual \
        -Wno-strict-prototypes \
        -Wno-extra-semi-stmt")

add_executable(xcrash_dumper
        ${XCRASH_DUMPER_SRC}
        ${LZME_SRC})

target_include_directories(xcrash_dumper PUBLIC
        xcrash_dumper
        common
        lzma)

target_link_libraries(xcrash_dumper
        log
        dl)

target_link_options(xcrash_dumper PRIVATE ${XCRASH_PAGE_SIZE_LINK_OPTIONS})

if(USEASAN)

target_compile_options(xcrash_dumper PUBLIC
        -fsanitize=address
        -fno-omit-frame-pointer)

set_target_properties(xcrash_dumper PROPERTIES
        LINK_FLAGS " \
        -fsanitize=address")

else()

target_compile_options(xcrash_dumper PUBLIC
        -Oz
        -flto
        -ffunction-sections
        -fdata-sections)

set_target_properties(xcrash_dumper PROPERTIES
        LINK_FLAGS " \
        -O3 \
        -flto \
        -Wl,--exclude-libs,ALL \
        -Wl,--gc-sections")

endif()

set_target_properties(xcrash_dumper PROPERTIES
        PREFIX "lib"
        SUFFIX ".so")
