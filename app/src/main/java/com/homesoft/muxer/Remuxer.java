package com.homesoft.muxer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaParser;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.muxer.Mp4Muxer;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class Remuxer implements MediaParser.OutputConsumer, Runnable {
    private static final int MAX_B_FRAMES = 4;
    private static final String TAG = Remuxer.class.getSimpleName();
    private final Context mContext;
    private final Uri mSourceUri;

    private Mp4Muxer mMp4Muxer;

    private byte[] sampleDataBuffer = new byte[4096];
    private byte[] discardedDataBuffer = new byte[4096];
    private int bytesWrittenCount = 0;

    private final HashMap<Integer, Mp4Muxer.TrackToken> mTokenMap = new HashMap<>();

    public Remuxer(Context context, Uri uri) {
        mContext = context;
        mSourceUri = uri;
    }

    @OptIn(markerClass = UnstableApi.class) @Override
    public void run() {
        final ContentResolver contentResolver = mContext.getContentResolver();
        Uri outUri = getUri(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "Test", "video/mp4");
        try (ParcelFileDescriptor pfd = contentResolver.openFileDescriptor(mSourceUri, "r");
             ParcelFileDescriptor outPfd = contentResolver.openFileDescriptor(outUri, "w");
             FileOutputStream out = new FileOutputStream(outPfd.getFileDescriptor());
             FileChannel fileChannel = out.getChannel()) {
            mMp4Muxer = new Mp4Muxer.Builder(fileChannel).setFragmentedMp4Enabled(true).build();
            MediaParser mediaParser = MediaParser.create(this, MediaParser.PARSER_NAME_MP4);
            PfdInputReader myInputReader = new PfdInputReader(pfd);
            while (mediaParser.advance(myInputReader)) {}
            mediaParser.release();
            mMp4Muxer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Uri getUri(Uri collection, String fileName, String mimeType) throws UnsupportedOperationException {
        ContentResolver resolver = mContext.getContentResolver();

        ContentValues newMediaValues = new ContentValues();
        newMediaValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        newMediaValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

        return resolver.insert(collection, newMediaValues);
    }
    @Override
    public void onSeekMapFound(@NonNull MediaParser.SeekMap seekMap) {

    }

    @Override
    public void onTrackCountFound(int numberOfTracks) {

    }

    private static void appendByteBuffer(ByteBuffer csd, List<byte[]> list) {
        if (csd != null) {
            byte[] buffer = new byte[csd.limit()];
            csd.rewind();
            csd.get(buffer);
            list.add(buffer);
        }
    }

    @OptIn(markerClass = UnstableApi.class) @Override
    public void onTrackDataFound(int trackIndex, @NonNull MediaParser.TrackData trackData) {
        final MediaFormat mediaFormat = trackData.mediaFormat;
        final String mimeType = mediaFormat.getString(MediaFormat.KEY_MIME);
        final Format.Builder builder = new Format.Builder().setSampleMimeType(mimeType);
        if (mimeType.startsWith(MimeTypes.BASE_TYPE_VIDEO)) {
            builder.setWidth(mediaFormat.getInteger(MediaFormat.KEY_WIDTH))
                    .setHeight(mediaFormat.getInteger(MediaFormat.KEY_HEIGHT));
            if (mediaFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                mMp4Muxer.setOrientation(mediaFormat.getInteger(MediaFormat.KEY_ROTATION));
            }
        } else if (mimeType.startsWith(MimeTypes.BASE_TYPE_AUDIO)) {
            builder.setChannelCount(mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    .setSampleRate(mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE));
        } else {
            Log.d(TAG, "Unknown mimeType: " + mimeType + " " + mediaFormat);
        }
        final ArrayList<byte[]> csdList = new ArrayList<>(2);
        ByteBuffer csd0 = mediaFormat.getByteBuffer("csd-0");
        appendByteBuffer(csd0, csdList);
        ByteBuffer csd1 = mediaFormat.getByteBuffer("csd-1");
        appendByteBuffer(csd1, csdList);
        builder.setInitializationData(csdList);
        final Format format = builder.build();
        final Mp4Muxer.TrackToken trackToken = mMp4Muxer.addTrack(trackIndex, format);
        mTokenMap.put(trackIndex, trackToken);
    }
    private void ensureSpaceInBuffer(int numberOfBytesToRead) {
        int requiredLength = bytesWrittenCount + numberOfBytesToRead;
        if (requiredLength > sampleDataBuffer.length) {
            sampleDataBuffer = Arrays.copyOf(sampleDataBuffer, requiredLength);
        }
    }
    @OptIn(markerClass = UnstableApi.class) @Override
    public void onSampleDataFound(int trackIndex, @NonNull MediaParser.InputReader inputReader) throws IOException {
        int numberOfBytesToRead = (int) inputReader.getLength();
        Mp4Muxer.TrackToken token = mTokenMap.get(trackIndex);
        if (token == null) {
            // Discard contents.
            inputReader.read(discardedDataBuffer, /* offset= */ 0,
                    Math.min(discardedDataBuffer.length, numberOfBytesToRead));
        } else {
            ensureSpaceInBuffer(numberOfBytesToRead);
            int bytesRead = inputReader.read(
                    sampleDataBuffer, bytesWrittenCount, numberOfBytesToRead);
            bytesWrittenCount += bytesRead;
        }
    }

    private static int mapFlags(int flags) {
        int out = 0;
        if ((flags & MediaParser.SAMPLE_FLAG_KEY_FRAME) != 0) {
            out = MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }
        if ((flags & MediaParser.SAMPLE_FLAG_DECODE_ONLY) != 0) {
            out |= MediaCodec.BUFFER_FLAG_DECODE_ONLY;
        }
        if ((flags & MediaParser.SAMPLE_FLAG_LAST_SAMPLE) != 0) {
            out |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
        }
        return out;
    }

    @OptIn(markerClass = UnstableApi.class) @Override
    public void onSampleCompleted(int trackIndex, long timeMicros, int flags, int size, int offset, @Nullable MediaCodec.CryptoInfo cryptoInfo) {
        Mp4Muxer.TrackToken token = mTokenMap.get(trackIndex);
        if (token != null) {
            byte[] sampleData = new byte[size];
            int sampleStartOffset = bytesWrittenCount - size - offset;
            System.arraycopy(
                    sampleDataBuffer,
                    sampleStartOffset,
                    sampleData,
                    /* destPos= */ 0,
                    size);
            // Place trailing bytes at the start of the buffer.
            System.arraycopy(
                    sampleDataBuffer,
                    bytesWrittenCount - offset,
                    sampleDataBuffer,
                    /* destPos= */ 0,
                    /* size= */ offset);
            bytesWrittenCount = bytesWrittenCount - offset;
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            bufferInfo.presentationTimeUs = timeMicros;
            bufferInfo.flags = mapFlags(flags);
            bufferInfo.size = size;

            try {
                mMp4Muxer.writeSampleData(token, ByteBuffer.wrap(sampleData), bufferInfo);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
