package net.programmierecke.radiodroid2.players.exoplayer;

import android.net.Uri;

import androidx.annotation.NonNull;

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
 * two-hour seek window plus a small safety margin.</p>
 */
final class TimeshiftBuffer {
    static final long WINDOW_DURATION_MS = 2L * 60L * 60L * 1000L;

    private static final long RETENTION_MARGIN_MS = 5L * 60L * 1000L;
    private static final long SEGMENT_SIZE_BYTES = 1024L * 1024L;
    private static final long CHECKPOINT_INTERVAL_MS = 1000L;
    private static final long LIVE_START_MARGIN_MS = 1500L;
    private static final int READ_WAIT_MS = 500;
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
        final long startTimeMs;
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

    private final Object monitor = new Object();
    private final File directory;
    private final String streamUrl;
    private final OkHttpClient httpClient;
    private final TransferListener transferListener;
    private final IcyDataSource.IcyDataSourceListener downstreamListener;
    private final int retryTimeoutMs;
    private final int retryDelayMs;
    private final String sessionId = UUID.randomUUID().toString();
    private final List<Segment> segments = new ArrayList<>();
    private final List<Checkpoint> checkpoints = new ArrayList<>();

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
    private long fallbackStartedAtMs;

    TimeshiftBuffer(
            @NonNull File cacheDirectory,
            @NonNull String streamUrl,
            @NonNull OkHttpClient httpClient,
            @NonNull TransferListener transferListener,
            @NonNull IcyDataSource.IcyDataSourceListener downstreamListener,
            int retryTimeoutSeconds,
            int retryDelayMs) {
        this.directory = new File(cacheDirectory, "radio-timeshift");
        this.streamUrl = streamUrl;
        this.httpClient = httpClient;
        this.transferListener = transferListener;
        this.downstreamListener = downstreamListener;
        this.retryTimeoutMs = Math.max(0, retryTimeoutSeconds) * 1000;
        this.retryDelayMs = Math.max(10, retryDelayMs);
    }

    void start() throws IOException {
        synchronized (monitor) {
            if (running) {
                return;
            }
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Could not create timeshift cache directory");
            }
            deleteAbandonedSessions();
            running = true;
            checkpoints.add(new Checkpoint(0, 0));
        }

        recorderThread = new Thread(this::recordLoop, "RadioTimeshiftRecorder");
        recorderThread.start();
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
            long retainedStart = checkpoints.isEmpty() ? 0 : checkpoints.get(0).timeMs;
            return Math.max(retainedStart, liveEdgeMs - WINDOW_DURATION_MS);
        }
    }

    boolean hasSeekableAudio() {
        synchronized (monitor) {
            return liveEdgeMs - getSeekableStartMs() >= 2000 && terminalError == null;
        }
    }

    SeekTarget resolveSeekTarget(long requestedTimeMs) {
        synchronized (monitor) {
            long startMs = getSeekableStartMs();
            long targetMs = Math.max(startMs, Math.min(requestedTimeMs, liveEdgeMs));
            if (liveEdgeMs - targetMs < LIVE_START_MARGIN_MS) {
                targetMs = Math.max(startMs, liveEdgeMs - LIVE_START_MARGIN_MS);
            }

            if (checkpoints.size() == 1) {
                return new SeekTarget(checkpoints.get(0).bytePosition, targetMs);
            }

            Checkpoint previous = checkpoints.get(0);
            for (int i = 1; i < checkpoints.size(); i++) {
                Checkpoint next = checkpoints.get(i);
                if (next.timeMs >= targetMs) {
                    long timeSpan = Math.max(1, next.timeMs - previous.timeMs);
                    long byteSpan = next.bytePosition - previous.bytePosition;
                    long offset = (targetMs - previous.timeMs) * byteSpan / timeSpan;
                    return new SeekTarget(previous.bytePosition + offset, targetMs);
                }
                previous = next;
            }

            return new SeekTarget(writePosition, targetMs);
        }
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
                    if (retryTimeoutMs == 0
                            || System.currentTimeMillis() - firstFailureAt >= retryTimeoutMs) {
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
                monitor.notifyAll();
            }
            deleteSessionFiles();
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
                    bitrateKbps = shoutcastInfo.bitrate;
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

    private void appendAudio(byte[] data, int offset, int length) throws IOException {
        synchronized (monitor) {
            int remaining = length;
            int sourceOffset = offset;
            while (remaining > 0) {
                ensureWriter();
                long segmentBytes = currentSegment.endPosition - currentSegment.startPosition;
                int writable = (int) Math.min(remaining, SEGMENT_SIZE_BYTES - segmentBytes);
                segmentWriter.write(data, sourceOffset, writable);
                currentSegment.endPosition += writable;
                writePosition += writable;
                sourceOffset += writable;
                remaining -= writable;

                if (currentSegment.endPosition - currentSegment.startPosition >= SEGMENT_SIZE_BYTES) {
                    closeWriter();
                }
            }

            updateDuration(length);
            if (liveEdgeMs - lastCheckpointMs >= CHECKPOINT_INTERVAL_MS) {
                checkpoints.add(new Checkpoint(writePosition, liveEdgeMs));
                lastCheckpointMs = liveEdgeMs;
            }
            evictExpiredSegments();
            monitor.notifyAll();
        }
    }

    private void updateDuration(int audioBytes) {
        if (bitrateKbps > 0) {
            double elapsedMs = (audioBytes * 8.0) / bitrateKbps + durationRemainderMs;
            long wholeMs = (long) elapsedMs;
            durationRemainderMs = elapsedMs - wholeMs;
            liveEdgeMs += wholeMs;
        } else {
            if (fallbackStartedAtMs == 0) {
                fallbackStartedAtMs = System.currentTimeMillis();
            }
            liveEdgeMs = Math.max(liveEdgeMs, System.currentTimeMillis() - fallbackStartedAtMs);
        }
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

    private void evictExpiredSegments() {
        long cutoffMs = liveEdgeMs - WINDOW_DURATION_MS - RETENTION_MARGIN_MS;
        while (segments.size() > 1 && segments.get(1).startTimeMs <= cutoffMs) {
            Segment expired = segments.remove(0);
            // A DataSource that already opened the file can continue reading its file descriptor.
            // New seeks are clamped to the first retained checkpoint.
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

    private int read(long position, byte[] buffer, int offset, int length) throws IOException {
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

            int available = (int) Math.min(length, segment.endPosition - position);
            try (RandomAccessFile reader = new RandomAccessFile(segment.file, "r")) {
                reader.seek(position - segment.startPosition);
                return reader.read(buffer, offset, available);
            }
        }
    }

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
        synchronized (monitor) {
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
        if (recorderThread != null) {
            recorderThread.interrupt();
        }
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
        }
    }

    private static final class BufferDataSource implements DataSource {
        private final TimeshiftBuffer owner;
        private final Uri uri;
        private final long basePosition;
        private long readPosition;

        BufferDataSource(TimeshiftBuffer owner, String streamUrl, long basePosition) {
            this.owner = owner;
            this.uri = Uri.parse(streamUrl);
            this.basePosition = basePosition;
        }

        @Override
        public long open(DataSpec dataSpec) {
            readPosition = basePosition + dataSpec.position;
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
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            // Network accounting is attached to the independent recorder.
        }
    }
}
