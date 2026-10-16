package net.programmierecke.radiodroid2.players.exoplayer;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimeshiftBufferTest {
    private static final int BITRATE_KBPS = 128;
    private static final int BYTES_PER_SECOND = BITRATE_KBPS * 1000 / 8;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private TimeshiftBuffer buffer;

    @Before
    public void setUp() throws Exception {
        File cacheDir = temporaryFolder.newFolder("cache");
        buffer = new TimeshiftBuffer(
                cacheDir,
                "http://example.invalid/stream",
                new OkHttpClient(),
                new NoOpTransferListener(),
                new NoOpListener(),
                /* retryTimeoutSeconds= */ 1,
                /* retryDelayMs= */ 10);
    }

    @After
    public void tearDown() {
        if (buffer != null) {
            buffer.close();
        }
    }

    @Test
    public void ingestTracksLiveEdgeFromBitrate() throws Exception {
        // 128 kbps => 16_000 bytes/s => 32_000 bytes ~= 2000 ms
        byte[] audio = new byte[2 * BYTES_PER_SECOND];
        Arrays.fill(audio, (byte) 0xFF);
        buffer.ingestForTest(audio, BITRATE_KBPS);

        assertEquals(2 * BYTES_PER_SECOND, buffer.getWritePositionForTest());
        assertEquals(2000, buffer.getLiveEdgeMs());
        assertTrue(buffer.hasSeekableAudio());
    }

    @Test
    public void resolveSeekTargetMapsTimeToBytes() throws Exception {
        // Keep the mid-point well outside LIVE_START_MARGIN so the mapping is not clamped.
        byte[] audio = new byte[5 * BYTES_PER_SECOND];
        buffer.ingestForTest(audio, BITRATE_KBPS);

        TimeshiftBuffer.SeekTarget mid = buffer.resolveSeekTarget(1000);
        assertEquals(1000, mid.timeMs);
        assertEquals(BYTES_PER_SECOND, mid.bytePosition);

        TimeshiftBuffer.SeekTarget live = buffer.resolveSeekTarget(buffer.getLiveEdgeMs());
        // Live seeks stay slightly behind the write tip so ExoPlayer has preroll.
        assertTrue(live.bytePosition < buffer.getWritePositionForTest());
        assertTrue(live.timeMs <= buffer.getLiveEdgeMs());
        assertEquals(TimeshiftBuffer.LIVE_START_MARGIN_MS, buffer.getLiveEdgeMs() - live.timeMs);
    }

    @Test
    public void resolveSeekTargetClampsNearLiveEdge() throws Exception {
        byte[] audio = new byte[5 * BYTES_PER_SECOND];
        buffer.ingestForTest(audio, BITRATE_KBPS);

        long liveEdge = buffer.getLiveEdgeMs();
        TimeshiftBuffer.SeekTarget nearLive = buffer.resolveSeekTarget(liveEdge - 500);
        assertEquals(liveEdge - TimeshiftBuffer.LIVE_START_MARGIN_MS, nearLive.timeMs);
        assertTrue(nearLive.bytePosition < buffer.getWritePositionForTest());
    }

    @Test
    public void dataSourceReadsBackWrittenAudio() throws Exception {
        byte[] audio = new byte[4096];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (byte) (i & 0xFF);
        }
        buffer.ingestForTest(audio, BITRATE_KBPS);

        byte[] readBack = new byte[audio.length];
        int total = 0;
        while (total < readBack.length) {
            int n = buffer.readForTest(total, readBack, total, readBack.length - total);
            assertTrue("expected readable buffered audio", n > 0);
            total += n;
        }

        assertTrue(Arrays.equals(audio, readBack));
    }

    @Test
    public void chunkedIngestStillMapsSeekTargets() throws Exception {
        final int chunkBytes = BYTES_PER_SECOND / 4; // 250 ms at 128 kbps
        byte[] first = new byte[chunkBytes];
        for (int i = 0; i < first.length; i++) {
            first[i] = (byte) (i & 0xFF);
        }
        buffer.ingestForTest(first, BITRATE_KBPS);

        long absoluteOffset = first.length;
        for (int chunk = 1; chunk < 16; chunk++) {
            byte[] next = new byte[chunkBytes];
            for (int i = 0; i < next.length; i++) {
                next[i] = (byte) ((absoluteOffset + i) & 0xFF);
            }
            buffer.appendForTest(next);
            absoluteOffset += next.length;
        }

        assertEquals(4000, buffer.getLiveEdgeMs());
        assertEquals(4L * BYTES_PER_SECOND, buffer.getWritePositionForTest());

        TimeshiftBuffer.SeekTarget atTwoSeconds = buffer.resolveSeekTarget(2000);
        assertEquals(2000, atTwoSeconds.timeMs);
        assertEquals(2L * BYTES_PER_SECOND, atTwoSeconds.bytePosition);

        byte[] sample = new byte[64];
        int read = buffer.readForTest(atTwoSeconds.bytePosition, sample, 0, sample.length);
        assertEquals(64, read);
        for (int i = 0; i < sample.length; i++) {
            assertEquals((byte) ((atTwoSeconds.bytePosition + i) & 0xFF), sample[i]);
        }
    }

    @Test
    public void seekableWindowStartsAtZeroUntilRetentionLimit() throws Exception {
        byte[] audio = new byte[BYTES_PER_SECOND];
        buffer.ingestForTest(audio, BITRATE_KBPS);
        assertEquals(0, buffer.getSeekableStartMs());
        assertFalse(buffer.getLiveEdgeMs() > TimeshiftBuffer.WINDOW_DURATION_MS);
    }

    @Test
    public void bitrateArrivalRecalibratesTimelineFromBytes() throws Exception {
        // Ingest without bitrate — seek clock must stay at zero (no wall-clock skew).
        byte[] audio = new byte[5 * BYTES_PER_SECOND];
        buffer.ingestForTest(audio, /* bitrate= */ 0);
        assertEquals(0, buffer.getLiveEdgeMs());
        assertFalse(buffer.hasSeekableAudio());

        buffer.applyBitrateForTest(BITRATE_KBPS);
        assertEquals(5000, buffer.getLiveEdgeMs());
        assertTrue(buffer.hasSeekableAudio());

        TimeshiftBuffer.SeekTarget mid = buffer.resolveSeekTarget(1000);
        assertEquals(1000, mid.timeMs);
        assertEquals(BYTES_PER_SECOND, mid.bytePosition);
    }

    @Test
    public void estimatesBitrateWhenStationSendsNoHeader() throws Exception {
        AtomicLong now = new AtomicLong(10_000);
        buffer.setClockForTest(now::get);

        // A server burst on connect must not inflate the estimate, then 128 kbps in real time.
        buffer.ingestForTest(new byte[4 * BYTES_PER_SECOND], /* bitrate= */ 0);
        assertFalse(buffer.hasSeekableAudio());
        for (int second = 1; second <= 10; second++) {
            now.addAndGet(1000);
            buffer.appendForTest(new byte[BYTES_PER_SECOND]);
        }

        assertTrue(buffer.hasSeekableAudio());
        assertEquals(14_000, buffer.getLiveEdgeMs());
        assertEquals(14L * BYTES_PER_SECOND, buffer.getWritePositionForTest());
        TimeshiftBuffer.SeekTarget target = buffer.resolveSeekTarget(4_000);
        assertEquals(4_000, target.timeMs);
        assertEquals(4L * BYTES_PER_SECOND, target.bytePosition);
    }

    @Test
    public void doesNotEstimateBitrateBeforeMeasurementWindowEnds() throws Exception {
        AtomicLong now = new AtomicLong(10_000);
        buffer.setClockForTest(now::get);

        buffer.ingestForTest(new byte[BYTES_PER_SECOND], /* bitrate= */ 0);
        for (int second = 1; second <= 5; second++) {
            now.addAndGet(1000);
            buffer.appendForTest(new byte[BYTES_PER_SECOND]);
        }

        assertFalse(buffer.hasSeekableAudio());
        assertEquals(0, buffer.getLiveEdgeMs());
    }

    @Test
    public void activeReaderBlocksEvictionOfNeededSegment() throws Exception {
        buffer.setSegmentSizeForTest(8_000);
        buffer.setWindowForTest(/* windowMs= */ 500, /* marginMs= */ 0);

        byte[] chunk = new byte[8_000];
        Arrays.fill(chunk, (byte) 0x42);
        // One segment (~500 ms). Register a reader before aging the window further.
        buffer.ingestForTest(chunk, BITRATE_KBPS);
        assertEquals(1, buffer.getSegmentCountForTest());

        DataSource.Factory factory = buffer.createDataSourceFactory(new TimeshiftBuffer.SeekTarget(0, 0));
        DataSource dataSource = factory.createDataSource();
        dataSource.open(new DataSpec(android.net.Uri.parse("http://example.invalid/stream")));
        try {
            byte[] sink = new byte[64];
            assertTrue(dataSource.read(sink, 0, sink.length) > 0);

            // Without reader protection these appends would evict segment 0 (window=500ms).
            for (int i = 0; i < 4; i++) {
                buffer.appendForTest(chunk);
            }
            assertTrue("segment still needed by active reader", buffer.segmentFileExistsForTest(0));
            assertTrue(buffer.getSegmentCountForTest() >= 2);
            assertTrue(dataSource.read(sink, 0, sink.length) > 0);
        } finally {
            dataSource.close();
        }
    }

    private static final class NoOpListener implements IcyDataSource.IcyDataSourceListener {
        @Override
        public void onDataSourceConnected() {
        }

        @Override
        public void onDataSourceConnectionLost() {
        }

        @Override
        public void onDataSourceConnectionLostIrrecoverably() {
        }

        @Override
        public void onDataSourceShoutcastInfo(ShoutcastInfo shoutcastInfo) {
        }

        @Override
        public void onDataSourceStreamLiveInfo(StreamLiveInfo streamLiveInfo) {
        }

        @Override
        public void onDataSourceBytesRead(byte[] buffer, int offset, int length) {
        }
    }

    private static final class NoOpTransferListener implements TransferListener {
        @Override
        public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }

        @Override
        public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }

        @Override
        public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {
        }

        @Override
        public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }
    }
}
