package com.doublesymmetry.kotlinaudio.players.components

import com.doublesymmetry.kotlinaudio.models.AudioItemHolder
import androidx.media3.common.MediaItem

fun MediaItem.getAudioItemHolder(): AudioItemHolder {
    return localConfiguration!!.tag as AudioItemHolder
}
