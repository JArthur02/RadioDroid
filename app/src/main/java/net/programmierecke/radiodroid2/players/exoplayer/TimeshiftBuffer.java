package net.programmierecke.radiodroid2.players.exoplayer;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import okhttp3.OkHttpClient;

/**
 * Disk-backed rolling buffer for progressive radio streams.
 *
 * <p>The network recorder is independent from ExoPlayer. ExoPlayer reads the audio-only bytes
 * through a {@link DataSource}, so reopening the source at an older byte position does not make a
 * second request to a live server. Files are segmented and old segments are evicted after the
 * two-hour seek window plus a small safety margin, but never while an active reader still needs
 * them.</p>
 */
final class TimeshiftBuffer {
    static final long WINDOW_DURATION_MS = 2L * 60L * 60L * 1000L;
    static final long LIVE_START_MARGIN_MS = 1500L;

    private static final long RETENTION_MARGIN_MS = 5L * 60L * 1000L;
    private static final long SEGMENT_SIZE_BYTES = 1024L * 1024L;
    private static final long CHECKPOINT_INTERVAL_MS = 1000L;
    private static final int READ_WAIT_MS = 500;
    private static final long RECORDER_JOIN_TIMEOUT_MS = 5000L;
    private static final String FILE_PREFIX = "radiodroid-timeshift-";

    static final class SeekTarget {
        final long bytePosition;
        final long timeMs;

        SeekTarget(long bytePosition, long timeMs) {
            this.bytePosition = bytePosition;
            this.timeMs = timeMs;
        }
    }

    private static final class Segment {
        final File file;
        final long startPosition;
        long startTimeMs;
        long endPosition;

        Segment(File file, long startPosition, long startTimeMs) {
            this.file = file;
            this.startPosition = startPosition;
            this.endPosition = startPosition;
            this.startTimeMs = startTimeMs;
        }
    }

    private static final class Checkpoint {
        final long bytePosition;
        final long timeMs;

        Checkpoint(long bytePosition, long timeMs) {
            this.bytePosition = bytePosition;
            this.timeMs = timeMs;
        }
    }

    private static final class WriteWindow {
        final RandomAccessFile writer;
        final Segment segment;
        final int length;

        WriteWindow(RandomAccessFile writer, Segment segment, int length) {
            this.writer = writer;
            this.segment = segment;
            this.length = length;
        }
    }

    private final Object monitor = new Object();
    private final File directory;
    private final String streamUrl;
    private final OkHttpClient httpClient;
    private final TransferListener transferListener;
    private final IcyDataSource.IcyDataSourceListener downstreamListener;
    private final int retryTimeoutMs;
    private final int resumeWithinMs;
    private final int retryDelayMs;
    private final String sessionId = UUID.randomUUID().toString();
    private final List<Segment> segments = new ArrayList<>();
    private final List<Checkpoint> checkpoints = new ArrayList<>();
    private final List<BufferDataSource> activeReaders = new ArrayList<>();

    private volatile boolean running;
    private volatile IcyDataSource upstream;
    private volatile IOException terminalError;

    private Thread recorderThread;
    private RandomAccessFile segmentWriter;
    private Segment currentSegment;
    private long writePosition;
    private long liveEdgeMs;
    private long lastCheckpointMs;
    private int bitrateKbps;
    private double durationRemainderMs;
    private long windowDurationMs = WINDOW_DURATION_MS;
    private long retentionMarginMs = RETENTION_MARGIN_MS;
    private long segmentSizeBytes = SEGMENT_SIZE_BYTES;

    TimeshiftBuffer(
            @NonNull File cacheDirectory,
            @NonNull String streamUrl,
            @NonNull OkHttpClient httpClient,
            @NonNull TransferListener transferListener,
            @NonNull IcyDataSource.IcyDataSourceListener downstreamListener,
            int retryTimeoutSeconds,
            int retryDelayMs) {
        this(cacheDirectory, streamUrl, httpClient, transferListener, downstreamListener,
                retryTimeoutSeconds, /* resumeWithinSeconds= */ 0, retryDelayMs);
    }

    TimeshiftBuffer(
            @NonNull File cacheDirectory,
            @NonNull String streamUrl,
            @NonNull OkHttpClient httpClient,
            @NonNull TransferListener transferListener,
            @NonNull IcyDataSource.IcyDataSourceListener downstreamListener,
            int retryTimeoutSeconds,
            int resumeWithinSeconds,
            int retryDelayMs) {
        this.directory = new File(cacheDirectory, "radio-timeshift");
        this.streamUrl = streamUrl;
        this.httpClient = httpClient;
        this.transferListener = transferListener;
        this.downstreamListener = downstreamListener;
        this.retryTimeoutMs = Math.max(0, retryTimeoutSeconds) * 1000;
        this.resumeWithinMs = Math.max(0, resumeWithinSeconds) * 1000;
        this.retryDelayMs = Math.max(10, retryDelayMs);
    }

    void start() throws IOException {
        resetStateForStart(/* deleteAbandoned= */ true);
        recorderThread = new Thread(this::recordLoop, "RadioTimeshiftRecorder");
        recorderThread.start();
    }

    /**
     * Test helper: ingest audio without opening a network connection.
     */
    void ingestForTest(@NonNull byte[] audio, int bitrate) throws IOException {
        resetStateForStart(/* deleteAbandoned= */ false);
        synchronized (monitor) {
            if (bitrate > 0) {
                applyBitrateLocked(bitrate);
            }
        }
        appendAudio(audio, 0, audio.length);
    }

    /**
     * Test helper: append more audio to an already-started test buffer.
     */
    void appendForTest(@NonNull byte[] audio) throws IOException {
        synchronized (monitor) {
            if (!running) {
                throw new IOException("Timeshift buffer is not running");
            }
        }
        appendAudio(audio, 0, audio.length);
    }

    void setWindowForTest(long windowMs, long marginMs) {
        synchronized (monitor) {
            windowDurationMs = Math.max(0, windowMs);
            retentionMarginMs = Math.max(0, marginMs);
        }
    }

    void setSegmentSizeForTest(long bytes) {
        synchronized (monitor) {
            segmentSizeBytes = Math.max(1024, bytes);
        }
    }

    void applyBitrateForTest(int bitrate) {
        synchronized (monitor) {
            applyBitrateLocked(bitrate);
        }
    }

    long getWritePositionForTest() {
        synchronized (monitor) {
            return writePosition;
        }
    }

    int getSegmentCountForTest() {
        synchronized (monitor) {
            return segments.size();
        }
    }

    boolean segmentFileExistsForTest(int index) {
        synchronized (monitor) {
            if (index < 0 || index >= segments.size()) {
                return false;
            }
            return segments.get(index).file.exists();
        }
    }

    int readForTest(long position, byte[] buffer, int offset, int length) throws IOException {
        return read(position, buffer, offset, length);
    }

    private void resetStateForStart(boolean deleteAbandoned) throws IOException {
        synchronized (monitor) {
            if (running && recorderThread != null) {
                throw new IOException("Timeshift buffer already running");
            }
            if (running) {
                // Previous test ingest left the buffer open without a recorder thread.
                closeWriter();
            }
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Could not create timeshift cache directory");
            }
            if (deleteAbandoned) {
                deleteAbandonedSessions();
            }
            running = true;
            terminalError = null;
            writePosition = 0;
            liveEdgeMs = 0;
            lastCheckpointMs = 0;
            bitrateKbps = 0;
            durationRemainderMs = 0;
            segments.clear();
            checkpoints.clear();
            checkpoints.add(new Checkpoint(0, 0));
            activeReaders.clear();
        }
    }

    DataSource.Factory createDataSourceFactory(SeekTarget target) {
        return () -> new BufferDataSource(this, streamUrl, target.bytePosition);
    }

    long getLiveEdgeMs() {
        synchronized (monitor) {
            return liveEdgeMs;
        }
    }

    long getSeekableStartMs() {
        synchronized (monitor) {
            return seekableStartMsLocked();
        }
    }

    boolean hasSeekableAudio() {
        synchronized (monitor) {
            return bitrateKbps > 0
                    && liveEdgeMs - seekableStartMsLocked() >= 2000
                    && terminalError == null;
        }
    }

    SeekTarget resolveSeekTarget(long requestedTimeMs) {
        synchronized (monitor) {
            long startMs = seekableStartMsLocked();
            long targetMs = Math.max(startMs, Math.min(requestedTimeMs, liveEdgeMs));
            if (liveEdgeMs - targetMs < LIVE_START_MARGIN_MS) {
                targetMs = Math.max(startMs, liveEdgeMs - LIVE_START_MARGIN_MS);
            }

            return new SeekTarget(bytePositionForTimeLocked(targetMs), targetMs);
        }
    }

    private long seekableStartMsLocked() {
        long retainedStart = checkpoints.isEmpty() ? 0 : checkpoints.get(0).timeMs;
        return Math.max(retainedStart, liveEdgeMs - windowDurationMs);
    }

    private long bytePositionForTimeLocked(long targetMs) {
        if (targetMs <= 0 || writePosition <= 0 || liveEdgeMs <= 0) {
            return 0;
        }
        if (targetMs >= liveEdgeMs) {
            return writePosition;
        }

        Checkpoint previous = checkpoints.get(0);
        for (int i = 1; i < checkpoints.size(); i++) {
            Checkpoint next = checkpoints.get(i);
            if (next.timeMs >= targetMs) {
                return interpolateLocked(previous.bytePosition, previous.timeMs,
                        next.bytePosition, next.timeMs, targetMs);
            }
            previous = next;
        }

        // Between the last checkpoint and the live tip (or only the zero checkpoint).
        return interpolateLocked(previous.bytePosition, previous.timeMs,
                writePosition, liveEdgeMs, targetMs);
    }

    private long interpolateLocked(
            long startBytes,
            long startMs,
            long endBytes,
            long endMs,
            long targetMs) {
        long timeSpan = Math.max(1, endMs - startMs);
        long byteSpan = endBytes - startBytes;
        long offset = (targetMs - startMs) * byteSpan / timeSpan;
        long position = startBytes + offset;
        if (position < 0) {
            return 0;
        }
        return Math.min(writePosition, position);
    }

    private long reconnectBudgetMs() {
        // Prefer the user-facing resume window; fall back to the short retry timeout.
        if (resumeWithinMs > 0) {
            return resumeWithinMs;
        }
        return retryTimeoutMs;
    }

    private void recordLoop() {
        byte[] networkBuffer = new byte[32 * 1024];
        long firstFailureAt = 0;

        try {
            while (running) {
                IcyDataSource source = new IcyDataSource(
                        httpClient,
                        transferListener,
                        new RecordingListener());
                upstream = source;

                try {
                    source.open(new DataSpec(Uri.parse(streamUrl)));
                    firstFailureAt = 0;

                    while (running) {
                        int read = source.read(networkBuffer, 0, networkBuffer.length);
                        if (read == C.RESULT_END_OF_INPUT) {
                            throw new IOException("Radio stream ended");
                        }
                    }
                } catch (IOException error) {
                    if (!running) {
                        break;
                    }

                    downstreamListener.onDataSourceConnectionLost();
                    if (firstFailureAt == 0) {
                        firstFailureAt = System.currentTimeMillis();
                    }
                    long budgetMs = reconnectBudgetMs();
                    if (budgetMs == 0
                            || System.currentTimeMillis() - firstFailureAt >= budgetMs) {
                        fail(error);
                        downstreamListener.onDataSourceConnectionLostIrrecoverably();
                        break;
                    }

                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } finally {
                    try {
                        source.close();
                    } catch (IOException ignored) {
                    }
                    upstream = null;
                }
            }
        } finally {
            synchronized (monitor) {
                closeWriter();
                running = false;
                recorderThread = null;
                monitor.notifyAll();
            }
            // Do not delete segment files here: ExoPlayer may still be reading behind writePosition
            // until stop()/close() runs on the main thread after irrecoverable failure.
            // close() always deletes the session files.
        }
    }

    private final class RecordingListener implements IcyDataSource.IcyDataSourceListener {
        @Override
        public void onDataSourceConnected() {
            downstreamListener.onDataSourceConnected();
        }

        @Override
        public void onDataSourceConnectionLost() {
            // recordLoop owns reconnect notifications to avoid duplicates.
        }

        @Override
        public void onDataSourceConnectionLostIrrecoverably() {
            downstreamListener.onDataSourceConnectionLostIrrecoverably();
        }

        @Override
        public void onDataSourceShoutcastInfo(ShoutcastInfo shoutcastInfo) {
            if (shoutcastInfo != null && shoutcastInfo.bitrate > 0) {
                synchronized (monitor) {
                    applyBitrateLocked(shoutcastInfo.bitrate);
                }
            }
            downstreamListener.onDataSourceShoutcastInfo(shoutcastInfo);
        }

        @Override
        public void onDataSourceStreamLiveInfo(StreamLiveInfo liveInfo) {
            downstreamListener.onDataSourceStreamLiveInfo(liveInfo);
        }

        @Override
        public void onDataSourceBytesRead(byte[] buffer, int offset, int length) {
            if (length <= 0 || !running) {
                return;
            }

            try {
                appendAudio(buffer, offset, length);
                downstreamListener.onDataSourceBytesRead(buffer, offset, length);
            } catch (IOException error) {
                fail(error);
                downstreamListener.onDataSourceConnectionLostIrrecoverably();
            }
        }
    }

    private void applyBitrateLocked(int bitrate) {
        if (bitrate <= 0) {
            return;
        }
        boolean firstBitrate = bitrateKbps <= 0;
        bitrateKbps = bitrate;
        if (firstBitrate && writePosition > 0) {
            recalibrateTimelineFromBytesLocked();
        }
    }

    private void recalibrateTimelineFromBytesLocked() {
        double exactMs = (writePosition * 8.0) / bitrateKbps;
        liveEdgeMs = (long) exactMs;
        durationRemainderMs = exactMs - liveEdgeMs;
        checkpoints.clear();
        checkpoints.add(new Checkpoint(0, 0));
        if (liveEdgeMs > 0) {
            checkpoints.add(new Checkpoint(writePosition, liveEdgeMs));
            lastCheckpointMs = liveEdgeMs;
        } else {
            lastCheckpointMs = 0;
        }
        for (Segment segment : segments) {
            segment.startTimeMs = (long) ((segment.startPosition * 8.0) / bitrateKbps);
        }
    }

    private void appendAudio(byte[] data, int offset, int length) throws IOException {
        int remaining = length;
        int sourceOffset = offset;
        while (remaining > 0) {
            WriteWindow window;
            synchronized (monitor) {
                if (!running) {
                    return;
                }
                ensureWriter();
                long segmentBytes = currentSegment.endPosition - currentSegment.startPosition;
                long remainingCapacity = segmentSizeBytes - segmentBytes;
                if (remainingCapacity <= 0) {
                    closeWriter();
                    continue;
                }
                int writable = (int) Math.min(remaining, remainingCapacity);
                window = new WriteWindow(segmentWriter, currentSegment, writable);
            }

            // Disk I/O outside the monitor so readers can map windows concurrently.
            window.writer.write(data, sourceOffset, window.length);

            synchronized (monitor) {
                if (window.segment != currentSegment || segmentWriter != window.writer) {
                    throw new IOException("Timeshift segment changed during write");
                }
                currentSegment.endPosition += window.length;
                writePosition += window.length;
                sourceOffset += window.length;
                remaining -= window.length;

                updateDuration(window.length);
                if (liveEdgeMs - lastCheckpointMs >= CHECKPOINT_INTERVAL_MS) {
                    checkpoints.add(new Checkpoint(writePosition, liveEdgeMs));
                    lastCheckpointMs = liveEdgeMs;
                }
                if (currentSegment.endPosition - currentSegment.startPosition >= segmentSizeBytes) {
                    closeWriter();
                }
                evictExpiredSegments();
                monitor.notifyAll();
            }
        }
    }

    private void updateDuration(int audioBytes) {
        if (bitrateKbps <= 0) {
            // Do not advance the seek clock from wall time: that desyncs byte↔time mapping once
            // Shoutcast bitrate arrives. Seeking stays disabled until bitrate is known.
            return;
        }
        double elapsedMs = (audioBytes * 8.0) / bitrateKbps + durationRemainderMs;
        long wholeMs = (long) elapsedMs;
        durationRemainderMs = elapsedMs - wholeMs;
        liveEdgeMs += wholeMs;
    }

    private void ensureWriter() throws IOException {
        if (segmentWriter != null) {
            return;
        }

        File file = new File(
                directory,
                FILE_PREFIX + sessionId + "-" + writePosition + ".cache");
        currentSegment = new Segment(file, writePosition, liveEdgeMs);
        segments.add(currentSegment);
        segmentWriter = new RandomAccessFile(file, "rw");
    }

    private void closeWriter() {
        if (segmentWriter != null) {
            try {
                segmentWriter.close();
            } catch (IOException ignored) {
            }
            segmentWriter = null;
            currentSegment = null;
        }
    }

    private long earliestActiveReadPositionLocked() {
        long earliest = Long.MAX_VALUE;
        for (BufferDataSource reader : activeReaders) {
            earliest = Math.min(earliest, reader.getReadPosition());
        }
        return earliest;
    }

    private void evictExpiredSegments() {
        long cutoffMs = liveEdgeMs - windowDurationMs - retentionMarginMs;
        long protectedPosition = earliestActiveReadPositionLocked();

        while (segments.size() > 1 && segments.get(1).startTimeMs <= cutoffMs) {
            Segment candidate = segments.get(0);
            // Keep any segment still needed by an active ExoPlayer DataSource.
            if (protectedPosition < candidate.endPosition) {
                break;
            }
            Segment expired = segments.remove(0);
            //noinspection ResultOfMethodCallIgnored
            expired.file.delete();
        }

        if (!segments.isEmpty()) {
            long earliestPosition = segments.get(0).startPosition;
            Iterator<Checkpoint> iterator = checkpoints.iterator();
            while (iterator.hasNext()) {
                Checkpoint checkpoint = iterator.next();
                if (checkpoint.bytePosition < earliestPosition && checkpoints.size() > 1) {
                    iterator.remove();
                } else {
                    break;
                }
            }
        }
    }

    private void registerReader(BufferDataSource reader) {
        synchronized (monitor) {
            activeReaders.add(reader);
        }
    }

    private void unregisterReader(BufferDataSource reader) {
        synchronized (monitor) {
            activeReaders.remove(reader);
            // Readers leaving may unblock eviction of segments past the window.
            evictExpiredSegments();
        }
    }

    private int read(long position, byte[] buffer, int offset, int length) throws IOException {
        RandomAccessFile reader;
        int available;
        synchronized (monitor) {
            while (position >= writePosition && running && terminalError == null) {
                try {
                    monitor.wait(READ_WAIT_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for timeshift audio", interrupted);
                }
            }

            if (terminalError != null && position >= writePosition) {
                throw terminalError;
            }
            if (position >= writePosition) {
                return C.RESULT_END_OF_INPUT;
            }

            Segment segment = findSegment(position);
            if (segment == null) {
                throw new IOException("Requested timeshift position is no longer buffered");
            }

            available = (int) Math.min(length, segment.endPosition - position);
            if (available <= 0) {
                return 0;
            }
            // Open under the monitor so eviction cannot delete the file between lookup and open.
            // On Linux the inode stays readable via this FD even if later unlinked.
            reader = new RandomAccessFile(segment.file, "r");
            try {
                reader.seek(position - segment.startPosition);
            } catch (IOException error) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
                throw error;
            }
        }

        try {
            return reader.read(buffer, offset, available);
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Nullable
    private Segment findSegment(long position) {
        for (Segment segment : segments) {
            if (position >= segment.startPosition && position < segment.endPosition) {
                return segment;
            }
        }
        return null;
    }

    private void fail(IOException error) {
        synchronized (monitor) {
            terminalError = error;
            running = false;
            monitor.notifyAll();
        }
        IcyDataSource source = upstream;
        if (source != null) {
            try {
                source.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void close() {
        Thread recorder;
        synchronized (monitor) {
            running = false;
            recorder = recorderThread;
            monitor.notifyAll();
        }

        IcyDataSource source = upstream;
        if (source != null) {
            try {
                source.close();
            } catch (IOException ignored) {
            }
        }
        if (recorder != null) {
            recorder.interrupt();
            try {
                recorder.join(RECORDER_JOIN_TIMEOUT_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized (monitor) {
            closeWriter();
        }
        deleteSessionFiles();
    }

    private void deleteAbandonedSessions() {
        File[] files = directory.listFiles(
                file -> file.isFile() && file.getName().startsWith(FILE_PREFIX));
        if (files == null) {
            return;
        }
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private void deleteSessionFiles() {
        synchronized (monitor) {
            for (Segment segment : segments) {
                //noinspection ResultOfMethodCallIgnored
                segment.file.delete();
            }
            segments.clear();
            checkpoints.clear();
            activeReaders.clear();
        }
    }

    private static final class BufferDataSource implements DataSource {
        private final TimeshiftBuffer owner;
        private final Uri uri;
        private final long basePosition;
        private long readPosition;
        private boolean opened;

        BufferDataSource(TimeshiftBuffer owner, String streamUrl, long basePosition) {
            this.owner = owner;
            this.uri = Uri.parse(streamUrl);
            this.basePosition = basePosition;
            this.readPosition = basePosition;
        }

        long getReadPosition() {
            return readPosition;
        }

        @Override
        public long open(DataSpec dataSpec) {
            readPosition = basePosition + Math.max(0, dataSpec.position);
            if (!opened) {
                owner.registerReader(this);
                opened = true;
            }
            return C.LENGTH_UNSET;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            int read = owner.read(readPosition, buffer, offset, length);
            if (read > 0) {
                readPosition += read;
            }
            return read;
        }

        @Override
        public Uri getUri() {
            return uri;
        }

        @Override
        public void close() {
            if (opened) {
                opened = false;
                owner.unregisterReader(this);
            }
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            // Network accounting is attached to the independent recorder.
        }
    }
}
