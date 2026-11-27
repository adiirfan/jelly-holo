/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import android.media.MediaCodecList
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/** Which formats this phone plays well, so a download picks one it can. */
object CodecSupport {
    private val known = ConcurrentHashMap<String, Boolean>()

    /**
     * Pictures: true when the phone has a hardware decoder for [mime] (a
     * software-only one, such as AV1 on older phones, plays slowly and drains
     * the battery). Sound: true when any decoder exists.
     */
    fun playable(mime: String): Boolean = known.getOrPut(mime) {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        if (!mime.startsWith("video/")) {
            decoders.isNotEmpty()
        } else {
            decoders.any { info ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                }
            }
        }
    }
}
