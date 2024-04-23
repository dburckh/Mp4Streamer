package com.homesoft.muxer;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import java.io.IOException;
import java.nio.ByteBuffer;

public class SurfaceEncoder extends MediaCodec.Callback {
    enum State{IDLE, STARTING, RUNNING, STOPPING, RELEASED}

    public static final String TAG = SurfaceEncoder.class.getSimpleName();

    public interface Listener {

        void onShutdown();
        void onReady(MediaFormat mediaFormat);
        void onBuffer(ByteBuffer byteBuffer, @NonNull MediaCodec.BufferInfo info);
    }

    private final MediaCodec mediaCodec;
    private final MediaFormat mediaFormat;

    private final Listener listener;

    private State state = State.IDLE;

    public SurfaceEncoder(int width, int height, float fps, int iFrameInterval,
                          Listener listener) throws IOException {
        this.listener = listener;
        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        mediaFormat.setFloat(MediaFormat.KEY_FRAME_RATE, fps);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        float pixelsPerSecond = fps * width * height;
        //Try 1/8 or 12.5%
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, (int)(pixelsPerSecond / 8));
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
    }

    public State getState() {
        return state;
    }

    public Surface startMediaCodec(Handler workerHandler) {
        state = State.STARTING;
        mediaCodec.setCallback(this, workerHandler);
        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = mediaCodec.createInputSurface();
        mediaCodec.start();
        return surface;
    }

    public void shutdown() {
        state = State.STOPPING;
        mediaCodec.signalEndOfInputStream();
    }

    public void release() {
        state = State.RELEASED;
        mediaCodec.release();
    }
    @Override
    public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
        // Should not get called
    }

    @Override
    public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {

        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            try {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) > 0) {
                    mediaCodec.stop();
                    listener.onShutdown();
                    return;
                } else {
                    listener.onBuffer(codec.getOutputBuffer(index), info);
                }
            } catch (Exception e) {
                Log.e(TAG, "onOutputBufferAvailable()", e);
            }
        }
        codec.releaseOutputBuffer(index, false);
    }

    @Override
    public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
        Log.e(TAG, "MediaCodec Error", e);
    }

    @OptIn(markerClass = UnstableApi.class) @Override
    public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat mediaFormat) {
        state = State.RUNNING;
        listener.onReady(mediaFormat);
    }
}

