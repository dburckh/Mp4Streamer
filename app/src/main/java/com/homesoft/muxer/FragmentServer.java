package com.homesoft.muxer;

import android.util.Log;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.GatheringByteChannel;

import kotlin.NotImplementedError;

public class FragmentServer implements GatheringByteChannel {
    private static final String TAG = FragmentServer.class.getSimpleName();

    ByteBuffer header;
    ByteBuffer lastMoof;
    private boolean open = true;

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) {
        throw new NotImplementedError();
    }

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
                Log.d(TAG, "Stored moov: " + header);
            } else {
                lastMoof = out;
                Log.d(TAG, "Stored moof" + lastMoof);
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
     * Return the common MP4 header (FTYP + MOOV)
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
