// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask.planted

/**
 * An error whose simple name is `OutOfMemoryError` but which is not the platform's out-of-memory error: the stage must
 * rethrow it unchanged (the backstop matches the exact qualified class name only).
 */
class OutOfMemoryError : Error("planted, same simple name")
