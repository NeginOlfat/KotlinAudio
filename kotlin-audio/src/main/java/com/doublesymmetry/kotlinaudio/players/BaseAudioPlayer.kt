package com.doublesymmetry.kotlinaudio.players

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.CallSuper
import com.doublesymmetry.kotlinaudio.event.EventHolder
import com.doublesymmetry.kotlinaudio.event.NotificationEventHolder
import com.doublesymmetry.kotlinaudio.event.PlayerEventHolder
import com.doublesymmetry.kotlinaudio.models.AudioContentType
import com.doublesymmetry.kotlinaudio.models.AudioItem
import com.doublesymmetry.kotlinaudio.models.AudioItemHolder
import com.doublesymmetry.kotlinaudio.models.AudioItemTransitionReason
import com.doublesymmetry.kotlinaudio.models.AudioPlayerState
import com.doublesymmetry.kotlinaudio.models.BufferConfig
import com.doublesymmetry.kotlinaudio.models.CacheConfig
import com.doublesymmetry.kotlinaudio.models.DefaultPlayerOptions
import com.doublesymmetry.kotlinaudio.models.MediaSessionCallback
import com.doublesymmetry.kotlinaudio.models.MediaType
import com.doublesymmetry.kotlinaudio.models.PlayWhenReadyChangeData
import com.doublesymmetry.kotlinaudio.models.PlaybackError
import com.doublesymmetry.kotlinaudio.models.PlayerConfig
import com.doublesymmetry.kotlinaudio.models.PlayerOptions
import com.doublesymmetry.kotlinaudio.models.PositionChangedReason
import com.doublesymmetry.kotlinaudio.models.WakeMode
import com.doublesymmetry.kotlinaudio.notification.NotificationManager
import com.doublesymmetry.kotlinaudio.players.components.PlayerCache
import com.doublesymmetry.kotlinaudio.players.components.getAudioItemHolder
import com.doublesymmetry.kotlinaudio.utils.isUriLocalFile
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Rating
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.Util
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.CommandButton
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLoadControl.Builder
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
import androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.smoothstreaming.DefaultSsChunkSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import java.util.Locale
import java.util.concurrent.TimeUnit


/**
 * [BaseAudioPlayer] is the core class responsible for managing media playback using ExoPlayer.
 *
 * It handles:
 * - Playback controls (play, pause, seek, etc.)
 * - Media session management
 * - Dynamic updates to notifications
 * - Audio focus handling
 *
 * Subclasses can extend this class to implement additional functionality, such as queue management.
 *
 * @param context The application context used to initialize the player.
 * @param playerConfig Configuration options for the player (e.g., buffer size, audio focus handling).
 * @param bufferConfig Optional buffer configuration for optimizing playback performance.
 * @param cacheConfig Optional cache configuration for offline playback.
 */
@UnstableApi
abstract class BaseAudioPlayer internal constructor(
    internal val context: Context,
    playerConfig: PlayerConfig,
    private val bufferConfig: BufferConfig? = null,
    private val cacheConfig: CacheConfig? = null
) : MediaSession.Callback {
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setHandleAudioBecomingNoisy(playerConfig.handleAudioBecomingNoisy)
        .setWakeMode(
                when (playerConfig.wakeMode) {
                    WakeMode.NONE -> C.WAKE_MODE_NONE
                    WakeMode.LOCAL -> C.WAKE_MODE_LOCAL
                    WakeMode.NETWORK -> C.WAKE_MODE_NETWORK
                }
            )
            .apply {
                if (bufferConfig != null) setLoadControl(setupBuffer(bufferConfig))
            }
        .build()

    private var cache: SimpleCache? = null
    private val scope = MainScope()
    private var playerConfig: PlayerConfig = playerConfig

    val notificationManager: NotificationManager

    open val playerOptions: PlayerOptions = DefaultPlayerOptions()

    private val mediaSession = MediaSession.Builder(context, exoPlayer)
        .setId("KotlinAudioPlayer")
        .setCallback(this@BaseAudioPlayer)
        .build()

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        command: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        when (command.customAction) {
            REWIND -> playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.REWIND)
            FORWARD -> playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.FORWARD)
            STOP -> playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.STOP)
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    open val currentItem: AudioItem?
        get() = exoPlayer.currentMediaItem?.getAudioItemHolder()?.audioItem

    var playbackError: PlaybackError? = null
    var playerState: AudioPlayerState = AudioPlayerState.IDLE
        private set(value) {
            if (value != field) {
                field = value
                playerEventHolder.updateAudioPlayerState(value)
            }
        }

    var playWhenReady: Boolean
        get() = exoPlayer.playWhenReady
        set(value) {
            exoPlayer.playWhenReady = value
        }

    val duration: Long
        get() {
            return if (exoPlayer.duration == C.TIME_UNSET) 0
            else exoPlayer.duration
        }

    val isCurrentMediaItemLive: Boolean
        get() = exoPlayer.isCurrentMediaItemLive

    private var oldPosition = 0L

    val position: Long
        get() {
            return if (exoPlayer.currentPosition == C.INDEX_UNSET.toLong()) 0
            else exoPlayer.currentPosition
        }

    val bufferedPosition: Long
        get() {
            return if (exoPlayer.bufferedPosition == C.INDEX_UNSET.toLong()) 0
            else exoPlayer.bufferedPosition
        }

    var volume: Float
        get() = exoPlayer.volume
        set(value) {
            exoPlayer.volume = value * volumeMultiplier
        }

    var playbackSpeed: Float
        get() = exoPlayer.playbackParameters.speed
        set(value) {
            exoPlayer.setPlaybackSpeed(value)
        }

    var automaticallyUpdateNotificationMetadata: Boolean = true

    private var volumeMultiplier = 1f
        private set(value) {
            field = value
            volume = volume
        }

    val isPlaying
        get() = exoPlayer.isPlaying

    private val notificationEventHolder = NotificationEventHolder()
    private val playerEventHolder = PlayerEventHolder()

    val event = EventHolder(notificationEventHolder, playerEventHolder)

     var ratingType: Int = 0
         set(value) {
             field = value
            // Update the media session with rating support
             mediaSession.setCustomLayout(
                 if (value != 0) {
                    listOf(
                        CommandButton.Builder()
                            .setDisplayName("Rate")
                            .setSessionCommand(SessionCommand(COMMAND_SET_RATING, Bundle.EMPTY))
                            .build()
                            )
                        } else {
                            emptyList()
                }
             )
         }

    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        rating: Rating
    ): ListenableFuture<SessionResult> {
        playerEventHolder.updateOnPlayerActionTriggeredExternally(
            MediaSessionCallback.RATING(rating, null)
        )
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    /**
    * Initializes the player with the given configuration.
    * This includes setting up the ExoPlayer instance, buffer settings, and audio attributes.
    */
    init {
        if (cacheConfig != null) {
            cache = PlayerCache.getInstance(context, cacheConfig)
        }

       val playerToUse =
           if (playerConfig.interceptPlayerActionsTriggeredExternally) createForwardingPlayer() else exoPlayer

        // Set audio attributes for media playback
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(
                when (playerConfig.audioContentType) {
                    AudioContentType.MUSIC -> C.AUDIO_CONTENT_TYPE_MUSIC
                    AudioContentType.SPEECH -> C.AUDIO_CONTENT_TYPE_SPEECH
                    AudioContentType.SONIFICATION -> C.AUDIO_CONTENT_TYPE_SONIFICATION
                    AudioContentType.MOVIE -> C.AUDIO_CONTENT_TYPE_MOVIE
                    else -> C.AUDIO_CONTENT_TYPE_UNKNOWN
                }
            )
            .build()
        exoPlayer.setAudioAttributes(audioAttributes, playerConfig.handleAudioFocus)

        notificationManager = NotificationManager(
            context,
            playerToUse,
            mediaSession,
            NotificationEventHolder(),
            PlayerEventHolder()
        )

        exoPlayer.addListener(PlayerListener())

        playerEventHolder.updateAudioPlayerState(AudioPlayerState.IDLE)
    }

   private fun createForwardingPlayer(): ForwardingPlayer {
       return object : ForwardingPlayer(exoPlayer) {
           override fun play() {
               playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PLAY)
           }

           override fun pause() {
               playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PAUSE)
           }

           override fun seekToNext() {
               playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.NEXT)
           }

           override fun seekToPrevious() {
               playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PREVIOUS)
           }

           override fun seekForward() {
               playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.FORWARD)
           }

           override fun seekBack() {
               playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.REWIND)
           }

           override fun stop() {
               playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.STOP)
           }

           override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
               playerEventHolder.updateOnPlayerActionTriggeredExternally(
                   MediaSessionCallback.SEEK(
                       positionMs
                   )
               )
           }

           override fun seekTo(positionMs: Long) {
               playerEventHolder.updateOnPlayerActionTriggeredExternally(
                   MediaSessionCallback.SEEK(
                       positionMs
                   )
               )
           }
       }
   }

    internal fun updateNotificationIfNecessary(overrideAudioItem: AudioItem? = null) {
        if (automaticallyUpdateNotificationMetadata) {
            notificationManager.overrideAudioItem = overrideAudioItem
            notificationManager.getCurrentMediaMetadata()
        }
    }

   private fun setupBuffer(bufferConfig: BufferConfig): DefaultLoadControl {
       bufferConfig.apply {
           val multiplier =
               DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / DEFAULT_BUFFER_FOR_PLAYBACK_MS
           val minBuffer =
               if (minBuffer != null && minBuffer != 0) minBuffer else DEFAULT_MIN_BUFFER_MS
           val maxBuffer =
               if (maxBuffer != null && maxBuffer != 0) maxBuffer else DEFAULT_MAX_BUFFER_MS
           val playBuffer =
               if (playBuffer != null && playBuffer != 0) playBuffer else DEFAULT_BUFFER_FOR_PLAYBACK_MS
           val backBuffer =
               if (backBuffer != null && backBuffer != 0) backBuffer else DEFAULT_BACK_BUFFER_DURATION_MS

           return Builder()
               .setBufferDurationsMs(minBuffer, maxBuffer, playBuffer, playBuffer * multiplier)
               .setBackBuffer(backBuffer, false)
               .build()
       }
   }

    /**
     * Will replace the current item with a new one and load it into the player.
     * @param item The [AudioItem] to replace the current one.
     * @param playWhenReady Whether playback starts automatically.
     */
    open fun load(item: AudioItem, playWhenReady: Boolean = true) {
        exoPlayer.playWhenReady = playWhenReady
        load(item)
    }

    /**
     * Will replace the current item with a new one and load it into the player.
     * @param item The [AudioItem] to replace the current one.
     */
    open fun load(item: AudioItem) {
        val mediaSource = getMediaSourceFromAudioItem(item)
        exoPlayer.addMediaSource(mediaSource)
        exoPlayer.prepare()
    }

   fun togglePlaying() {
       if (exoPlayer.isPlaying) {
           pause()
       } else {
           play()
       }
   }

   var skipSilence: Boolean
       get() = exoPlayer.skipSilenceEnabled
       set(value) {
            exoPlayer.skipSilenceEnabled = value;
       }

    fun play() {
        exoPlayer.play()
         if (currentItem != null) {
            exoPlayer.prepare()
        }
    }

    fun prepare() {
        if (currentItem != null) {
            exoPlayer.prepare()
        }
    }

    fun pause() {
        exoPlayer.pause()
    }

    /**
     * Stops playback, without clearing the active item. Calling this method will cause the playback
     * state to transition to AudioPlayerState.IDLE and the player will release the loaded media and
     * resources required for playback.
     */
    @CallSuper
    open fun stop() {
        playerState = AudioPlayerState.STOPPED
        exoPlayer.playWhenReady = false
        exoPlayer.stop()
    }

    @CallSuper
    open fun clear() {
        exoPlayer.clearMediaItems()
    }

    /**
     * Pause playback whenever an item plays to its end.
     */
    fun setPauseAtEndOfItem(pause: Boolean) {
        exoPlayer.pauseAtEndOfMediaItems = pause
    }

    /**
     * Stops and destroys the player. Only call this when you are finished using the player, otherwise use [pause].
     */
    @CallSuper
    open fun destroy() {
        scope.cancel() 
        stop()
        notificationManager.destroy()
        exoPlayer.release()
        cache?.release()
        cache = null
        mediaSession.release()
    }

    open fun seek(duration: Long, unit: TimeUnit) {
        val positionMs = TimeUnit.MILLISECONDS.convert(duration, unit)
        exoPlayer.seekTo(positionMs)
    }

   open fun seekBy(offset: Long, unit: TimeUnit) {
       val positionMs = exoPlayer.currentPosition + TimeUnit.MILLISECONDS.convert(offset, unit)
       exoPlayer.seekTo(positionMs)
   }

    protected fun getMediaSourceFromAudioItem(audioItem: AudioItem): MediaSource {
        val uri = Uri.parse(audioItem.audioUrl)
        val mediaItem = MediaItem.Builder()
            .setUri(audioItem.audioUrl)
            .setTag(AudioItemHolder(audioItem))
            .build()

        val userAgent =
            if (audioItem.options == null || audioItem.options!!.userAgent.isNullOrBlank()) {
                Util.getUserAgent(context, APPLICATION_NAME)
            } else {
                audioItem.options!!.userAgent
            }

        val factory: DataSource.Factory = when {
            audioItem.options?.resourceId != null -> {
                val raw = RawResourceDataSource(context)
                raw.open(DataSpec(uri))
                DataSource.Factory { raw }
            }
            isUriLocalFile(uri) -> {
                DefaultDataSource.Factory(context)
            }
            else -> {
                val tempFactory = DefaultHttpDataSource.Factory().apply {
                    setUserAgent(userAgent)
                    setAllowCrossProtocolRedirects(true)

                    audioItem.options?.headers?.let {
                        setDefaultRequestProperties(it.toMap())
                    }
                }

                enableCaching(tempFactory)
            }
        }

        return when (audioItem.type) {
            MediaType.DASH -> createDashSource(mediaItem, factory)
            MediaType.HLS -> createHlsSource(mediaItem, factory)
            MediaType.SMOOTH_STREAMING -> createSsSource(mediaItem, factory)
            else -> createProgressiveSource(mediaItem, factory)
        }
    }

    private fun createDashSource(mediaItem: MediaItem, factory: DataSource.Factory?): MediaSource {
        return DashMediaSource.Factory(DefaultDashChunkSource.Factory(factory!!), factory)
            .createMediaSource(mediaItem)
    }

    private fun createHlsSource(mediaItem: MediaItem, factory: DataSource.Factory?): MediaSource {
        return HlsMediaSource.Factory(factory!!)
            .createMediaSource(mediaItem)
    }

    private fun createSsSource(mediaItem: MediaItem, factory: DataSource.Factory?): MediaSource {
        return SsMediaSource.Factory(DefaultSsChunkSource.Factory(factory!!), factory)
            .createMediaSource(mediaItem)
    }

    private fun createProgressiveSource(
        mediaItem: MediaItem,
        factory: DataSource.Factory
    ): ProgressiveMediaSource {
        return ProgressiveMediaSource.Factory(
            factory, DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
        )
            .createMediaSource(mediaItem)
    }

    private fun enableCaching(factory: DataSource.Factory): DataSource.Factory {
        return if (cache == null || cacheConfig == null || (cacheConfig.maxCacheSize ?: 0) <= 0) {
            factory
        } else {
            CacheDataSource.Factory().apply {
                setCache(this@BaseAudioPlayer.cache!!)
                setUpstreamDataSourceFactory(factory)
                setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            }
        }
    }

    companion object {
        const val APPLICATION_NAME = "react-native-track-player"
        private const val COMMAND_SET_RATING = "android.media3.session.command.SET_RATING"
        private const val REWIND = "rewind"
        private const val FORWARD = "forward"
        private const val STOP = "stop"
    }

    inner class PlayerListener : Player.Listener {
        /**
         * Called when there is metadata associated with the current playback time.
         */
        override fun onMetadata(metadata: Metadata) {
            playerEventHolder.updateOnTimedMetadata(metadata)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            playerEventHolder.updateOnCommonMetadata(mediaMetadata)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
                notificationManager.invalidate()
        }
        /**
         * A position discontinuity occurs when the playing period changes, the playback position
         * jumps within the period currently being played, or when the playing period has been
         * skipped or removed.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            this@BaseAudioPlayer.oldPosition = oldPosition.positionMs

            when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.AUTO(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SEEK -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SEEK(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SEEK_FAILED(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_REMOVE -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.QUEUE_CHANGED(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_SKIP -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SKIPPED_PERIOD(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_INTERNAL -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.UNKNOWN(oldPosition.positionMs, newPosition.positionMs)
                )
            }
        }

        /**
         * Called when playback transitions to a media item or starts repeating a media item
         * according to the current repeat mode. Note that this callback is also called when the
         * playlist becomes non-empty or empty as a consequence of a playlist change.
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.AUTO(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.QUEUE_CHANGED(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.REPEAT(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.SEEK_TO_ANOTHER_AUDIO_ITEM(oldPosition)
                )
            }

            updateNotificationIfNecessary()
        }

        /**
         * Called when the value returned from Player.getPlayWhenReady() changes.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val pausedBecauseReachedEnd = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            playerEventHolder.updatePlayWhenReadyChange(PlayWhenReadyChangeData(playWhenReady, pausedBecauseReachedEnd))
        }

        /**
         * The generic onEvents callback provides access to the Player object and specifies the set
         * of events that occurred together. It’s always called after the callbacks that correspond
         * to the individual events.
         */
        override fun onEvents(player: Player, events: Player.Events) {
            // Note that it is necessary to set `playerState` in order, since each mutation fires an
            // event.
            for (i in 0 until events.size()) {
                when (events[i]) {
                    Player.EVENT_PLAYBACK_STATE_CHANGED -> {
                        val state = when (player.playbackState) {
                            Player.STATE_BUFFERING -> AudioPlayerState.BUFFERING
                            Player.STATE_READY -> AudioPlayerState.READY
                            Player.STATE_IDLE ->
                                // Avoid transitioning to idle from error or stopped
                                if (
                                    playerState == AudioPlayerState.ERROR ||
                                    playerState == AudioPlayerState.STOPPED
                                )
                                    null
                                else
                                    AudioPlayerState.IDLE
                            Player.STATE_ENDED ->
                                if (player.mediaItemCount > 0) AudioPlayerState.ENDED
                                else AudioPlayerState.IDLE
                            else -> null // noop
                        }
                        if (state != null && state != playerState) {
                            playerState = state
                        }
                    }
                    Player.EVENT_MEDIA_ITEM_TRANSITION -> {
                        playbackError = null
                        if (currentItem != null) {
                            playerState = AudioPlayerState.LOADING
                            if (isPlaying) {
                                playerState = AudioPlayerState.READY
                                playerState = AudioPlayerState.PLAYING
                            }
                        }
                    }
                    Player.EVENT_PLAY_WHEN_READY_CHANGED -> {
                        if (!player.playWhenReady && playerState != AudioPlayerState.STOPPED) {
                            playerState = AudioPlayerState.PAUSED
                        }
                    }
                    Player.EVENT_IS_PLAYING_CHANGED -> {
                        if (player.isPlaying) {
                            playerState = AudioPlayerState.PLAYING
                        }
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val _playbackError = PlaybackError(
                error.errorCodeName
                    .replace("ERROR_CODE_", "")
                    .lowercase(Locale.getDefault())
                    .replace("_", "-"),
                error.message
            )
            playerEventHolder.updatePlaybackError(_playbackError)
            playbackError = _playbackError
            playerState = AudioPlayerState.ERROR
        }
    }
}