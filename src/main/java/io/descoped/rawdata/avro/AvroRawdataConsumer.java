package io.descoped.rawdata.avro;

import de.huxhorn.sulky.ulid.ULID;
import io.descoped.rawdata.api.RawdataClosedException;
import io.descoped.rawdata.api.RawdataConsumer;
import io.descoped.rawdata.api.RawdataMessage;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.util.Utf8;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

class AvroRawdataConsumer implements RawdataConsumer {

    final String topic;
    final TopicAvroFileCache gcsTopicAvroFileCache;
    final AtomicReference<Long> activeBlobFromKeyRef = new AtomicReference<>(-1L);
    final AtomicReference<DataFileReader<GenericRecord>> activeBlobDataFileReaderRef = new AtomicReference<>(null);
    final AtomicBoolean closed = new AtomicBoolean(false);
    final Deque<RawdataMessage> preloadedMessages = new ConcurrentLinkedDeque<>();

    AvroRawdataConsumer(AvroRawdataUtils gcsRawdataUtils, String topic, AvroRawdataCursor cursor, int minFileListingIntervalSeconds) {
        this.topic = topic;
        this.gcsTopicAvroFileCache = new TopicAvroFileCache(gcsRawdataUtils, topic, minFileListingIntervalSeconds);
        if (cursor == null) {
            seek(0);
        } else {
            seek(cursor.ulid.timestamp());
            try {
                RawdataMessage msg;
                while ((msg = receive(0, TimeUnit.SECONDS)) != null) {
                    if (msg.ulid().equals(cursor.ulid)) {
                        if (cursor.inclusive) {
                            preloadedMessages.addFirst(msg);
                        }
                        break; // found match
                    }
                    if (msg.timestamp() > cursor.ulid.timestamp()) {
                        // past possible point of match, use this message as starting point
                        preloadedMessages.addFirst(msg);
                        break;
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public RawdataMessage receive(int timeout, TimeUnit unit) throws InterruptedException, RawdataClosedException {
        final long start = System.currentTimeMillis();
        RawdataMessage preloadedMessage = preloadedMessages.poll();
        if (preloadedMessage != null) {
            return preloadedMessage;
        }
        DataFileReader<GenericRecord> dataFileReader = activeBlobDataFileReaderRef.get();
        if (dataFileReader == null) {
            Map.Entry<Long, RawdataAvroFile> nextEntry = findNextGCSBlob(timeout, unit, start);
            if (nextEntry == null) return null; // timeout
            activeBlobFromKeyRef.set(nextEntry.getKey());
            dataFileReader = setDataFileReader(nextEntry.getValue());
        }
        if (!dataFileReader.hasNext()) {
            Map.Entry<Long, RawdataAvroFile> nextEntry = findNextGCSBlob(timeout, unit, start);
            if (nextEntry == null) return null; // timeout
            activeBlobFromKeyRef.set(nextEntry.getKey());
            RawdataAvroFile rawdataAvroFile = nextEntry.getValue();
            setDataFileReader(rawdataAvroFile);
            return receive(timeout, unit);
        }
        GenericRecord record = dataFileReader.next();
        RawdataMessage msg = toRawdataMessage(record);
        return msg;
    }

    private Map.Entry<Long, RawdataAvroFile> findNextGCSBlob(int timeout, TimeUnit unit, long start) throws InterruptedException {
        Long currentBlobKey = activeBlobFromKeyRef.get();
        Map.Entry<Long, RawdataAvroFile> nextEntry = gcsTopicAvroFileCache.blobsByTimestamp().higherEntry(currentBlobKey);
        while (nextEntry == null) {
            // TODO the GCS file-listing poll-loop can be replaced with notifications from pub/sub
            // TODO if so, the poll-loop should be a fallback when google-pub/sub is unavailable
            long duration = System.currentTimeMillis() - start;
            if (duration >= unit.toMillis(timeout)) {
                return null; // timeout
            }
            Thread.sleep(500);
            nextEntry = gcsTopicAvroFileCache.blobsByTimestamp().higherEntry(currentBlobKey);
        }
        return nextEntry;
    }

    static RawdataMessage toRawdataMessage(GenericRecord record) {
        GenericData.Fixed id = (GenericData.Fixed) record.get("id");
        ULID.Value ulid = ULID.fromBytes(id.bytes());
        String orderingGroup = ofNullable(record.get("orderingGroup")).map(Object::toString).orElse(null);
        long sequenceNumber = (Long) record.get("sequenceNumber");
        String position = record.get("position").toString();
        Map<Utf8, ByteBuffer> data = (Map<Utf8, ByteBuffer>) record.get("data");
        Map<String, byte[]> map = data.entrySet().stream().collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().array()));

        return RawdataMessage.builder()
                .ulid(ulid)
                .orderingGroup(orderingGroup)
                .sequenceNumber(sequenceNumber)
                .position(position)
                .data(map)
                .build();
    }

    @Override
    public CompletableFuture<? extends RawdataMessage> receiveAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return receive(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void seek(long timestamp) {
        preloadedMessages.clear();
        DataFileReader<GenericRecord> previousDataFileReader = activeBlobDataFileReaderRef.getAndSet(null);
        if (previousDataFileReader != null) {
            try {
                previousDataFileReader.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        NavigableMap<Long, RawdataAvroFile> blobByFrom = gcsTopicAvroFileCache.blobsByTimestamp();
        Map.Entry<Long, RawdataAvroFile> firstEntryHigherOrEqual = blobByFrom.floorEntry(timestamp);
        if (firstEntryHigherOrEqual == null) {
            firstEntryHigherOrEqual = blobByFrom.ceilingEntry(timestamp);
        }
        if (firstEntryHigherOrEqual == null) {
            activeBlobFromKeyRef.set(-1L);
            return;
        }
        activeBlobFromKeyRef.set(firstEntryHigherOrEqual.getKey());
        RawdataAvroFile rawdataAvroFile = firstEntryHigherOrEqual.getValue();
        DataFileReader<GenericRecord> dataFileReader = setDataFileReader(rawdataAvroFile);
        GenericRecord record = null;
        while (dataFileReader.hasNext()) {
            try {
                record = dataFileReader.next(record);
                RawdataMessage message = toRawdataMessage(record);
                long msgTimestamp = message.timestamp();
                if (msgTimestamp >= timestamp) {
                    preloadedMessages.add(message);
                    return; // first match
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private DataFileReader<GenericRecord> setDataFileReader(RawdataAvroFile rawdataAvroFile) {
        DatumReader<GenericRecord> datumReader = new GenericDatumReader<>(AvroRawdataProducer.schema);
        DataFileReader<GenericRecord> dataFileReader;
        try {
            dataFileReader = new DataFileReader<>(rawdataAvroFile.seekableInput(), datumReader);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        activeBlobDataFileReaderRef.set(dataFileReader);
        return dataFileReader;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            activeBlobFromKeyRef.set(null);
            gcsTopicAvroFileCache.clear();
            preloadedMessages.clear();
            DataFileReader<GenericRecord> dataFileReader = activeBlobDataFileReaderRef.getAndSet(null);
            if (dataFileReader != null) {
                try {
                    dataFileReader.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
