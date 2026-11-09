cmake_minimum_required(VERSION 3.10.2)

project(unwindstack)

enable_language(C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fPIC -fvisibility=hidden")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC -fvisibility=hidden")

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--no-allow-shlib-undefined,--no-undefined -Wl,-z,text,-z,defs,-z,now,-z,relro")
# prefer static libgcc and libstdc++
#set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -static-libgcc -static-libstdc++")

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-omit-frame-pointer -Werror=non-virtual-dtor -Werror=delete-non-virtual-dtor")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fno-omit-frame-pointer")

include_directories(misc)

include(CheckIncludeFile)
# new version of musl do not have <sys/cdefs.h>
CHECK_INCLUDE_FILE("sys/cdefs.h" HAVE_SYS_CDEFS_H)
if (NOT HAVE_SYS_CDEFS_H)
    include_directories(misc/musl_stub)
    message(STATUS "Using musl stub for missing sys/cdefs.h")
endif ()

if (DEFINED ANDROID_ABI)
    if (ANDROID_ABI STREQUAL "x86")
        set(UNWINDSTACK_ARCH "x86")
    elseif (ANDROID_ABI STREQUAL "x86_64")
        set(UNWINDSTACK_ARCH "x86_64")
    elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
        set(UNWINDSTACK_ARCH "arm")
    elseif (ANDROID_ABI STREQUAL "arm64-v8a")
        set(UNWINDSTACK_ARCH "arm64")
    elseif (ANDROID_ABI STREQUAL "riscv64")
        set(UNWINDSTACK_ARCH "riscv64")
    else ()
        message(FATAL_ERROR "Unsupported ANDROID_ABI: ${ANDROID_ABI}")
    endif ()
else ()
    if (CMAKE_SYSTEM_PROCESSOR STREQUAL "x86" OR CMAKE_SYSTEM_PROCESSOR STREQUAL "i686")
        set(UNWINDSTACK_ARCH "x86")
    elseif (CMAKE_SYSTEM_PROCESSOR STREQUAL "x86_64" OR CMAKE_SYSTEM_PROCESSOR STREQUAL "amd64")
        set(UNWINDSTACK_ARCH "x86_64")
    elseif (CMAKE_SYSTEM_PROCESSOR STREQUAL "armv7l" OR CMAKE_SYSTEM_PROCESSOR STREQUAL "armv7-a")
        set(UNWINDSTACK_ARCH "arm")
    elseif (CMAKE_SYSTEM_PROCESSOR STREQUAL "aarch64" OR CMAKE_SYSTEM_PROCESSOR STREQUAL "armv8-a")
        set(UNWINDSTACK_ARCH "arm64")
    else ()
        message(FATAL_ERROR "Unsupported CMAKE_SYSTEM_PROCESSOR: ${CMAKE_SYSTEM_PROCESSOR}")
    endif ()
endif ()

# architecture-specific sources
if (UNWINDSTACK_ARCH STREQUAL "x86")
    set(UNWINDSTACK_ARCH_SRC "libunwindstack/AsmGetRegsX86.S")
elseif (UNWINDSTACK_ARCH STREQUAL "x86_64")
    set(UNWINDSTACK_ARCH_SRC "libunwindstack/AsmGetRegsX86_64.S")
else ()
    set(UNWINDSTACK_ARCH_SRC "")
endif ()

if (UNWINDSTACK_ARCH STREQUAL "x86")
    list(APPEND ART_COMPILE_C_FLAGS "-DART_ENABLE_CODEGEN_x86")
elseif (UNWINDSTACK_ARCH STREQUAL "x86_64")
    list(APPEND ART_COMPILE_C_FLAGS "-DART_ENABLE_CODEGEN_x86_64" "-DART_ENABLE_CODEGEN_x86")
elseif (UNWINDSTACK_ARCH STREQUAL "arm")
    list(APPEND ART_COMPILE_C_FLAGS "-DART_ENABLE_CODEGEN_arm")
elseif (UNWINDSTACK_ARCH STREQUAL "arm64")
    list(APPEND ART_COMPILE_C_FLAGS "-DART_ENABLE_CODEGEN_arm64" "-DART_ENABLE_CODEGEN_arm")
endif ()

add_library(base STATIC
        "libbase/chrono_utils.cpp"
        "libbase/file.cpp"
        "libbase/hex.cpp"
        "libbase/mapped_file.cpp"
        "libbase/parsebool.cpp"
        "libbase/parsenetaddress.cpp"
        "libbase/logging.cpp"
        "libbase/posix_strerror_r.cpp"
        "libbase/process.cpp"
        "libbase/stringprintf.cpp"
        "libbase/strings.cpp"
        "libbase/threads.cpp"
)

target_include_directories(base PUBLIC "libbase/include" "liblog")

add_library(procinfo STATIC "libprocinfo/process.cpp")
target_include_directories(procinfo PUBLIC "libprocinfo/include")
target_compile_options(procinfo PRIVATE -fPIC)
target_link_libraries(procinfo base)

set(libziparchive_src_dir libziparchive)

add_library(ziparchive STATIC
        "${libziparchive_src_dir}/zip_archive.cc"
        "${libziparchive_src_dir}/zip_archive_stream_entry.cc"
        "${libziparchive_src_dir}/zip_cd_entry_map.cc"
        "${libziparchive_src_dir}/zip_error.cpp"
        "${libziparchive_src_dir}/zip_writer.cc"
        "${libziparchive_src_dir}/incfs_support/signal_handling.cpp"
)

# libziparchive requires cxx2a
set_target_properties(ziparchive PROPERTIES CXX_STANDARD 20)

target_include_directories(ziparchive
        PUBLIC "${libziparchive_src_dir}/include"
        PRIVATE "${libziparchive_src_dir}/incfs_support/include"
        PUBLIC "$<TARGET_PROPERTY:base,INTERFACE_INCLUDE_DIRECTORIES>"
)

target_compile_definitions(ziparchive PRIVATE ZLIB_CONST=const)

target_compile_options(ziparchive PRIVATE -fPIC
        $<$<COMPILE_LANGUAGE:CXX>:-Wno-missing-field-initializers -Wconversion -Wno-sign-conversion -Wold-style-cast>
        $<$<COMPILE_LANGUAGE:C>:-DZLIB_CONST -Wall -Werror -Wextra -D_FILE_OFFSET_BITS=64 -DINCFS_SUPPORT_DISABLED=1>)

target_link_libraries(ziparchive base z)

add_library(dexfile_stub STATIC
        #        "libdexfile/dex/dex_file_verifier.cc"
        #        "libdexfile/dex/art_dex_file_loader.cc"
        "libdexfile/dex/compact_offset_table.cc"
        "libdexfile/dex/descriptors_names.cc"
        "libdexfile/dex/dex_file.cc"
        "libdexfile/dex/standard_dex_file.cc"
        "libdexfile/dex/compact_dex_file.cc"
        #        "libdexfile/dex/dex_file_exception_helpers.cc"
        #        "libdexfile/dex/dex_file_layout.cc"
        "libdexfile/dex/dex_file_loader.cc"
        #        "libdexfile/dex/dex_file_tracking_registrar.cc"
        "libdexfile/dex/dex_file_verifier.cc"
        #        "libdexfile/dex/dex_instruction.cc"
        "libdexfile/dex/modifiers.cc"
        "libdexfile/dex/primitive.cc"
        "libdexfile/dex/signature.cc"
        #        "libdexfile/dex/type_lookup_table.cc"
        "libdexfile/dex/utf.cc"
        "libdexfile/external/dex_file_ext.cc"
        "libdexfile/external/dex_file_supp.cc"

        "libartbase/base/unix_file/fd_file.cc"
        "libartbase/base/logging.cc"
        "libartbase/base/utils.cc"
        "libartbase/base/file_magic.cc"
        "libartbase/base/mem_map.cc"
        "libartbase/base/mem_map_unix.cc"
        "libartbase/base/zip_archive.cc"

        "libartbase/gen/libartbase.operator_out.cc"
        "libartbase/base/runtime_common.cc"
        "libartbase/base/native_stack_dump.cc"

        "generated/libdexfile.operator_out.cc"
)

target_include_directories(dexfile_stub BEFORE
        PUBLIC "libdexfile"
        PUBLIC "libdexfile/external/include"
        PUBLIC "libnativehelper/include_jni"
        PUBLIC "libartpalette/include"
        PRIVATE "libartbase"
        PRIVATE "runtime"
        #        PUBLIC "${MDVM_NATIVE_SRC_ROOT_DIR}/libnativehelper/include_jni"
        #        PRIVATE "$<TARGET_PROPERTY:artbase,INTERFACE_INCLUDE_DIRECTORIES>"
)

# check if we are targeting Android or Linux
if (ANDROID)
    target_compile_definitions(dexfile_stub PUBLIC -DART_TARGET -DSTATIC_LIB=1 -DART_TARGET_ANDROID)
else ()
    target_compile_definitions(dexfile_stub PUBLIC -DART_TARGET -DSTATIC_LIB=1 -DART_TARGET_LINUX)
endif ()

target_compile_options(dexfile_stub PRIVATE ${ART_COMPILE_C_FLAGS} -Wno-deprecated-declarations)

target_link_libraries(dexfile_stub base unwindstack ziparchive)

add_library(lzma STATIC
        "lzma/C/7zAlloc.c"
        "lzma/C/7zArcIn.c"
        "lzma/C/7zBuf2.c"
        "lzma/C/7zBuf.c"
        "lzma/C/7zCrc.c"
        "lzma/C/7zCrcOpt.c"
        "lzma/C/7zDec.c"
        "lzma/C/7zFile.c"
        "lzma/C/7zStream.c"
        "lzma/C/Aes.c"
        "lzma/C/AesOpt.c"
        "lzma/C/Alloc.c"
        "lzma/C/Bcj2.c"
        "lzma/C/Bra86.c"
        "lzma/C/Bra.c"
        "lzma/C/BraIA64.c"
        "lzma/C/CpuArch.c"
        "lzma/C/Delta.c"
        "lzma/C/LzFind.c"
        "lzma/C/Lzma2Dec.c"
        "lzma/C/Lzma2Enc.c"
        "lzma/C/Lzma86Dec.c"
        "lzma/C/Lzma86Enc.c"
        "lzma/C/LzmaDec.c"
        "lzma/C/LzmaEnc.c"
        "lzma/C/LzmaLib.c"
        "lzma/C/Ppmd7.c"
        "lzma/C/Ppmd7Dec.c"
        "lzma/C/Ppmd7Enc.c"
        "lzma/C/Sha256.c"
        "lzma/C/Sort.c"
        "lzma/C/Xz.c"
        "lzma/C/XzCrc64.c"
        "lzma/C/XzCrc64Opt.c"
        "lzma/C/XzDec.c"
        "lzma/C/XzEnc.c"
        "lzma/C/XzIn.c"
)

set_property(TARGET lzma PROPERTY CXX_STANDARD 20)

target_include_directories(lzma PUBLIC "lzma/C")

target_compile_options(lzma PRIVATE -fPIC "-D_7ZIP_ST")

add_library(unwindstack STATIC
        "libunwindstack/AndroidUnwinder.cpp"
        "libunwindstack/ArmExidx.cpp"
        "libunwindstack/Demangle.cpp"
        "libunwindstack/DexFiles.cpp"
        "libunwindstack/DwarfCfa.cpp"
        "libunwindstack/DwarfEhFrameWithHdr.cpp"
        "libunwindstack/DwarfMemory.cpp"
        "libunwindstack/DwarfOp.cpp"
        "libunwindstack/DwarfSection.cpp"
        "libunwindstack/Elf.cpp"
        "libunwindstack/ElfInterface.cpp"
        "libunwindstack/ElfInterfaceArm.cpp"
        "libunwindstack/Global.cpp"
        "libunwindstack/JitDebug.cpp"
        "libunwindstack/MapInfo.cpp"
        "libunwindstack/Maps.cpp"
        "libunwindstack/Memory.cpp"
        "libunwindstack/MemoryMte.cpp"
        "libunwindstack/MemoryXz.cpp"
        "libunwindstack/Regs.cpp"
        "libunwindstack/RegsArm.cpp"
        "libunwindstack/RegsArm64.cpp"
        "libunwindstack/RegsX86.cpp"
        "libunwindstack/RegsX86_64.cpp"
        "libunwindstack/RegsRiscv64.cpp"
        "libunwindstack/RegsMips.cpp"
        "libunwindstack/RegsMips64.cpp"
        "libunwindstack/Symbols.cpp"
        "libunwindstack/ThreadEntry.cpp"
        "libunwindstack/ThreadUnwinder.cpp"
        "libunwindstack/Unwinder.cpp"

        "libunwindstack/DexFile.cpp"
        "libunwindstack/LogAndroid.cpp"

        "${UNWINDSTACK_ARCH_SRC}"

)

target_include_directories(unwindstack BEFORE
        PUBLIC "libunwindstack/include"
        PRIVATE "platform"
)
target_compile_options(unwindstack PRIVATE -Wno-c99-designator -Wno-deprecated-declarations)
# for clang
if (CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
    target_compile_options(unwindstack PRIVATE -Wexit-time-destructors -fno-c++-static-destructors)
endif ()

target_compile_definitions(unwindstack PRIVATE DEXFILE_SUPPORT=1)

target_link_libraries(unwindstack lzma base dexfile_stub procinfo)

# check are we top-level project
if (CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)

    # check if we are building for Android
    if (ANDROID)
        add_executable(unwindstack_test
                preload/init_handler.cc
                test/test.cc
        )
        target_include_directories(unwindstack_test PRIVATE libartbase)
        target_link_libraries(unwindstack_test unwindstack log)
    else ()
        add_executable(unwindstack_test
                preload/init_handler.cc
                test/test.cc
                log_stub/android_log_impl.cc
        )
        target_include_directories(unwindstack_test PRIVATE libartbase)
        target_link_libraries(unwindstack_test unwindstack pthread)

        add_library(unwindstack-preload SHARED
                preload/preload_entry.c
                preload/init_handler.cc
                log_stub/android_log_impl.cc
        )
        target_include_directories(unwindstack-preload PRIVATE libartbase)
        target_link_libraries(unwindstack-preload unwindstack pthread)
    endif ()

endif ()
