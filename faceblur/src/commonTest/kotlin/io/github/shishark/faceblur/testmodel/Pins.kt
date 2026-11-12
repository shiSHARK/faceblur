// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.testmodel

import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.internal.model.Sha256

/** A well-formed SHA-256 for profiles that are never used to load a model (range and decoder tests). */
internal const val ANY_SHA256: String = "0000000000000000000000000000000000000000000000000000000000000000"

/** This profile with the SHA-256 of [bytes] as its required pin (the test approves the model it just built). */
internal fun DetectorProfile.pinnedTo(bytes: ByteArray): DetectorProfile =
    DetectorProfile(format, inputWidth, inputHeight, scoreThreshold, Sha256.hex(bytes), minMaskExpandX, minMaskExpandY)
