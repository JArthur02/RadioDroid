package net.programmierecke.radiodroid2.players;

import android.content.Context;

import androidx.annotation.NonNull;

import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;
import net.programmierecke.radiodroid2.recording.Recordable;

import okhttp3.OkHttpClient;

public interface PlayerWrapper extends Recordable {
    interface PlayListener {
        void onStateChanged(PlayState state);

        void onPlayerWarning(final int messageId);

        void onPlayerError(final int messageId);

        void onDataSourceShoutcastInfo(ShoutcastInfo shoutcastInfo, boolean isHls);

        void onDataSourceStreamLiveInfo(StreamLiveInfo liveInfo);
    }

    void playRemote(@NonNull OkHttpClient httpClient, @NonNull String streamUrl, @NonNull Context context, boolean isAlarm);

    void pause();

    /**
     * Pauses without tearing the session down so {@link #resumeInPlace()} can continue from the
     * exact point where playback stopped. Returns false if this player cannot do that, in which
     * case the caller falls back to {@link #pause()}.
     */
    default boolean pauseInPlace() {
        return false;
    }

    /**
     * Continues a session paused by {@link #pauseInPlace()}. Returns false if there is none.
     */
    default boolean resumeInPlace() {
        return false;
    }

    void stop();

    boolean isPlaying();

    long getBufferedMs();

    /**
     * Current playback position in the media timeline, in milliseconds.
     * For progressive radio this grows from ~0 when the listening session started.
     */
    long getCurrentPositionMs();

    /**
     * Furthest buffered (live-edge) position in the media timeline, in milliseconds.
     */
    long getLiveEdgePositionMs();

    /**
     * Earliest position still retained for seeking (timeshift window start), in milliseconds.
     */
    long getSeekableStartPositionMs();

    /**
     * Seek within the retained timeshift buffer. No-op if seeking is unsupported.
     */
    void seekTo(long positionMs);

    boolean canSeek();

    int getAudioSessionId();

    long getTotalTransferredBytes();

    long getCurrentPlaybackTransferredBytes();

    boolean isLocal();

    void setVolume(float newVolume);

    void setStateListener(PlayListener listener);
}
