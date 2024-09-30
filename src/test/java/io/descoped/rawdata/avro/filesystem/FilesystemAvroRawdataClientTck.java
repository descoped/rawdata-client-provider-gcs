package io.descoped.rawdata.avro.filesystem;

import de.huxhorn.sulky.ulid.ULID;
import io.descoped.rawdata.api.RawdataClient;
import io.descoped.rawdata.api.RawdataClientInitializer;
import io.descoped.rawdata.api.RawdataConsumer;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataMetadataClient;
import io.descoped.rawdata.api.RawdataNoSuchPositionException;
import io.descoped.rawdata.api.RawdataProducer;
import io.descoped.service.provider.api.ProviderConfigurator;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

public class FilesystemAvroRawdataClientTck {

    RawdataClient client;

    @BeforeMethod
    public void createRawdataClient() throws IOException {
        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put("local-temp-folder", "target/_tmp_avro_");
        configuration.put("avro-file.max.seconds", "2");
        configuration.put("avro-file.max.bytes", Long.toString(2 * 1024)); // 2 KiB
        configuration.put("avro-file.sync.interval", Long.toString(200));
        configuration.put("listing.min-interval-seconds", "0");
        configuration.put("filesystem.storage-folder", "target/rawdata-store");

        {
            Path folder = Paths.get(configuration.get("local-temp-folder"));
            if (Files.exists(folder)) {
                Files.walk(folder).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
            Files.createDirectories(folder);
        }
        {
            Path folder = Paths.get(configuration.get("filesystem.storage-folder"));
            if (Files.exists(folder)) {
                Files.walk(folder).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
            Files.createDirectories(folder);
        }
        client = ProviderConfigurator.configure(configuration, "filesystem", RawdataClientInitializer.class);
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
            producer.publish(
                    RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build(),
                    RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build()
            );
        }

        assertEquals(client.lastMessage("the-topic").position(), "b");

        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build());
        }

        assertEquals(client.lastMessage("the-topic").position(), "c");
    }

    @Test
    public void thatAllFieldsOfMessageSurvivesStream() throws Exception {
        ULID.Value ulid = new ULID().nextValue();
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(
                    RawdataMessage.builder().ulid(ulid).orderingGroup("og1").sequenceNumber(1).position("a").put("payload1", new byte[3]).put("payload2", new byte[7]).build(),
                    RawdataMessage.builder().orderingGroup("og1").sequenceNumber(1).position("b").put("payload1", new byte[4]).put("payload2", new byte[8]).build(),
                    RawdataMessage.builder().orderingGroup("og1").sequenceNumber(1).position("c").put("payload1", new byte[2]).put("payload2", new byte[5]).build()
            );
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
                assertNotNull(message.ulid());
                assertEquals(message.orderingGroup(), "og1");
                assertEquals(message.sequenceNumber(), 1);
                assertEquals(message.position(), "b");
                assertEquals(message.keys().size(), 2);
                assertEquals(message.get("payload1"), new byte[4]);
                assertEquals(message.get("payload2"), new byte[8]);
            }
            {
                RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
                assertNotNull(message.ulid());
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
        try (RawdataProducer producer = client.producer("a/b/c")) {
            producer.publish(RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build());
        }

        try (RawdataConsumer consumer = client.consumer("a/b/c")) {
            RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(message.position(), "a");
            assertEquals(message.keys().size(), 2);
        }
    }

    @Test
    public void thatSingleMessageCanBeProducedAndConsumerAsynchronously() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build());
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
            producer.publish(
                    RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build(),
                    RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build(),
                    RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build()
            );
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
            producer.publish(
                    RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build(),
                    RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build(),
                    RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build()
            );
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
            producer.publish(RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build(),
                    RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build(),
                    RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build()
            );
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
            producer.publish(
                    RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build(),
                    RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build(),
                    RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build(),
                    RawdataMessage.builder().position("d").put("payload1", new byte[7]).put("payload2", new byte[7]).build()
            );
        }
        try (RawdataConsumer consumer = client.consumer("the-topic")) {
            RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(message.position(), "a");
        }
    }

    @Test
    public void thatConsumerCanReadFromFirstMessage() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(
                    RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build(),
                    RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build(),
                    RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build(),
                    RawdataMessage.builder().position("d").put("payload1", new byte[7]).put("payload2", new byte[7]).build()
            );
        }
        try (RawdataConsumer consumer = client.consumer("the-topic", "a", System.currentTimeMillis(), Duration.ofMinutes(1))) {
            RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(message.position(), "b");
        }
    }

    @Test
    public void thatConsumerCanReadFromMiddle() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(
                    RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build(),
                    RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build(),
                    RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build(),
                    RawdataMessage.builder().position("d").put("payload1", new byte[7]).put("payload2", new byte[7]).build()
            );
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
            producer.publish(
                    RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build(),
                    RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build(),
                    RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build(),
                    RawdataMessage.builder().position("d").put("payload1", new byte[7]).put("payload2", new byte[7]).build()
            );
        }
        try (RawdataConsumer consumer = client.consumer("the-topic", "c", System.currentTimeMillis(), Duration.ofMinutes(1))) {
            RawdataMessage message = consumer.receive(1, TimeUnit.SECONDS);
            assertEquals(message.position(), "d");
        }
    }

    @Test
    public void thatConsumerCanReadFromLast() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(
                    RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build(),
                    RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build(),
                    RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build(),
                    RawdataMessage.builder().position("d").put("payload1", new byte[7]).put("payload2", new byte[7]).build()
            );
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
            timestampBeforeA = System.currentTimeMillis();
            producer.publish(RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build());
            Thread.sleep(5);
            timestampBeforeB = System.currentTimeMillis();
            producer.publish(RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build());
            Thread.sleep(5);
            timestampBeforeC = System.currentTimeMillis();
            producer.publish(RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build());
            Thread.sleep(5);
            timestampBeforeD = System.currentTimeMillis();
            producer.publish(RawdataMessage.builder().position("d").put("payload1", new byte[7]).put("payload2", new byte[7]).build());
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
            producer.publish(
                    RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build(),
                    RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build(),
                    RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build()
            );
        }
        assertNotNull(client.cursorOf("the-topic", "a", true, System.currentTimeMillis(), Duration.ofMinutes(1)));
        assertNotNull(client.cursorOf("the-topic", "b", true, System.currentTimeMillis(), Duration.ofMinutes(1)));
        assertNotNull(client.cursorOf("the-topic", "c", true, System.currentTimeMillis(), Duration.ofMinutes(1)));
    }

    @Test(expectedExceptions = RawdataNoSuchPositionException.class)
    public void thatPositionCursorOfInvalidPositionIsNotFound() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(
                    RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build(),
                    RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build(),
                    RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build()
            );
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
            producer.publish(RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build());
            producer.publish(RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build());
            producer.publish(RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build());
        }
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(RawdataMessage.builder().position("d").put("payload1", new byte[5]).put("payload2", new byte[5]).build());
            producer.publish(RawdataMessage.builder().position("e").put("payload1", new byte[3]).put("payload2", new byte[3]).build());
            producer.publish(RawdataMessage.builder().position("f").put("payload1", new byte[7]).put("payload2", new byte[7]).build());
        }
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(RawdataMessage.builder().position("g").put("payload1", new byte[5]).put("payload2", new byte[5]).build());
            producer.publish(RawdataMessage.builder().position("h").put("payload1", new byte[3]).put("payload2", new byte[3]).build());
            producer.publish(RawdataMessage.builder().position("i").put("payload1", new byte[7]).put("payload2", new byte[7]).build());
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
    public void thatMultipleGCSFilesCanBeProducedThroughSizeBasedWindowingAndReadBack() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            for (int i = 0; i < 100; i++) {
                producer.publish(RawdataMessage.builder().position("a" + i)
                        .put("attribute-1", ("a" + i + "_").getBytes(StandardCharsets.UTF_8))
                        .put("payload", "ABC_".repeat(i).getBytes(StandardCharsets.UTF_8))
                        .build());
            }
        }

        try (RawdataConsumer consumer = client.consumer("the-topic")) {
            for (int i = 0; i < 100; i++) {
                RawdataMessage msg = consumer.receive(1, TimeUnit.SECONDS);
                assertEquals(msg.position(), "a" + i);
                assertEquals(new String(msg.get("attribute-1"), StandardCharsets.UTF_8), "a" + i + "_");
                assertEquals(new String(msg.get("payload"), StandardCharsets.UTF_8), "ABC_".repeat(i));
            }
            RawdataMessage msg = consumer.receive(100, TimeUnit.MILLISECONDS);
            assertNull(msg);
        }
    }

    @Test
    public void thatMultipleGCSFilesCanBeProducedThroughTimeBasedWindowingAndReadBack() throws Exception {
        int N = 3;
        try (RawdataProducer producer = client.producer("the-topic")) {
            for (int i = 0; i < N; i++) {
                producer.publish(RawdataMessage.builder().position("a" + i)
                        .put("attribute-1", ("a" + i).getBytes(StandardCharsets.UTF_8))
                        .build());
                if (i < N - 1) {
                    Thread.sleep(1100);
                }
            }
        }

        try (RawdataConsumer consumer = client.consumer("the-topic")) {
            for (int i = 0; i < N; i++) {
                RawdataMessage msg = consumer.receive(1, TimeUnit.SECONDS);
                assertEquals(msg.position(), "a" + i);
                assertEquals(new String(msg.get("attribute-1"), StandardCharsets.UTF_8), "a" + i);
            }
            RawdataMessage msg = consumer.receive(10, TimeUnit.MILLISECONDS);
            assertNull(msg);
        }
    }

    @Test
    public void thatFilesCreatedAfterConsumerHasSubscribedAreUsed() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build());
            producer.publish(RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build());
            producer.publish(RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build());
        }
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(RawdataMessage.builder().position("d").put("payload1", new byte[5]).put("payload2", new byte[5]).build());
            producer.publish(RawdataMessage.builder().position("e").put("payload1", new byte[3]).put("payload2", new byte[3]).build());
            producer.publish(RawdataMessage.builder().position("f").put("payload1", new byte[7]).put("payload2", new byte[7]).build());
        }

        // start a background task that will wait 1 second, then publish 3 messages
        CompletableFuture.runAsync(() -> {
            try (RawdataProducer producer = client.producer("the-topic")) {
                Thread.sleep(TimeUnit.SECONDS.toMillis(1));
                producer.publish(RawdataMessage.builder().position("g").put("payload1", new byte[5]).put("payload2", new byte[5]).build());
                producer.publish(RawdataMessage.builder().position("h").put("payload1", new byte[3]).put("payload2", new byte[3]).build());
                producer.publish(RawdataMessage.builder().position("i").put("payload1", new byte[7]).put("payload2", new byte[7]).build());
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
                RawdataMessage a = consumer.receive(1, TimeUnit.SECONDS);
                RawdataMessage b = consumer.receive(100, TimeUnit.MILLISECONDS);
                RawdataMessage c = consumer.receive(100, TimeUnit.MILLISECONDS);
                RawdataMessage d = consumer.receive(100, TimeUnit.MILLISECONDS);
                RawdataMessage e = consumer.receive(100, TimeUnit.MILLISECONDS);
                RawdataMessage f = consumer.receive(100, TimeUnit.MILLISECONDS);
                RawdataMessage none = consumer.receive(100, TimeUnit.MILLISECONDS);
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

        Thread.sleep(300);

        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(RawdataMessage.builder().position("a").put("payload1", new byte[5]).put("payload2", new byte[5]).build());
            producer.publish(RawdataMessage.builder().position("b").put("payload1", new byte[3]).put("payload2", new byte[3]).build());
            producer.publish(RawdataMessage.builder().position("c").put("payload1", new byte[7]).put("payload2", new byte[7]).build());
            producer.publish(RawdataMessage.builder().position("d").put("payload1", new byte[5]).put("payload2", new byte[5]).build());
            producer.publish(RawdataMessage.builder().position("e").put("payload1", new byte[3]).put("payload2", new byte[3]).build());
            producer.publish(RawdataMessage.builder().position("f").put("payload1", new byte[7]).put("payload2", new byte[7]).build());
        }

        consumerThread.join();
    }

    @Test
    public void thatReadLastMessageWorksWithMultipleBlocks() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(RawdataMessage.builder().position("a").put("payload1", new byte[50]).put("payload2", new byte[50]).build());
            producer.publish(RawdataMessage.builder().position("b").put("payload1", new byte[30]).put("payload2", new byte[30]).build());
            producer.publish(RawdataMessage.builder().position("c").put("payload1", new byte[70]).put("payload2", new byte[70]).build());
            producer.publish(RawdataMessage.builder().position("d").put("payload1", new byte[50]).put("payload2", new byte[50]).build());
            producer.publish(RawdataMessage.builder().position("e").put("payload1", new byte[30]).put("payload2", new byte[30]).build());
            producer.publish(RawdataMessage.builder().position("f").put("payload1", new byte[70]).put("payload2", new byte[70]).build());
            producer.publish(RawdataMessage.builder().position("g").put("payload1", new byte[50]).put("payload2", new byte[50]).build());
            producer.publish(RawdataMessage.builder().position("h").put("payload1", new byte[30]).put("payload2", new byte[30]).build());
            producer.publish(RawdataMessage.builder().position("i").put("payload1", new byte[70]).put("payload2", new byte[70]).build());
        }

        RawdataMessage lastMessage = client.lastMessage("the-topic");
        assertEquals(lastMessage.position(), "i");
    }

    @Test
    public void thatReadLastMessageWorksWithSingleBlock() throws Exception {
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.publish(RawdataMessage.builder().position("a").put("payload1", new byte[50]).put("payload2", new byte[50]).build());
        }

        RawdataMessage lastMessage = client.lastMessage("the-topic");
        assertEquals(lastMessage.position(), "a");
    }

    @Test
    public void thatMetadataCanBeWrittenListedAndRead() {
        RawdataMetadataClient metadata = client.metadata("the-topic");
        assertEquals(metadata.topic(), "the-topic");
        assertEquals(metadata.keys().size(), 0);
        String key1 = "//./key-1'§!#$%&/()=?";
        String key2 = ".";
        String key3 = "..";
        metadata.put(key1, "Value-1".getBytes(StandardCharsets.UTF_8));
        metadata.put(key2, "Value-2".getBytes(StandardCharsets.UTF_8));
        metadata.put(key3, "Value-3".getBytes(StandardCharsets.UTF_8));
        assertEquals(metadata.keys().size(), 3);
        assertEquals(new String(metadata.get(key1), StandardCharsets.UTF_8), "Value-1");
        assertEquals(new String(metadata.get(key2), StandardCharsets.UTF_8), "Value-2");
        metadata.put(key2, "Overwritten-Value-2".getBytes(StandardCharsets.UTF_8));
        assertEquals(metadata.keys().size(), 3);
        assertEquals(new String(metadata.get(key2), StandardCharsets.UTF_8), "Overwritten-Value-2");
        metadata.remove(key3);
        assertEquals(metadata.keys().size(), 2);
    }
}
