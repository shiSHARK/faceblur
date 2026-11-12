// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import kotlin.test.Test
import kotlin.test.assertEquals

class FaceBlurInfoTest {
    @Test
    fun describeContainsNameAndLicence() {
        assertEquals("FaceBlur (Apache-2.0)", FaceBlurInfo.describe())
    }
}
