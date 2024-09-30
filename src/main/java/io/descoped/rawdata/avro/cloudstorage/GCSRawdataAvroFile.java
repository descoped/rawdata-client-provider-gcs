package io.descoped.rawdata.avro.cloudstorage;

import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import io.descoped.rawdata.avro.RawdataAvroFile;
import org.apache.avro.file.SeekableInput;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

class GCSRawdataAvroFile implements RawdataAvroFile {

    private final Storage storage;
    private final Blob blob;
    private final BlobId blobId;

    GCSRawdataAvroFile(Storage storage, Blob blob) {
        this.storage = storage;
        this.blob = blob;
        this.blobId = blob.getBlobId();
    }

    GCSRawdataAvroFile(Storage storage, BlobId blobId) {
        this.storage = storage;
        this.blob = null;
        this.blobId = blobId;
    }

    @Override
    public SeekableInput seekableInput() {
        if (blob == null) {
            throw new IllegalStateException("Cannot get seekableInput of method when blob is null");
        }
        return new GCSSeekableInput(blob.reader(), blob.getSize());
    }

    @Override
    public long getOffsetOfLastBlock() {
        return GCSRawdataUtils.getOffsetOfLastBlock(blobId);
    }

    @Override
    public void copyFrom(Path sourcePath) {
        try (WriteChannel writeChannel = storage.writer(BlobInfo.newBuilder(blobId)
                .setContentType("text/plain")
                .build())) {
            writeChannel.setChunkSize(8 * 1024 * 1024); // 8 MiB
            try (FileChannel source = new RandomAccessFile(sourcePath.toFile(), "r").getChannel()) {
                long bytesTransferred = 0;
                while (bytesTransferred < source.size()) {
                    bytesTransferred += source.transferTo(bytesTransferred, source.size(), writeChannel);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return "GCSRawdataAvroFile{" +
                "blobId=" + blobId +
                '}';
    }
}
