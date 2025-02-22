package com.doublesymmetry.kotlinaudio.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.common.Rating as RatingCompat
import androidx.media3.ui.PlayerNotificationManager
import androidx.media3.session.MediaSession
import androidx.media3.session.CommandButton
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import com.doublesymmetry.kotlinaudio.R
import com.doublesymmetry.kotlinaudio.event.NotificationEventHolder
import com.doublesymmetry.kotlinaudio.event.PlayerEventHolder
import com.doublesymmetry.kotlinaudio.models.AudioItem
import com.doublesymmetry.kotlinaudio.models.MediaSessionCallback
import com.doublesymmetry.kotlinaudio.models.NotificationButton
import com.doublesymmetry.kotlinaudio.models.NotificationConfig
import com.doublesymmetry.kotlinaudio.models.NotificationState
import com.doublesymmetry.kotlinaudio.players.components.getAudioItemHolder
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import java.io.ByteArrayOutputStream

/**
 * This class manages media notifications for the audio player using Media3's [PlayerNotificationManager].
 *
 * It dynamically updates the notification based on the current playback state and user-defined configurations.
 *
 * @param context The application context.
 * @param player The [Player] instance used for playback.
 * @param mediaSession The [MediaSession] instance for handling media commands.
 * @param event The [NotificationEventHolder] for notifying changes in the notification state.
 * @param playerEventHolder The [PlayerEventHolder] for triggering external player actions.
 */
@UnstableApi
class NotificationManager internal constructor(
    private val context: Context,
    private val player: Player,
    private val mediaSession: MediaSession,
    val event: NotificationEventHolder,
    val playerEventHolder: PlayerEventHolder
) : PlayerNotificationManager.NotificationListener {
    private var pendingIntent: PendingIntent? = null
    private val descriptionAdapter = object : PlayerNotificationManager.MediaDescriptionAdapter {
        override fun getCurrentContentTitle(player: Player): CharSequence {
            return getTitle() ?: ""
        }

        override fun createCurrentContentIntent(player: Player): PendingIntent? {
            return pendingIntent
        }

        override fun getCurrentContentText(player: Player): CharSequence? {
            return getArtist() ?: ""
        }

        override fun getCurrentSubText(player: Player): CharSequence? {
            return player.mediaMetadata.displayTitle
        }

        override fun getCurrentLargeIcon(
            player: Player,
            callback: PlayerNotificationManager.BitmapCallback,
        ): Bitmap? {
            val bitmap = getCachedArtworkBitmap()
            if (bitmap != null) {
                return bitmap
            }
            val artwork = getMediaItemArtworkUrl()
            val headers = getNetworkHeaders()
            val holder = player.currentMediaItem?.getAudioItemHolder()
            if (artwork != null && holder?.artworkBitmap == null) {
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(artwork)
                        .headers(headers)
                        .target { result ->
                            val resultBitmap = (result as BitmapDrawable).bitmap
                            holder?.artworkBitmap = resultBitmap
                            invalidate()
                        }
                        .build()
                )
            }
            return iconPlaceholder
        }
    }

    private var internalNotificationManager: PlayerNotificationManager? = null
    private val scope = MainScope()
    private val buttons = mutableSetOf<NotificationButton?>()
    private var iconPlaceholder = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    private var notificationMetadataBitmap: Bitmap? = null
    private var notificationMetadataArtworkDisposable: Disposable? = null

   /**
    * The item that should be used for the notification
    * This is used when the user manually sets the notification item
    *
    * _Note: If [BaseAudioPlayer.automaticallyUpdateNotificationMetadata] is true, this will
    * get override on a track change_
    */
    internal var overrideAudioItem: AudioItem? = null
        set(value) {
            field = value
            invalidate()
        }

    private fun getTitle(index: Int? = null): String? {
        val mediaItem = if (index == null) player.currentMediaItem else player.getMediaItemAt(index)

        val audioItem = mediaItem?.getAudioItemHolder()?.audioItem
        return overrideAudioItem?.title
            ?:mediaItem?.mediaMetadata?.title?.toString()
            ?: audioItem?.title
    }

    private fun getArtist(index: Int? = null): String? {
        val mediaItem = if (index == null) player.currentMediaItem else player.getMediaItemAt(index)
        val audioItem = mediaItem?.getAudioItemHolder()?.audioItem

        return overrideAudioItem?.artist
            ?: mediaItem?.mediaMetadata?.artist?.toString()
            ?: mediaItem?.mediaMetadata?.albumArtist?.toString()
            ?: audioItem?.artist
    }

   private fun getGenre(index: Int? = null): String? {
       val mediaItem = if (index == null) player.currentMediaItem else player.getMediaItemAt(index)
       return mediaItem?.mediaMetadata?.genre?.toString()
   }

   private fun getAlbumTitle(index: Int? = null): String? {
       val mediaItem = if (index == null) player.currentMediaItem else player.getMediaItemAt(index)
       return mediaItem?.mediaMetadata?.albumTitle?.toString()
           ?: mediaItem?.getAudioItemHolder()?.audioItem?.albumTitle
   }

   private fun getArtworkUrl(index: Int? = null): Uri? {
       return getMediaItemArtworkUrl(index)?.toUri()
   }

    private fun getMediaItemArtworkUrl(index: Int? = null): String? {
        val mediaItem = if (index == null) player.currentMediaItem else player.getMediaItemAt(index)

        return overrideAudioItem?.artwork
            ?: mediaItem?.mediaMetadata?.artworkUri?.toString()
            ?: mediaItem?.getAudioItemHolder()?.audioItem?.artwork
    }

    private fun getNetworkHeaders(): Headers {
        return player.currentMediaItem?.getAudioItemHolder()?.audioItem?.options?.headers?.toHeaders() ?: Headers.Builder().build()
    }

    /**
     * Returns the cached artwork bitmap for the current media item.
     * Bitmap might be cached if the media item has extracted one from the media file
     * or if a user is setting custom data for the notification.
     */
    private fun getCachedArtworkBitmap(index: Int? = null): Bitmap? {
        val mediaItem = if (index == null) player.currentMediaItem else player.getMediaItemAt(index)
        val isCurrent = index == null || index == player.currentMediaItemIndex
        val artworkData = player.mediaMetadata.artworkData

        return if (isCurrent && overrideAudioItem != null) {
            notificationMetadataBitmap
        } else if (isCurrent && artworkData != null) {
            BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
        } else {
            mediaItem?.getAudioItemHolder()?.artworkBitmap
        }
    }

   private fun getDuration(index: Int? = null): Long {
       val mediaItem = if (index == null) player.currentMediaItem
           else player.getMediaItemAt(index)

       return if (player.isCurrentMediaItemDynamic || player.duration == C.TIME_UNSET) {
           overrideAudioItem?.duration ?: mediaItem?.getAudioItemHolder()?.audioItem?.duration ?: -1
       } else {
           overrideAudioItem?.duration ?: player.duration
       }
   }

   private fun getUserRating(index: Int? = null): RatingCompat? {
       val mediaItem = if (index == null) player.currentMediaItem
           else player.getMediaItemAt(index)
       return mediaItem?.mediaMetadata?.userRating
   }

    var showPlayPauseButton = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUsePlayPauseActions(value)
            }
        }

    var showStopButton = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseStopAction(value)
            }
        }

    var showForwardButton = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseFastForwardAction(value)
            }
        }

    /**
     * Controls whether or not this button should appear when the notification is compact (collapsed).
     */
    var showForwardButtonCompact = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseFastForwardActionInCompactView(value)
            }
        }

    var showRewindButton = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseRewindAction(value)
            }
        }

    /**
     * Controls whether or not this button should appear when the notification is compact (collapsed).
     */
    var showRewindButtonCompact = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseRewindActionInCompactView(value)
            }
        }

    var showNextButton = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseNextAction(value)
            }
        }

    /**
     * Controls whether or not this button should appear when the notification is compact (collapsed).
     */
    var showNextButtonCompact = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUseNextActionInCompactView(value)
            }
        }

    var showPreviousButton = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUsePreviousAction(value)
            }
        }

    /**
     * Controls whether or not this button should appear when the notification is compact (collapsed).
     */
    var showPreviousButtonCompact = false
        set(value) {
            scope.launch {
                field = value
                internalNotificationManager?.setUsePreviousActionInCompactView(value)
            }
        }

    var stopIcon: Int? = null
    var forwardIcon: Int? = null
    var rewindIcon: Int? = null

    private val customActionReceiver = object : PlayerNotificationManager.CustomActionReceiver {
        override fun createCustomActions(
            context: Context,
            instanceId: Int
        ): MutableMap<String, NotificationCompat.Action> {
            if (!needsCustomActionsToAddMissingButtons) return mutableMapOf()
            return mutableMapOf(
                REWIND to createNotificationAction(
                    rewindIcon ?: DEFAULT_REWIND_ICON,
                    REWIND,
                    instanceId
                ),
                FORWARD to createNotificationAction(
                    forwardIcon ?: DEFAULT_FORWARD_ICON,
                    FORWARD,
                    instanceId
                ),
                STOP to createNotificationAction(
                    stopIcon ?: DEFAULT_STOP_ICON,
                    STOP,
                    instanceId
                )
            )
        }

        override fun getCustomActions(player: Player): List<String> {
            if (!needsCustomActionsToAddMissingButtons) return emptyList()
            return buttons.mapNotNull {
                when (it) {
                    is NotificationButton.BACKWARD -> {
                        REWIND
                    }
                    is NotificationButton.FORWARD -> {
                        FORWARD
                    }
                    is NotificationButton.STOP -> {
                        STOP
                    }
                    else -> {
                        null
                    }
                }
            }
        }

        override fun onCustomAction(player: Player, action: String,  intent: Intent) {
            handlePlayerAction(action)
        }
    }

    init {
        // Create a notification channel for Android Oreo+ devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.playback_channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.createNotificationChannel(channel)
        }

          // Initialize the PlayerNotificationManager.
        internalNotificationManager = PlayerNotificationManager.Builder(context, NOTIFICATION_ID, CHANNEL_ID)
            .setChannelNameResourceId(R.string.playback_channel_name)
            .setMediaDescriptionAdapter(descriptionAdapter)
            .setCustomActionReceiver(customActionReceiver)
            .setNotificationListener(this)
            .build()
            .apply {
                setPlayer(player) // Link the player to the notification manager
                setMediaSessionToken(mediaSession.sessionCompatToken)
            }
    }

   /**
     * Overrides the notification metadata with the given [AudioItem].
     *
     * _Note: If [BaseAudioPlayer.automaticallyUpdateNotificationMetadata] is true, this will
     * get overridden on a track change._
     *
     * @param item The [AudioItem] to override the notification metadata.
    */
    public fun overrideMetadata(item: AudioItem) {
        overrideAudioItem = item
    }

   /**
    * Returns the current media metadata as a [MediaMetadata] object.
    */
   public fun getCurrentMediaMetadata(): MediaMetadata {
       val currentItemMetadata = player.currentMediaItem?.mediaMetadata
       return MediaMetadata.Builder()
           .setArtist(getArtist())
           .setTitle(getTitle())
           .setDisplayTitle(getTitle())
           .setSubtitle(currentItemMetadata?.subtitle)
           .setDescription(currentItemMetadata?.description)
           .setAlbumTitle(getAlbumTitle())
           .setGenre(getGenre())
           .setExtras(Bundle().apply {
                putLong("duration_ms", player.duration)  // Custom key
                })
           .setArtworkUri(getArtworkUrl())
           .setUserRating(getUserRating())
           .build()
   }

    private fun createNotificationAction(
        drawable: Int,
        action: String,
        instanceId: Int
    ): NotificationCompat.Action {
        val intent: Intent = Intent(action).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            instanceId,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )
        return NotificationCompat.Action.Builder(drawable, action, pendingIntent).build()
    }

    private fun handlePlayerAction(action: String) {
        when (action) {
            REWIND -> {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.REWIND)
            }
            FORWARD -> {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.FORWARD)
            }
            STOP -> {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.STOP)
            }

        }
    }


    fun invalidate() {
        internalNotificationManager?.invalidate()
    }

    /**
     * Create a media player notification that automatically updates. Call this
     * method again with a different configuration to update the notification.
     */
    fun createNotification(config: NotificationConfig) = scope.launch {
        if (isNotificationButtonsChanged(config.buttons)) {
            hideNotification()
        }

        buttons.apply {
            clear()
            addAll(config.buttons)
        }

        stopIcon = null
        forwardIcon = null
        rewindIcon = null

        updateMediaSessionPlaybackActions()

        pendingIntent = config.pendingIntent
    
        showPlayPauseButton = false
        showForwardButton = false
        showRewindButton = false
        showNextButton = false
        showPreviousButton = false
        showStopButton = false
      
      // Reinitialize the PlayerNotificationManager if it's null or the configuration has changed.
         internalNotificationManager = PlayerNotificationManager.Builder(context, NOTIFICATION_ID, CHANNEL_ID)
             .setChannelNameResourceId(R.string.playback_channel_name)
             .setMediaDescriptionAdapter(descriptionAdapter)
             .setCustomActionReceiver(customActionReceiver)
             .setNotificationListener(this@NotificationManager)
             .build()
             .apply {
                 setMediaSessionToken(mediaSession.sessionCompatToken)
                 setPlayer(player)
             }

       setupInternalNotificationManager(config)
    }

    private fun isNotificationButtonsChanged(newButtons: List<NotificationButton>): Boolean {
        val currentNotificationButtonsMapByType = buttons.filterNotNull().associateBy { it::class }
        return newButtons.any { newButton ->
            when (newButton) {
                is NotificationButton.PLAY_PAUSE -> {
                    (currentNotificationButtonsMapByType[NotificationButton.PLAY_PAUSE::class] as? NotificationButton.PLAY_PAUSE).let { currentButton ->
                        newButton.pauseIcon != currentButton?.pauseIcon || newButton.playIcon != currentButton?.playIcon
                    }
                }

                is NotificationButton.STOP -> {
                    (currentNotificationButtonsMapByType[NotificationButton.STOP::class] as? NotificationButton.STOP).let { currentButton ->
                        newButton.icon != currentButton?.icon
                    }
                }

                is NotificationButton.FORWARD -> {
                    (currentNotificationButtonsMapByType[NotificationButton.FORWARD::class] as? NotificationButton.FORWARD).let { currentButton ->
                        newButton.icon != currentButton?.icon
                    }
                }

                is NotificationButton.BACKWARD -> {
                    (currentNotificationButtonsMapByType[NotificationButton.BACKWARD::class] as? NotificationButton.BACKWARD).let { currentButton ->
                        newButton.icon != currentButton?.icon
                    }
                }

                is NotificationButton.NEXT -> {
                    (currentNotificationButtonsMapByType[NotificationButton.NEXT::class] as? NotificationButton.NEXT).let { currentButton ->
                        newButton.icon != currentButton?.icon
                    }
                }

                is NotificationButton.PREVIOUS -> {
                    (currentNotificationButtonsMapByType[NotificationButton.PREVIOUS::class] as? NotificationButton.PREVIOUS).let { currentButton ->
                        newButton.icon != currentButton?.icon
                    }
                }

                else -> false
            }
        }
    }

    private fun updateMediaSessionPlaybackActions() {
       mediaSession.setCustomLayout(
            buttons.mapNotNull { button ->
                when (button) {
                    is NotificationButton.BACKWARD -> createMediaSessionAction( REWIND)
                    is NotificationButton.FORWARD -> createMediaSessionAction( FORWARD)
                    is NotificationButton.STOP -> createMediaSessionAction(STOP)
                    else -> null
                }
            }
        )
    }

    private fun setupInternalNotificationManager(config: NotificationConfig) {
        internalNotificationManager?.run {
            setColor(config.accentColor ?: Color.TRANSPARENT)
            config.smallIcon?.let { setSmallIcon(it) }
            for (button in buttons) {
                if (button == null) continue
                when (button) {
                    is NotificationButton.PLAY_PAUSE -> {
                        showPlayPauseButton = true
                    }

                    is NotificationButton.STOP -> {
                        showStopButton = true
                    }

                    is NotificationButton.FORWARD -> {
                        showForwardButton = true
                        showForwardButtonCompact = button.isCompact
                    }

                    is NotificationButton.BACKWARD -> {
                        showRewindButton = true
                        showRewindButtonCompact = button.isCompact
                    }

                    is NotificationButton.NEXT -> {
                        showNextButton = true
                        showNextButtonCompact = button.isCompact
                    }

                    is NotificationButton.PREVIOUS -> {
                        showPreviousButton = true
                        showPreviousButtonCompact = button.isCompact
                    }

                    else -> {}
                }
            }
        }
    }

    fun hideNotification() {
        internalNotificationManager?.setPlayer(null)
    }

    override fun onNotificationPosted(
        notificationId: Int,
        notification: Notification,
        ongoing: Boolean
    ) {
        scope.launch {
            event.updateNotificationState(
                NotificationState.POSTED(
                    notificationId,
                    notification,
                    ongoing
                )
            )
        }
    }

    override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
        scope.launch {
            event.updateNotificationState(NotificationState.CANCELLED(notificationId))
        }
    }

    internal fun destroy() = scope.launch {
        internalNotificationManager?.setPlayer(null)
        notificationMetadataArtworkDisposable?.dispose()
    }

    private fun createMediaSessionAction( actionName: String): CommandButton {
        return CommandButton.Builder()
            .setDisplayName(actionName)
            .build()
    }

    companion object {
        // Due to the removal of rewind, forward, and stop buttons from the standard notification
        // controls in Android 13, custom actions are implemented to support them
        // https://developer.android.com/about/versions/13/behavior-changes-13#playback-controls
        private val needsCustomActionsToAddMissingButtons = Build.VERSION.SDK_INT >= 33
        private const val REWIND = "rewind"
        private const val FORWARD = "forward"
        private const val STOP = "stop"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "kotlin_audio_player"
         private const val DEFAULT_STOP_ICON =1
         private const val DEFAULT_REWIND_ICON = 1
         private const val DEFAULT_FORWARD_ICON =1
    
    }
}