package app.revanced.music.shared

import app.revanced.music.utils.Event
import app.revanced.music.utils.LogHelper

/**
 * Music video type
 */
enum class VideoType {
    MUSIC_VIDEO_TYPE_UNKNOWN,
    MUSIC_VIDEO_TYPE_ATV,
    MUSIC_VIDEO_TYPE_OMV,
    MUSIC_VIDEO_TYPE_UGC,
    MUSIC_VIDEO_TYPE_SHOULDER,
    MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC,
    MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK,
    MUSIC_VIDEO_TYPE_LIVE_STREAM,
    MUSIC_VIDEO_TYPE_PODCAST_EPISODE;

    companion object {

        private val nameToVideoType = entries.associateBy { it.name }

        @JvmStatic
        fun setFromString(enumName: String) {
            val newType = nameToVideoType[enumName]
            if (newType != null && current != newType) {
                LogHelper.printDebug(VideoType.Companion::class.java, "VideoType changed to: $newType")
                current = newType
            }
        }

        /**
         * The current video type.
         */
        @JvmStatic
        var current
            get() = currentVideoType
            private set(value) {
                currentVideoType = value
                onChange(currentVideoType)
            }

        @Volatile // value is read/write from different threads
        private var currentVideoType = MUSIC_VIDEO_TYPE_UNKNOWN

        /**
         * player type change listener
         */
        @JvmStatic
        val onChange = Event<VideoType>()
    }

    fun isMusicVideo(): Boolean {
        return this == MUSIC_VIDEO_TYPE_OMV
    }
}