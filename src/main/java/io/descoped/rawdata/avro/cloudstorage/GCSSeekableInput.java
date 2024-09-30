package io.descoped.rawdata.avro.cloudstorage;

import com.google.cloud.ReadChannel;
import org.apache.avro.file.SeekableInput;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

class GCSSeekableInput implements SeekableInput {

    private final ReadChannel readChannel;
    private final long size;
    private final AtomicLong positionOfNextByteToBeRead = new AtomicLong(0);

    GCSSeekableInput(ReadChannel readChannel, long size) {
        this.readChannel = readChannel;
        this.size = size;
    }

    @Override
    public void seek(long p) throws IOException {
        readChannel.seek(p);
        positionOfNextByteToBeRead.set(p);
    }

    @Override
    public long tell() {
        return positionOfNextByteToBeRead.get();
    }

    @Override
    public long length() {
        return size;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = readChannel.read(ByteBuffer.wrap(b, off, len));
        if (n > 0) {
            positionOfNextByteToBeRead.addAndGet(n);
        }
        return n;
    }

    @Override
    public void close() {
        readChannel.close();
    }
}
