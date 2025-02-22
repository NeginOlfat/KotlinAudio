package com.doublesymmetry.kotlinaudio.models

import androidx.media3.common.Player

data class PlayWhenReadyChangeData(val playWhenReady: Boolean, val pausedBecauseReachedEnd: Boolean)
