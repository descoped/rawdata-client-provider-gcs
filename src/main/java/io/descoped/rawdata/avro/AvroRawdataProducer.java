package io.descoped.rawdata.avro;

import de.huxhorn.sulky.ulid.ULID;
import io.descoped.rawdata.api.RawdataClosedException;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataProducer;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.file.SeekableFileInput;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

class AvroRawdataProducer implements RawdataProducer {

    static final Logger LOG = LoggerFactory.getLogger(AvroRawdataProducer.class);

    static final Schema schema = SchemaBuilder.record("RawdataMessage")
            .fields()
            .name("id").type().fixed("ulid").size(16).noDefault()
            .name("orderingGroup").type().nullable().stringType().noDefault()
            .name("sequenceNumber").type().longType().longDefault(0)
            .name("position").type().stringType().noDefault()
            .name("data").type().map().values().bytesType().noDefault()
            .endRecord();

    final AtomicBoolean closed = new AtomicBoolean(false);

    final ULID ulid = new ULID();
    final AtomicReference<ULID.Value> prevUlid = new AtomicReference<>(ulid.nextValue());

    final AvroRawdataUtils gcsRawdataUtils;
    final Path tmpFolder;
    final long avroMaxSeconds;
    final long avroMaxBytes;
    final int avroSyncInterval;
    final String topic;

    final AtomicReference<DataFileWriter<GenericRecord>> dataFileWriterRef = new AtomicReference<>();
    final Path topicFolder;
    final AtomicReference<Path> pathRef = new AtomicReference<>();

    final AtomicLong timestampOfFirstMessageInWindow = new AtomicLong(-1);
    final AvroFileMetadata activeAvrofileMetadata;
    final AtomicLong avroBytesWrittenInBlock = new AtomicLong(0);

    final ReentrantLock lock = new ReentrantLock();

    final Thread uploadThread;
    final BlockingQueue<Upload> uploadQueue = new LinkedBlockingQueue<>();

    static class Upload {
        final Path source;
        final RawdataAvroFile target;

        Upload(Path source, RawdataAvroFile target) {
            this.source = source;
            this.target = target;
        }
    }

    AvroRawdataProducer(AvroRawdataUtils gcsRawdataUtils, Path tmpFolder, long avroMaxSeconds, long avroMaxBytes, int avroSyncInterval, String topic) {
        this.gcsRawdataUtils = gcsRawdataUtils;
        this.tmpFolder = tmpFolder;
        this.avroMaxSeconds = avroMaxSeconds;
        this.avroMaxBytes = avroMaxBytes;
        this.avroSyncInterval = avroSyncInterval;
        this.topic = topic;
        this.activeAvrofileMetadata = gcsRawdataUtils.newAvrofileMetadata();
        this.topicFolder = tmpFolder.resolve(topic);
        try {
            Files.createDirectories(topicFolder);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        createOrOverwriteLocalAvroFile();
        this.uploadThread = new Thread(() -> {
            for (; ; ) {
                final Upload upload;
                try {
                    upload = uploadQueue.take(); // wait for upload task
                } catch (InterruptedException e) {
                    LOG.warn("Closing producer topic {}", topic);
                    close();
                    LOG.warn("Upload thread interrupted. Upload thread for producer of topic {} will now die.", topic);
                    return;
                }
                try {
                    if (upload.source == null) {
                        LOG.info("Upload thread for producer of topic {} received close signal and will now die.", topic);
                        return;
                    }
                    verifySeekableToLastBlockOffsetAsGivenByFilename(upload.source, upload.target.getOffsetOfLastBlock());
                    String fileSize = AvroRawdataUtils.humanReadableByteCount(upload.source.toFile().length(), false);
                    LOG.info("Copying Avro file {} ({}) to target: {}", upload.source.getFileName(), fileSize, upload.target);
                    upload.target.copyFrom(upload.source);
                    Files.delete(upload.source);
                    LOG.info("Copy COMPLETE! Deleted Avro file {}", upload.source.getFileName());
                } catch (Throwable t) {
                    LOG.error(String.format("While uploading file %s to target %s", upload.source.getFileName(), upload.target), t);
                    LOG.warn("Closing producer topic {}", topic);
                    close();
                    LOG.warn("Upload thread for producer of topic {} will now die.", topic);
                    return;
                }
            }
        });
        this.uploadThread.start();
    }

    private void createOrOverwriteLocalAvroFile() {
        try {
            if (!lock.tryLock(5, TimeUnit.MINUTES)) {
                throw new IllegalStateException("Unable to acquire lock within 5 minutes");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        try {
            Path path = Files.createTempFile(topicFolder, "", ".avro");
            pathRef.set(path);
            activeAvrofileMetadata.clear();
            DatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<>(schema);
            DataFileWriter<GenericRecord> dataFileWriter = new DataFileWriter<>(datumWriter);
            dataFileWriter.setSyncInterval(2 * avroSyncInterval);
            dataFileWriter.setFlushOnEveryBlock(true);
            dataFileWriterRef.set(dataFileWriter);
            dataFileWriter.create(schema, path.toFile());
            long lastSyncPosition = dataFileWriter.sync(); // position of first block
            activeAvrofileMetadata.setSyncOfLastBlock(lastSyncPosition);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    private void closeAvroFileAndTriggerAsyncUploadToGCS() {
        try {
            if (!lock.tryLock(5, TimeUnit.MINUTES)) {
                throw new IllegalStateException("Unable to acquire lock within 5 minutes");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        try {
            DataFileWriter<GenericRecord> dataFileWriter = dataFileWriterRef.getAndSet(null);
            if (dataFileWriter != null) {
                dataFileWriter.flush();
                dataFileWriter.close();
            }
            Path path = pathRef.get();
            if (path != null) {
                if (activeAvrofileMetadata.getCount() > 0) {
                    RawdataAvroFile rawdataAvroFile = activeAvrofileMetadata.toRawdataAvroFile(topic);
                    uploadQueue.add(new Upload(path, rawdataAvroFile)); // schedule upload asynchronously
                } else {
                    // no records, no need to write file to GCS
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    static void verifySeekableToLastBlockOffsetAsGivenByFilename(Path path, long offsetOfLastBlock) throws IOException {
        DatumReader<GenericRecord> datumReader = new GenericDatumReader<>(AvroRawdataProducer.schema);
        try (DataFileReader<GenericRecord> dataFileReader = new DataFileReader<>(new SeekableFileInput(path.toFile()), datumReader)) {
            dataFileReader.seek(offsetOfLastBlock);
            dataFileReader.hasNext(); // will throw an exception if offset is wrong
        }
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public void publish(RawdataMessage... messages) throws RawdataClosedException {
        if (isClosed()) {
            throw new RawdataClosedException();
        }
        try {
            if (!lock.tryLock(5, TimeUnit.MINUTES)) {
                throw new IllegalStateException("Unable to acquire lock within 5 minutes");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        try {
            for (RawdataMessage message : messages) {
                long now = System.currentTimeMillis();
                timestampOfFirstMessageInWindow.compareAndSet(-1, now);

                boolean timeLimitExceeded = timestampOfFirstMessageInWindow.get() + 1000 * avroMaxSeconds < now;
                if (timeLimitExceeded) {
                    closeAvroFileAndTriggerAsyncUploadToGCS();
                    createOrOverwriteLocalAvroFile();
                    timestampOfFirstMessageInWindow.set(now);
                }

                ULID.Value ulidValue = message.ulid();
                if (ulidValue == null) {
                    ulidValue = RawdataProducer.nextMonotonicUlid(ulid, prevUlid.get());
                }
                prevUlid.set(ulidValue);

                activeAvrofileMetadata.setIdOfFirstRecord(ulidValue);
                activeAvrofileMetadata.setPositionOfFirstRecord(message.position());

                try {
                    GenericRecord record = new GenericData.Record(schema);
                    record.put("id", new GenericData.Fixed(schema.getField("id").schema(), ulidValue.toBytes()));
                    record.put("orderingGroup", message.orderingGroup());
                    record.put("sequenceNumber", message.sequenceNumber());
                    record.put("position", message.position());
                    record.put("data", message.data().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> ByteBuffer.wrap(e.getValue()))));

                    if (avroBytesWrittenInBlock.get() >= avroSyncInterval) {
                        // start new block in avro file
                        long lastSyncPosition = dataFileWriterRef.get().sync();
                        activeAvrofileMetadata.setSyncOfLastBlock(lastSyncPosition);
                        avroBytesWrittenInBlock.set(0);
                    }
                    dataFileWriterRef.get().append(record);
                    activeAvrofileMetadata.incrementCounter(1);
                    avroBytesWrittenInBlock.addAndGet(estimateAvroSizeOfRawdataMessage(message));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                boolean sizeLimitExceeded = pathRef.get().toFile().length() > avroMaxBytes;
                if (sizeLimitExceeded) {
                    closeAvroFileAndTriggerAsyncUploadToGCS();
                    createOrOverwriteLocalAvroFile();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    static long estimateAvroSizeOfRawdataMessage(RawdataMessage message) {
        return 16 + // ulid
                2 + ofNullable(message.orderingGroup()).map(String::length).orElse(0) + // orderingGroup
                6 + // sequenceNumber
                2 + message.position().length() // position
                + message.data().entrySet().stream()
                .map(e -> 2L + e.getKey().length() + 4 + e.getValue().length)
                .reduce(0L, Long::sum);
    }

    @Override
    public CompletableFuture<Void> publishAsync(RawdataMessage... messages) {
        if (isClosed()) {
            throw new RawdataClosedException();
        }
        return CompletableFuture.runAsync(() -> publish(messages));
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeAvroFileAndTriggerAsyncUploadToGCS();
            uploadQueue.add(new Upload(null, null)); // send close signal to upload-thread.
        }
        try {
            // all callers must wait for all uploads to complete
            uploadThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
