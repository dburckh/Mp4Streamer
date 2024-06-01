package com.homesoft.muxer;

import android.media.MediaParser;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Not used
 */
@RequiresApi(api = Build.VERSION_CODES.R)
public class PfdInputReader implements MediaParser.SeekableInputReader {
    private FileChannel fileChannel;
    public PfdInputReader(ParcelFileDescriptor pfd) {
        fileChannel = new FileInputStream(pfd.getFileDescriptor()).getChannel();
    }

    @Override
    public void seekToPosition(long position) {
        try {
            fileChannel.position(position);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int readLength) throws IOException {
        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
        byteBuffer.position(offset);
        byteBuffer.limit(offset + readLength);
        return fileChannel.read(byteBuffer);
    }

    @Override
    public long getPosition() {
        try {
            return fileChannel.position();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long getLength() {
        try {
            return fileChannel.size();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
