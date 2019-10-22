package no.ssb.rawdata.gcs;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import de.huxhorn.sulky.ulid.ULID;
import no.ssb.rawdata.api.RawdataClientInitializer;
import no.ssb.rawdata.api.RawdataConsumer;
import no.ssb.rawdata.api.RawdataMessage;
import no.ssb.rawdata.api.RawdataNoSuchPositionException;
import no.ssb.rawdata.api.RawdataNotBufferedException;
import no.ssb.rawdata.api.RawdataProducer;
import no.ssb.service.provider.api.ProviderConfigurator;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

/**
 * Running these tests requires an accessible google-cloud-storage bucket with read and write object access.
 * <p>
 * Requirements: A google cloud service-account key with access to read and write objects in cloud-storage.
 * This can be put as a file at the path "secret/gcs_sa_test.json"
 */
public class GCSRawdataClientTck {

    GCSRawdataClient client;

    @BeforeMethod
    public void createRawdataClient() throws IOException {
        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put("gcs.bucket-name", "bip-drone-dependency-cache");
        configuration.put("local-temp-folder", "target/_tmp_avro_");
        configuration.put("avro-file.max.seconds", "30");
        configuration.put("avro-file.max.bytes", Long.toString(1 * 1024 * 1024)); // 1 MiB
        configuration.put("gcs.listing.min-interval-seconds", "3");
        configuration.put("gcs.service-account.key-file", "secret/gcs_sa_test.json");

        Path localTempFolder = Paths.get(configuration.get("local-temp-folder"));
        if (Files.exists(localTempFolder)) {
            Files.walk(localTempFolder).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
        Files.createDirectories(localTempFolder);
        client = (GCSRawdataClient) ProviderConfigurator.configure(configuration, "gcs", RawdataClientInitializer.class);

        // clear bucket
        String bucket = configuration.get("gcs.bucket-name");
        Storage storage = client.getWritableStorage();
        Page<Blob> page = storage.list(bucket, Storage.BlobListOption.prefix("the-topic"));
        BlobId[] blobs = StreamSupport.stream(page.iterateAll().spliterator(), false).map(BlobInfo::getBlobId).collect(Collectors.toList()).toArray(new BlobId[0]);
        if (blobs.length > 0) {
            List<Boolean> deletedList = storage.delete(blobs);
            for (Boolean deleted : deletedList) {
                if (!deleted) {
                    throw new RuntimeException("Unable to delete blob in bucket");
                }
            }
        }
    }

    @AfterMethod
    public void closeRawdataClient() throws Exception {
        client.close();
    }

    @Test
    public void thatLastPositionOfEmptyTopicCanBeRead() {
        assertNull(client.lastMessage("the-topic"));
    }

    @Test
    public void thatLastPositionOfProducerCanBeRead() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.publish("a", "b");
        }

        assertEquals(client.lastMessage("the-topic").position(), "b");

        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("c");
        }

        assertEquals(client.lastMessage("the-topic").position(), "c");
    }

    @Test(expectedExceptions = RawdataNotBufferedException.class)
    public void thatPublishNonBufferedMessagesThrowsException() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish("unbuffered-1");
        }
    }

    @Test
    public void thatAllFieldsOfMessageSurvivesStream() throws Exception {
        ULID.Value ulid = new ULID().nextValue();
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().ulid(ulid).orderingGroup("og1").sequenceNumber(1).position("a").put("payload1", new byte[3]).put("payload2", new byte[7]));
            producer.buffer(producer.builder().ulid(ulid).orderingGroup("og1").sequenceNumber(1).position("b").put("payload1", new byte[4]).put("payload2", new byte[8]));
            producer.buffer(producer.builder().ulid(ulid).orderingGroup("og1").sequenceNumber(1).position("c").put("payload1", new byte[2]).put("payload2", new byte[5]));
            producer.publish("a", "b", "c");
        }

        try (RawdataConsumer consumer = client.consumer("the-topic", ulid, true)) {
            {
                RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
                assertEquals(message.ulid(), ulid);
                assertEquals(message.orderingGroup(), "og1");
                assertEquals(message.sequenceNumber(), 1);
                assertEquals(message.position(), "a");
                assertEquals(message.keys().size(), 2);
                assertEquals(message.get("payload1"), new byte[3]);
                assertEquals(message.get("payload2"), new byte[7]);
            }
            {
                RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
                assertEquals(message.ulid(), ulid);
                assertEquals(message.orderingGroup(), "og1");
                assertEquals(message.sequenceNumber(), 1);
                assertEquals(message.position(), "b");
                assertEquals(message.keys().size(), 2);
                assertEquals(message.get("payload1"), new byte[4]);
                assertEquals(message.get("payload2"), new byte[8]);
            }
            {
                RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
                assertEquals(message.ulid(), ulid);
                assertEquals(message.orderingGroup(), "og1");
                assertEquals(message.sequenceNumber(), 1);
                assertEquals(message.position(), "c");
                assertEquals(message.keys().size(), 2);
                assertEquals(message.get("payload1"), new byte[2]);
                assertEquals(message.get("payload2"), new byte[5]);
            }
        }
    }

    @Test
    public void thatSingleMessageCanBeProducedAndConsumerSynchronously() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.publish("a");
        }

        try (RawdataConsumer consumer = client.consumer("the-topic")) {
            RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(message.position(), "a");
            assertEquals(message.keys().size(), 2);
        }
    }

    @Test
    public void thatSingleMessageCanBeProducedAndConsumerAsynchronously() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.publish("a");
        }

        try (RawdataConsumer consumer = client.consumer("the-topic")) {

            CompletableFuture<? extends RawdataMessage> future = consumer.receiveAsync();

            RawdataMessage message = future.join();
            assertEquals(message.position(), "a");
            assertEquals(message.keys().size(), 2);
        }
    }

    @Test
    public void thatMultipleMessagesCanBeProducedAndConsumerSynchronously() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("a", "b", "c");
        }

        try (RawdataConsumer consumer = client.consumer("the-topic")) {
            RawdataMessage message1 = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage message2 = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage message3 = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(message1.position(), "a");
            assertEquals(message2.position(), "b");
            assertEquals(message3.position(), "c");
        }
    }

    @Test
    public void thatMultipleMessagesCanBeProducedAndConsumerAsynchronously() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("a", "b", "c");
        }

        try (RawdataConsumer consumer = client.consumer("the-topic")) {

            CompletableFuture<List<RawdataMessage>> future = receiveAsyncAddMessageAndRepeatRecursive(consumer, "c", new ArrayList<>());

            List<RawdataMessage> messages = future.join();

            assertEquals(messages.get(0).position(), "a");
            assertEquals(messages.get(1).position(), "b");
            assertEquals(messages.get(2).position(), "c");
        }
    }

    private CompletableFuture<List<RawdataMessage>> receiveAsyncAddMessageAndRepeatRecursive(RawdataConsumer consumer, String endPosition, List<RawdataMessage> messages) {
        return consumer.receiveAsync().thenCompose(message -> {
            messages.add(message);
            if (endPosition.equals(message.position())) {
                return CompletableFuture.completedFuture(messages);
            }
            return receiveAsyncAddMessageAndRepeatRecursive(consumer, endPosition, messages);
        });
    }

    @Test
    public void thatMessagesCanBeConsumedByMultipleConsumers() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("a", "b", "c");
        }

        try (RawdataConsumer consumer1 = client.consumer("the-topic")) {
            CompletableFuture<List<RawdataMessage>> future1 = receiveAsyncAddMessageAndRepeatRecursive(consumer1, "c", new ArrayList<>());
            try (RawdataConsumer consumer2 = client.consumer("the-topic")) {
                CompletableFuture<List<RawdataMessage>> future2 = receiveAsyncAddMessageAndRepeatRecursive(consumer2, "c", new ArrayList<>());
                List<RawdataMessage> messages2 = future2.join();
                assertEquals(messages2.get(0).position(), "a");
                assertEquals(messages2.get(1).position(), "b");
                assertEquals(messages2.get(2).position(), "c");
            }
            List<RawdataMessage> messages1 = future1.join();
            assertEquals(messages1.get(0).position(), "a");
            assertEquals(messages1.get(1).position(), "b");
            assertEquals(messages1.get(2).position(), "c");
        }
    }

    @Test
    public void thatConsumerCanReadFromBeginning() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.buffer(producer.builder().position("d").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("a", "b", "c", "d");
        }
        try (RawdataConsumer consumer = client.consumer("the-topic")) {
            RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(message.position(), "a");
        }
    }

    @Test
    public void thatConsumerCanReadFromFirstMessage() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.buffer(producer.builder().position("d").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("a", "b", "c", "d");
        }
        try (RawdataConsumer consumer = client.consumer("the-topic", "a", System.currentTimeMillis(), Duration.ofMinutes(1))) {
            RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(message.position(), "b");
        }
    }

    @Test
    public void thatConsumerCanReadFromMiddle() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.buffer(producer.builder().position("d").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("a", "b", "c", "d");
        }
        try (RawdataConsumer consumer = client.consumer("the-topic", "b", System.currentTimeMillis(), Duration.ofMinutes(1))) {
            RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(message.position(), "c");
        }
        try (RawdataConsumer consumer = client.consumer("the-topic", "c", true, System.currentTimeMillis(), Duration.ofMinutes(1))) {
            RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(message.position(), "c");
        }
    }

    @Test
    public void thatConsumerCanReadFromRightBeforeLast() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.buffer(producer.builder().position("d").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("a", "b", "c", "d");
        }
        try (RawdataConsumer consumer = client.consumer("the-topic", "c", System.currentTimeMillis(), Duration.ofMinutes(1))) {
            RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(message.position(), "d");
        }
    }

    @Test
    public void thatConsumerCanReadFromLast() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.buffer(producer.builder().position("d").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("a", "b", "c", "d");
        }
        try (RawdataConsumer consumer = client.consumer("the-topic", "d", System.currentTimeMillis(), Duration.ofMinutes(1))) {
            RawdataMessage message = consumer.receive(100, TimeUnit.MILLISECONDS);
            assertNull(message);
        }
    }

    @Test
    public void thatSeekToWorks() throws Exception {
        long timestampBeforeA;
        long timestampBeforeB;
        long timestampBeforeC;
        long timestampBeforeD;
        long timestampAfterD;
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            timestampBeforeA = System.currentTimeMillis();
            producer.publish("a");
            Thread.sleep(5);
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            timestampBeforeB = System.currentTimeMillis();
            producer.publish("b");
            Thread.sleep(5);
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            timestampBeforeC = System.currentTimeMillis();
            producer.publish("c");
            Thread.sleep(5);
            producer.buffer(producer.builder().position("d").put("payload1", new byte[7]).put("payload2", new byte[7]));
            timestampBeforeD = System.currentTimeMillis();
            producer.publish("d");
            Thread.sleep(5);
            timestampAfterD = System.currentTimeMillis();
        }
        try (RawdataConsumer consumer = client.consumer("the-topic")) {
            consumer.seek(timestampAfterD);
            assertNull(consumer.receive(100, TimeUnit.MILLISECONDS));
            consumer.seek(timestampBeforeD);
            assertEquals(consumer.receive(100, TimeUnit.MILLISECONDS).position(), "d");
            consumer.seek(timestampBeforeB);
            assertEquals(consumer.receive(100, TimeUnit.MILLISECONDS).position(), "b");
            consumer.seek(timestampBeforeC);
            assertEquals(consumer.receive(100, TimeUnit.MILLISECONDS).position(), "c");
            consumer.seek(timestampBeforeA);
            assertEquals(consumer.receive(100, TimeUnit.MILLISECONDS).position(), "a");
        }
    }

    @Test
    public void thatPositionCursorOfValidPositionIsFound() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("a", "b", "c");
        }
        assertNotNull(client.cursorOf("the-topic", "a", true, System.currentTimeMillis(), Duration.ofMinutes(1)));
        assertNotNull(client.cursorOf("the-topic", "b", true, System.currentTimeMillis(), Duration.ofMinutes(1)));
        assertNotNull(client.cursorOf("the-topic", "c", true, System.currentTimeMillis(), Duration.ofMinutes(1)));
    }

    @Test(expectedExceptions = RawdataNoSuchPositionException.class)
    public void thatPositionCursorOfInvalidPositionIsNotFound() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("a", "b", "c");
        }
        assertNull(client.cursorOf("the-topic", "d", true, System.currentTimeMillis(), Duration.ofMinutes(1)));
    }

    @Test(expectedExceptions = RawdataNoSuchPositionException.class)
    public void thatPositionCursorOfEmptyTopicIsNotFound() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
        }
        client.cursorOf("the-topic", "d", true, System.currentTimeMillis(), Duration.ofMinutes(1));
    }

    @Test
    public void thatMultipleGCSFilesCanBeProducedAndReadBack() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.publish("a");
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.publish("b");
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("c");
        }
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("d").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.publish("d");
            producer.buffer(producer.builder().position("e").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.publish("e");
            producer.buffer(producer.builder().position("f").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("f");
        }
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("g").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.publish("g");
            producer.buffer(producer.builder().position("h").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.publish("h");
            producer.buffer(producer.builder().position("i").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("i");
        }

        try (RawdataConsumer consumer = client.consumer("the-topic")) {
            RawdataMessage a = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage b = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage c = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage d = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage e = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage f = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage g = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage h = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage i = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(a.position(), "a");
            assertEquals(b.position(), "b");
            assertEquals(c.position(), "c");
            assertEquals(d.position(), "d");
            assertEquals(e.position(), "e");
            assertEquals(f.position(), "f");
            assertEquals(g.position(), "g");
            assertEquals(h.position(), "h");
            assertEquals(i.position(), "i");
        }
    }

    @Test
    public void thatFilesCreatedAfterConsumerHasSubscribedAreUsed() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.publish("a");
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.publish("b");
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("c");
        }
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("d").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.publish("d");
            producer.buffer(producer.builder().position("e").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.publish("e");
            producer.buffer(producer.builder().position("f").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("f");
        }

        // start a background task that will wait 5 seconds, then publish 3 messages
        CompletableFuture.runAsync(() -> {
            try (RawdataProducer producer = client.producer("the-topic")) {
                Thread.sleep(TimeUnit.SECONDS.toMillis(5));
                producer.buffer(producer.builder().position("g").put("payload1", new byte[5]).put("payload2", new byte[5]));
                producer.publish("g");
                producer.buffer(producer.builder().position("h").put("payload1", new byte[3]).put("payload2", new byte[3]));
                producer.publish("h");
                producer.buffer(producer.builder().position("i").put("payload1", new byte[7]).put("payload2", new byte[7]));
                producer.publish("i");
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try (RawdataConsumer consumer = client.consumer("the-topic")) {
            RawdataMessage a = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage b = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage c = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage d = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage e = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage f = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(a.position(), "a");
            assertEquals(b.position(), "b");
            assertEquals(c.position(), "c");
            assertEquals(d.position(), "d");
            assertEquals(e.position(), "e");
            assertEquals(f.position(), "f");

            // until here is easy. After this point we have to wait for more files to appear on GCS

            RawdataMessage g = consumer.receive(15, TimeUnit.SECONDS);
            RawdataMessage h = consumer.receive(1, TimeUnit.SECONDS);
            RawdataMessage i = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(g.position(), "g");
            assertEquals(h.position(), "h");
            assertEquals(i.position(), "i");
        }
    }

    @Test
    public void thatNonExistentStreamCanBeConsumedFirstAndProducedAfter() throws Exception {
        Thread consumerThread = new Thread(() -> {
            try (RawdataConsumer consumer = client.consumer("the-topic")) {
                RawdataMessage a = consumer.receive(15, TimeUnit.SECONDS);
                RawdataMessage b = consumer.receive(1, TimeUnit.SECONDS);
                RawdataMessage c = consumer.receive(1, TimeUnit.SECONDS);
                RawdataMessage d = consumer.receive(1, TimeUnit.SECONDS);
                RawdataMessage e = consumer.receive(1, TimeUnit.SECONDS);
                RawdataMessage f = consumer.receive(1, TimeUnit.SECONDS);
                RawdataMessage none = consumer.receive(1, TimeUnit.SECONDS);
                assertEquals(a.position(), "a");
                assertEquals(b.position(), "b");
                assertEquals(c.position(), "c");
                assertEquals(d.position(), "d");
                assertEquals(e.position(), "e");
                assertEquals(f.position(), "f");
                assertNull(none);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        consumerThread.start();

        Thread.sleep(1500);

        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.publish("a");
            producer.buffer(producer.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.publish("b");
            producer.buffer(producer.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("c");
        }
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("d").put("payload1", new byte[5]).put("payload2", new byte[5]));
            producer.publish("d");
            producer.buffer(producer.builder().position("e").put("payload1", new byte[3]).put("payload2", new byte[3]));
            producer.publish("e");
            producer.buffer(producer.builder().position("f").put("payload1", new byte[7]).put("payload2", new byte[7]));
            producer.publish("f");
        }

        consumerThread.join();
    }
}
