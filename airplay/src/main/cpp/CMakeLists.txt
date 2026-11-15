# AirPlay TV native core: UxPlay protocol library + libplist + Apple ALAC decoder + JNI bridge.
# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(airplaytv C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# ---- OpenSSL (static libcrypto built by scripts/build-openssl-android.sh) ----
set(OPENSSL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/openssl/${ANDROID_ABI})
if(NOT EXISTS ${OPENSSL_DIR}/lib/libcrypto.a)
  message(FATAL_ERROR "libcrypto.a missing for ${ANDROID_ABI}. Run scripts/build-openssl-android.sh first.")
endif()
add_library(crypto_static STATIC IMPORTED)
set_target_properties(crypto_static PROPERTIES
  IMPORTED_LOCATION ${OPENSSL_DIR}/lib/libcrypto.a
  INTERFACE_INCLUDE_DIRECTORIES ${OPENSSL_DIR}/include)

# ---- libplist 2.6.0 (LGPL-2.1) ----
file(GLOB PLIST_SRC ${TP}/libplist/src/*.c ${TP}/libplist/libcnary/*.c)
add_library(plist STATIC ${PLIST_SRC})
target_include_directories(plist
  PUBLIC ${TP}/libplist/include
  PRIVATE ${TP}/libplist/src ${TP}/libplist/libcnary/include)
target_compile_definitions(plist PRIVATE PACKAGE_VERSION="2.6.0" HAVE_STRNDUP HAVE_STRPTIME
  HAVE_GMTIME_R HAVE_LOCALTIME_R HAVE_TIMEGM)
target_compile_options(plist PRIVATE -fvisibility=hidden -Wno-unused-parameter)

# ---- Apple ALAC decoder (Apache-2.0) ----
add_library(alac STATIC
  ${TP}/alac/ALACDecoder.cpp ${TP}/alac/ALACBitUtilities.c ${TP}/alac/EndianPortable.c
  ${TP}/alac/ag_dec.c ${TP}/alac/dp_dec.c ${TP}/alac/matrix_dec.c)
target_include_directories(alac PUBLIC ${TP}/alac)
target_compile_options(alac PRIVATE -fvisibility=hidden -Wno-everything)

# ---- UxPlay protocol library (GPL-3.0) ----
file(GLOB UXPLAY_SRC ${TP}/uxplay/*.c ${TP}/uxplay/playfair/*.c ${TP}/uxplay/llhttp/*.c)
add_library(uxplay STATIC ${UXPLAY_SRC} dnssd_android.c)
target_include_directories(uxplay PUBLIC ${TP}/uxplay ${TP}/uxplay/playfair ${TP}/uxplay/llhttp)
target_compile_definitions(uxplay PRIVATE
  NOHOLD _LINUX _REENTRANT _FILE_OFFSET_BITS=64 PLIST_210 PLIST_230
  OPENSSL_API_COMPAT=0x10101000L)
# UxPlay uses assert() as defensive checks; keep them in release builds so a violated
# invariant aborts cleanly instead of becoming undefined behaviour.
target_compile_options(uxplay PRIVATE -UNDEBUG -fvisibility=hidden -Wno-unused-parameter
  -Wno-unused-variable -Wno-unused-function -Wno-deprecated-declarations)
target_link_libraries(uxplay PUBLIC plist crypto_static)

# ---- JNI bridge ----
add_library(airplaytv SHARED airplay_jni.c alac_bridge.cpp)
target_link_libraries(airplaytv PRIVATE uxplay alac log)
target_compile_options(airplaytv PRIVATE -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden)
# Strip unused code and keep the .so small on cheap TV boxes; 16 KB page alignment for Android 15+.
target_link_options(airplaytv PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
  -Wl,-z,max-page-size=16384)
