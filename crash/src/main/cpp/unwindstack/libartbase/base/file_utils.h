/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ART_LIBARTBASE_BASE_FILE_UTILS_H_
#define ART_LIBARTBASE_BASE_FILE_UTILS_H_

#include <stdlib.h>

#include <string>
#include <string_view>

#include <android-base/logging.h>

#include "arch/instruction_set.h"

namespace art {

static constexpr const char kAndroidArtApexDefaultPath[] = "/apex/com.android.art";
static constexpr const char kArtApexDataDefaultPath[] = "/data/misc/apexdata/com.android.art";
static constexpr const char kAndroidConscryptApexDefaultPath[] = "/apex/com.android.conscrypt";
static constexpr const char kAndroidI18nApexDefaultPath[] = "/apex/com.android.i18n";

static constexpr const char kArtImageExtension[] = "art";

// These methods return the Android Root, which is the historical location of
// the Android "system" directory, containing the built Android artifacts. On
// target, this is normally "/system". On host this is usually a directory under
// the build tree, e.g. "$ANDROID_BUILD_TOP/out/host/linux-x86". The location of
// the Android Root can be overriden using the ANDROID_ROOT environment
// variable.
//
// Find $ANDROID_ROOT, /system, or abort.
std::string GetAndroidRoot();
// Find $ANDROID_ROOT, /system, or return an empty string.
std::string GetAndroidRootSafe(/*out*/ std::string* error_msg);

// Find $SYSTEM_EXT_ROOT, /system_ext, or abort.
std::string GetSystemExtRoot();
// Find $SYSTEM_EXT_ROOT, /system_ext, or return an empty string.
std::string GetSystemExtRootSafe(/*out*/ std::string* error_msg);

// These methods return the ART Root, which is the location of the (activated)
// ART APEX module. On target, this is normally "/apex/com.android.art". On
// host, this is usually a subdirectory of the Android Root, e.g.
// "$ANDROID_BUILD_TOP/out/host/linux-x86/com.android.art". The location of the
// ART root can be overridden using the ANDROID_ART_ROOT environment variable.
//
// Find $ANDROID_ART_ROOT, /apex/com.android.art, or abort.
std::string GetArtRoot();
// Find $ANDROID_ART_ROOT, /apex/com.android.art, or return an empty string.
std::string GetArtRootSafe(/*out*/ std::string* error_msg);

// Return the path to the directory containing the ART binaries.
std::string GetArtBinDir();

// Find $ANDROID_DATA, /data, or abort.
std::string GetAndroidData();
// Find $ANDROID_DATA, /data, or return an empty string.
std::string GetAndroidDataSafe(/*out*/ std::string* error_msg);

// Find $ART_APEX_DATA, /data/misc/apexdata/com.android.art, or abort.
std::string GetArtApexData();

// Returns the directory that contains the prebuilt version of the primary boot image (i.e., the one
// generated at build time).
std::string GetPrebuiltPrimaryBootImageDir();

// Returns the filename of the first mainline framework library.
std::string GetFirstMainlineFrameworkLibraryFilename(std::string* error_msg);

// Returns the default boot image location, based on the passed `android_root`.
// Returns an empty string if an error occurs.
// The default boot image location can only be used with the default bootclasspath (the value of the
// BOOTCLASSPATH environment variable).
std::string GetDefaultBootImageLocationSafe(const std::string& android_root,
                                            bool deny_art_apex_data_files,
                                            std::string* error_msg);

// Same as above, but fails if an error occurs.
std::string GetDefaultBootImageLocation(const std::string& android_root,
                                        bool deny_art_apex_data_files);

// Returns the boot image location that forces the runtime to run in JIT Zygote mode.
std::string GetJitZygoteBootImageLocation();

// Allows the name to be used for the dalvik cache directory (normally "dalvik-cache") to be
// overridden with a new value.
void OverrideDalvikCacheSubDirectory(std::string sub_dir);

// Return true if we found the dalvik cache and stored it in the dalvik_cache argument.
// `have_android_data` will be set to true if we have an ANDROID_DATA that exists,
// `dalvik_cache_exists` will be true if there is a dalvik-cache directory that is present.
// The flag `is_global_cache` tells whether this cache is /data/dalvik-cache.
void GetDalvikCache(const char* subdir, bool create_if_absent, std::string* dalvik_cache,
                    bool* have_android_data, bool* dalvik_cache_exists, bool* is_global_cache);

// Returns the absolute dalvik-cache path for a DexFile or OatFile. The path returned will be
// rooted at `cache_location`.
bool GetDalvikCacheFilename(const char* location, const char* cache_location,
                            std::string* filename, std::string* error_msg);

// Returns the absolute dalvik-cache path. The path may include the instruction set sub-directory
// if specified.
std::string GetApexDataDalvikCacheDirectory(InstructionSet isa);

// Gets the oat location in the ART APEX data directory for a DEX file installed anywhere other
// than in an APEX. Returns the oat filename if `location` is valid, empty string otherwise.
std::string GetApexDataOatFilename(std::string_view location, InstructionSet isa);

// Gets the odex location in the ART APEX data directory for a DEX file. Returns the odex filename
// if `location` is valid, empty string otherwise.
std::string GetApexDataOdexFilename(std::string_view location, InstructionSet isa);

// Gets the boot image in the ART APEX data directory for a DEX file installed anywhere other
// than in an APEX. Returns the image location if `dex_location` is valid, empty string otherwise.
std::string GetApexDataBootImage(std::string_view dex_location);

// Gets the image in the ART APEX data directory for a DEX file. Returns the image location if
// `dex_location` is valid, empty string otherwise.
std::string GetApexDataImage(std::string_view dex_location);

// Gets the name of a file in the ART APEX directory dalvik-cache. This method assumes the
// `dex_location` is for an application.
// Returns the location of the file in the dalvik-cache
std::string GetApexDataDalvikCacheFilename(std::string_view dex_location,
                                           InstructionSet isa,
                                           std::string_view file_extension);

// Returns the system location for an image. This method inserts the `isa` between the
// dirname and basename of `location`.
std::string GetSystemImageFilename(const char* location, InstructionSet isa);

// Returns the vdex filename for the given oat filename.
std::string GetVdexFilename(const std::string& oat_filename);

// Returns the dm filename for the given dex location.
std::string GetDmFilename(const std::string& dex_location);

// Returns the odex location on /system for a DEX file on /apex. The caller must make sure that
// `location` is on /apex.
std::string GetSystemOdexFilenameForApex(std::string_view location, InstructionSet isa);

// Returns `filename` with the text after the last occurrence of '.' replaced with
// `extension`. If `filename` does not contain a period, returns a string containing `filename`,
// a period, and `new_extension`.
// Example: ReplaceFileExtension("foo.bar", "abc") == "foo.abc"
//          ReplaceFileExtension("foo", "abc") == "foo.abc"
std::string ReplaceFileExtension(std::string_view filename, std::string_view new_extension);

// Return whether the location is on /apex/com.android.art
bool LocationIsOnArtModule(std::string_view location);

// Return whether the location is on /data/misc/apexdata/com.android.art/.
bool LocationIsOnArtApexData(std::string_view location);

// Return whether the location is on /apex/com.android.conscrypt
bool LocationIsOnConscryptModule(std::string_view location);

// Return whether the location is on /apex/com.android.i18n
bool LocationIsOnI18nModule(std::string_view location);

// Return whether the location is on system (i.e. android root).
bool LocationIsOnSystem(const std::string& location);

// Return whether the location is on system_ext
bool LocationIsOnSystemExt(const std::string& location);

// Return whether the location is on system/framework (i.e. $ANDROID_ROOT/framework).
bool LocationIsOnSystemFramework(std::string_view location);

// Return whether the location is on system_ext/framework
bool LocationIsOnSystemExtFramework(std::string_view location);

// Return whether the location is on /apex/.
bool LocationIsOnApex(std::string_view location);

// If the given location is /apex/<apexname>/..., return <apexname>, otherwise return an empty
// string. Note that the result is a view into full_path and is valid only as long as it is.
std::string_view ApexNameFromLocation(std::string_view full_path);

// Returns whether the location is trusted for loading oat files. Trusted locations are protected
// by dm-verity or fs-verity. The recognized locations are on /system or
// /data/misc/apexdata/com.android.art.
bool LocationIsTrusted(const std::string& location, bool trust_art_apex_data_files);

// Compare the ART module root against android root. Returns true if they are
// both known and distinct. This is meant to be a proxy for 'running with apex'.
bool ArtModuleRootDistinctFromAndroidRoot();

// dup(2), except setting the O_CLOEXEC flag atomically, when possible.
int DupCloexec(int fd);

// Returns true if `path` begins with a slash.
inline bool IsAbsoluteLocation(const std::string& path) { return !path.empty() && path[0] == '/'; }

}  // namespace art

#endif  // ART_LIBARTBASE_BASE_FILE_UTILS_H_
