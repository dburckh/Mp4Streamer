package com.homesoft.muxer;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.muxer.Mp4Muxer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.GatheringByteChannel;
import java.util.ArrayList;
import java.util.List;

import kotlin.NotImplementedError;

/**
 * Mp4 Fragment Server
 * Holds the stream header(ftyp+moov) and the latest movie fragment (moof)
 */
public class FragmentServer implements GatheringByteChannel {
    /**
     * Microseconds in a second
     */
    public static final int ONE_US = 1_000_000;

    @OptIn(markerClass = UnstableApi.class)
    public static Format getFormat(MediaFormat mediaFormat) {
        final String mimeType = mediaFormat.getString(MediaFormat.KEY_MIME);
        final Format.Builder builder = new Format.Builder().setSampleMimeType(mimeType);
        if (mimeType.startsWith(MimeTypes.BASE_TYPE_VIDEO)) {
            builder.setWidth(mediaFormat.getInteger(MediaFormat.KEY_WIDTH))
                    .setHeight(mediaFormat.getInteger(MediaFormat.KEY_HEIGHT));
            if (mediaFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                builder.setRotationDegrees(mediaFormat.getInteger(MediaFormat.KEY_ROTATION));
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
        return builder.build();
    }
    private static void appendByteBuffer(ByteBuffer csd, List<byte[]> list) {
        if (csd != null) {
            byte[] buffer = new byte[csd.limit()];
            csd.rewind();
            csd.get(buffer);
            list.add(buffer);
        }
    }

    private static final String TAG = FragmentServer.class.getSimpleName();

    private final Mp4Muxer mp4Muxer;
    private final Mp4Muxer.TrackToken trackToken;

    ByteBuffer header;
    ByteBuffer lastMoof;
    private boolean open = true;

    public FragmentServer(MediaFormat mediaFormat, int rotation, int fragmentUs) {
        int fragmentDurationUs = fragmentUs >= ONE_US ? (fragmentUs - ONE_US / 4) : fragmentUs;
        mp4Muxer = new Mp4Muxer.Builder(this)
                .setFragmentedMp4Enabled(true)
                .setFragmentDurationUs(fragmentDurationUs)
                .build();
        final Format format = getFormat(mediaFormat);
        mp4Muxer.setOrientation(rotation);

        trackToken = mp4Muxer.addTrack(0, format);
    }

    public void onBuffer(ByteBuffer byteBuffer, @NonNull MediaCodec.BufferInfo info) throws IOException {
        byteBuffer.limit(info.offset + info.size);
        byteBuffer.position(info.offset);
        ByteBuffer copy = ByteBuffer.allocateDirect(byteBuffer.remaining());
        copy.put(byteBuffer);
        copy.flip();
        mp4Muxer.writeSampleData(trackToken, copy, info);
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) {
        throw new NotImplementedError();
    }

    /**
     * This is a little hacky, the FragmentedMp4Writer always writes data in chunks
     * The first chuck is the ftyp+moov and all subsequent chunks are moof.
     * @param srcs
     *         The buffers from which bytes are to be retrieved
     *
     * @return the number of bytes written
     */
    @Override
    public long write(ByteBuffer[] srcs) throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        int bytes = 0;
        for (ByteBuffer byteBuffer : srcs) {
            bytes += byteBuffer.remaining();
        }
        ByteBuffer out = ByteBuffer.allocate(bytes);
        for (ByteBuffer byteBuffer : srcs) {
            out.put(byteBuffer);
        }
        out.flip();
        synchronized (this) {
            if (header == null) {
                header = out;
                //Log.d(TAG, "Stored moov: " + header);
            } else {
                lastMoof = out;
                //Log.d(TAG, "Stored moof" + lastMoof);
            }
            notifyAll();
        }
        return bytes;
    }

    @Override
    public int write(ByteBuffer src) {
        throw new NotImplementedError();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    private void checkClosed() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    @Override
    public synchronized void close() {
        open = false;
        notifyAll();
    }

    /**
     * Return the common MP4 header (ftyp + moov)
     */
    public synchronized ByteBuffer getHeader() throws ClosedChannelException, InterruptedException {
        if (header == null) {
            wait();
            checkClosed();
        }
        return header;
    }

    public synchronized ByteBuffer getFragment(@Nullable ByteBuffer lastFragment)
            throws ClosedChannelException, InterruptedException {
        if (lastFragment == lastMoof) {
            wait();
            checkClosed();
        }
        return lastMoof;
    }
}
