package net.programmierecke.radiodroid2.players.exoplayer;


import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;

import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static net.programmierecke.radiodroid2.Utils.getMimeType;
import static okhttp3.internal.Util.closeQuietly;

/**
 * An {@link HttpDataSource} that uses {@link OkHttpClient},
 * retrieves stream's {@link ShoutcastInfo} and {@link StreamLiveInfo} if any,
 * attempts to reconnect if connection is lost. These distinguishes it from {@link DefaultHttpDataSource}.
 * <p>
 * When connection is lost attempts to reconnect will made alongside with calling
 * {@link IcyDataSourceListener#onDataSourceConnectionLost()}.
 * After reconnecting time has passed
 * {@link IcyDataSourceListener#onDataSourceConnectionLostIrrecoverably()} will be called.
 **/
public class IcyDataSource implements HttpDataSource {

    public static final long DEFAULT_TIME_UNTIL_STOP_RECONNECTING = 2 * 60 * 1000; // 2 minutes

    public static final long DEFAULT_DELAY_BETWEEN_RECONNECTIONS = 0;

    public interface IcyDataSourceListener {
        /**
         * Called on first connection and after successful reconnection.
         */
        void onDataSourceConnected();

        /**
         * Called when connection is lost and reconnection attempts will be made.
         */
        void onDataSourceConnectionLost();

        /**
         * Called when data source gives up reconnecting.
         */
        void onDataSourceConnectionLostIrrecoverably();

        void onDataSourceShoutcastInfo(@Nullable ShoutcastInfo shoutcastInfo);

        void onDataSourceStreamLiveInfo(StreamLiveInfo streamLiveInfo);

        void onDataSourceBytesRead(byte[] buffer, int offset, int length);
    }

    private static final String TAG = "IcyDataSource";

    private DataSpec dataSpec;

    private final OkHttpClient httpClient;
    private final TransferListener transferListener;
    private final IcyDataSourceListener dataSourceListener;

    private Request request;

    private ResponseBody responseBody;
    private Map<String, List<String>> responseHeaders;

    int metadataBytesToSkip = 0;
    int remainingUntilMetadata = Integer.MAX_VALUE;
    private int metadataFrameSize = 0;
    private final ByteArrayOutputStream metadataBuffer = new ByteArrayOutputStream();
    private boolean opened;

    ShoutcastInfo shoutcastInfo;
    private StreamLiveInfo streamLiveInfo;

    public IcyDataSource(@NonNull OkHttpClient httpClient,
                         @NonNull TransferListener listener,
                         @NonNull IcyDataSourceListener dataSourceListener) {
        this.httpClient = httpClient;
        this.transferListener = listener;
        this.dataSourceListener = dataSourceListener;
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSourceException {
        close();

        this.dataSpec = dataSpec;

        final boolean allowGzip = (dataSpec.flags & DataSpec.FLAG_ALLOW_GZIP) != 0;

        HttpUrl url = HttpUrl.parse(dataSpec.uri.toString());
        Request.Builder builder = new Request.Builder().url(url)
                .addHeader("Icy-MetaData", "1");

        if (!allowGzip) {
            builder.addHeader("Accept-Encoding", "identity");
        }

        request = builder.build();

        return connect();
    }

    private long connect() throws HttpDataSourceException {
        Response response;
        try {
            response = httpClient.newCall(request).execute();
        } catch (IOException e) {
            throw new HttpDataSourceException("Unable to connect to " + dataSpec.uri.toString(), e,
                    dataSpec, HttpDataSourceException.TYPE_OPEN);
        }

        final int responseCode = response.code();

        if (!response.isSuccessful()) {
            final Map<String, List<String>> headers = request.headers().toMultimap();
            throw new InvalidResponseCodeException(responseCode, headers, dataSpec);
        }

        responseBody = response.body();
        assert responseBody != null;

        responseHeaders = response.headers().toMultimap();

        final MediaType contentType = responseBody.contentType();

        final String type = contentType == null ? getMimeType(dataSpec.uri.toString(), "audio/mpeg") : contentType.toString().toLowerCase();

        if (!REJECT_PAYWALL_TYPES.apply(type)) {
            close();
            throw new InvalidContentTypeException(type, dataSpec);
        }

        opened = true;

        dataSourceListener.onDataSourceConnected();
        transferListener.onTransferStart(this, dataSpec, true);

        if (type.equals("application/vnd.apple.mpegurl") || type.equals("application/x-mpegurl")) {
            return responseBody.contentLength();
        } else {
            // try to get shoutcast information from stream connection
            shoutcastInfo = ShoutcastInfo.Decode(response);
            dataSourceListener.onDataSourceShoutcastInfo(shoutcastInfo);

            metadataBytesToSkip = 0;
            metadataFrameSize = 0;
            metadataBuffer.reset();
            if (shoutcastInfo != null) {
                remainingUntilMetadata = shoutcastInfo.metadataOffset;
            } else {
                remainingUntilMetadata = Integer.MAX_VALUE;
            }

            return responseBody.contentLength();
        }
    }

    @Override
    public void close() throws HttpDataSourceException {
        if (opened) {
            opened = false;
            transferListener.onTransferEnd(this, dataSpec, true);
        }

        if (responseBody != null) {
            closeQuietly(responseBody);
            responseBody = null;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws HttpDataSourceException {
        try {
            final int bytesTransferred = readInternal(buffer, offset, readLength);
            if (bytesTransferred > 0) {
                transferListener.onBytesTransferred(this, dataSpec, true, bytesTransferred);
            }
            return bytesTransferred;
        } catch (HttpDataSourceException readError) {
            dataSourceListener.onDataSourceConnectionLost();
            throw readError;
        }
    }

    void sendToDataSourceListenersWithoutMetadata(byte[] buffer, int offset, int bytesAvailable) {
        while (bytesAvailable > 0) {
            if (metadataBytesToSkip > 0) {
                int bytesToConsume = Math.min(metadataBytesToSkip, bytesAvailable);
                int consumedInFrame = metadataFrameSize + 1 - metadataBytesToSkip;
                int payloadOffset = consumedInFrame == 0 ? 1 : 0;
                int payloadLength = Math.max(0, bytesToConsume - payloadOffset);
                if (payloadLength > 0) {
                    metadataBuffer.write(buffer, offset + payloadOffset, payloadLength);
                }

                metadataBytesToSkip -= bytesToConsume;
                remainingUntilMetadata -= bytesToConsume;
                offset += bytesToConsume;
                bytesAvailable -= bytesToConsume;

                if (metadataBytesToSkip == 0) {
                    publishMetadata();
                    metadataFrameSize = 0;
                    metadataBuffer.reset();
                }
                continue;
            }

            if (remainingUntilMetadata > 0) {
                int audioBytes = Math.min(bytesAvailable, remainingUntilMetadata);
                dataSourceListener.onDataSourceBytesRead(buffer, offset, audioBytes);
                offset += audioBytes;
                bytesAvailable -= audioBytes;
                remainingUntilMetadata -= audioBytes;
                continue;
            }

            metadataFrameSize = (buffer[offset] & 0xff) * 16;
            metadataBytesToSkip = metadataFrameSize + 1; // Includes the metadata-length byte.
            remainingUntilMetadata = shoutcastInfo.metadataOffset + metadataBytesToSkip;
        }
    }

    private void publishMetadata() {
        if (metadataFrameSize == 0) {
            return;
        }

        String raw = new String(metadataBuffer.toByteArray(), Charset.forName("UTF-8"))
                .replace("\u0000", "")
                .trim();
        if (raw.isEmpty()) {
            return;
        }

        Map<String, String> metadata = new HashMap<>();
        for (String entry : raw.split(";")) {
            int equals = entry.indexOf('=');
            if (equals < 1) {
                continue;
            }

            String key = entry.substring(0, equals).trim();
            String value = entry.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1);
            }
            metadata.put(key, value);
        }

        if (!metadata.isEmpty()) {
            streamLiveInfo = new StreamLiveInfo(metadata);
            dataSourceListener.onDataSourceStreamLiveInfo(streamLiveInfo);
        }
    }

    private int readInternal(byte[] buffer, int offset, int readLength) throws HttpDataSourceException {
        if (responseBody == null) {
            throw new HttpDataSourceException(dataSpec, HttpDataSourceException.TYPE_READ);
        }

        InputStream stream = responseBody.byteStream();

        int bytesRead = 0;
        try {
            bytesRead = stream.read(buffer, offset, readLength);
        } catch (IOException e) {
            throw new HttpDataSourceException(e, dataSpec, HttpDataSourceException.TYPE_READ);
        }

        if (bytesRead > 0) {
            sendToDataSourceListenersWithoutMetadata(buffer, offset, bytesRead);
        }

        return bytesRead;
    }

    @Override
    public Uri getUri() {
        return dataSpec.uri;
    }

    @Override
    public void setRequestProperty(String name, String value) {
        // Ignored
    }

    @Override
    public void clearRequestProperty(String name) {
        // Ignored
    }

    @Override
    public void clearAllRequestProperties() {
        // Ignored
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public int getResponseCode() {
        return 0;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {

    }
}
