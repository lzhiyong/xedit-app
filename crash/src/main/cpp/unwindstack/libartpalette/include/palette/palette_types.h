/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef ART_LIBARTPALETTE_INCLUDE_PALETTE_PALETTE_TYPES_H_
#define ART_LIBARTPALETTE_INCLUDE_PALETTE_PALETTE_TYPES_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif  // __cplusplus

using palette_status_t = int32_t;

// Palette function return value when the function completed successfully.
#define PALETTE_STATUS_OK                 ((palette_status_t) 0)

// Palette function return value when the function called yielded and error captured by `errno`.
#define PALETTE_STATUS_CHECK_ERRNO        ((palette_status_t) 1)

// Palette function return value when an argument to the function was invalid.
#define PALETTE_STATUS_INVALID_ARGUMENT   ((palette_status_t) 2)

// Palette function return value when the function called is not supported. This value allows
// palette functions to be retired across Android versions. Palette functions can never be removed
// from the palette interface by design.
#define PALETTE_STATUS_NOT_SUPPORTED      ((palette_status_t) 3)

// Palette function return value when the function failed for unknown reasons.
#define PALETTE_STATUS_FAILED_CHECK_LOG   ((palette_status_t) 4)

#ifdef __cplusplus
}
#endif  // __cplusplus

#endif  // ART_LIBARTPALETTE_INCLUDE_PALETTE_PALETTE_TYPES_H_
