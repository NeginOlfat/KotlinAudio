package com.doublesymmetry.kotlinaudio.models

import android.os.Bundle
import androidx.media3.common.Rating as RatingCompat

sealed class MediaSessionCallback {
    class RATING(val rating: RatingCompat, extras: Bundle?): MediaSessionCallback()
    object PLAY : MediaSessionCallback()
    object PAUSE : MediaSessionCallback()
    object NEXT : MediaSessionCallback()
    object PREVIOUS : MediaSessionCallback()
    object FORWARD : MediaSessionCallback()
    object REWIND : MediaSessionCallback()
    object STOP : MediaSessionCallback()
    class SEEK(val positionMs: Long): MediaSessionCallback()
}
