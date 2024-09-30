package io.descoped.rawdata.avro.cloudstorage;

import com.google.api.gax.paging.Page;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import io.descoped.rawdata.api.RawdataClient;
import io.descoped.rawdata.api.RawdataClientInitializer;
import io.descoped.rawdata.api.RawdataConsumer;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataMetadataClient;
import io.descoped.rawdata.api.RawdataProducer;
import io.descoped.service.provider.api.ProviderConfigurator;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Ignore;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
public class GCSRawdataClientIntegrationTest {

    RawdataClient client;

    @BeforeMethod
    public void createRawdataClient() throws IOException {
        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put("gcs.bucket-name", "test-gcs-provider");
        configuration.put("local-temp-folder", "target/_tmp_avro_");
        configuration.put("avro-file.max.seconds", "3");
        configuration.put("avro-file.max.bytes", Long.toString(2 * 1024)); // 2 KiB
        configuration.put("avro-file.sync.interval", Long.toString(200));
        configuration.put("gcs.listing.min-interval-seconds", "3");
        configuration.put("gcs.credential-provider", "service-account");
        configuration.put("gcs.service-account.key-file", "secret/dev-sirius-e9f1008a4f11.json");

        String rawdataGcsBucket = System.getenv("RAWDATA_GCS_BUCKET");
        if (rawdataGcsBucket != null) {
            configuration.put("gcs.bucket-name", rawdataGcsBucket);
        }

        String rawdataGcsSaKeyFile = System.getenv("RAWDATA_GCS_SERVICE_ACCOUNT_KEY_FILE");
        if (rawdataGcsSaKeyFile != null) {
            configuration.put("gcs.service-account.key-file", rawdataGcsSaKeyFile);
        }

        Path localTempFolder = Paths.get(configuration.get("local-temp-folder"));
        if (Files.exists(localTempFolder)) {
            Files.walk(localTempFolder).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
        Files.createDirectories(localTempFolder);
        client = ProviderConfigurator.configure(configuration, "gcs", RawdataClientInitializer.class);

        // clear bucket
        String bucket = configuration.get("gcs.bucket-name");

        ServiceAccountCredentials credentials = ServiceAccountCredentials.fromStream(Files.newInputStream(Path.of(configuration.get("gcs.service-account.key-file")), StandardOpenOption.READ));
        Storage storage = GCSRawdataClientInitializer.getWritableStorage(credentials);
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

    @Ignore
    @Test
    public void thatMostFunctionsWorkWhenIntegratedWithGCS() throws Exception {
        assertNull(client.lastMessage("the-topic"));

        {
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
            assertEquals(new String(metadata.get(key3), StandardCharsets.UTF_8), "Value-3");
            metadata.put(key2, "Overwritten-Value-2".getBytes(StandardCharsets.UTF_8));
            assertEquals(metadata.keys().size(), 3);
            assertEquals(new String(metadata.get(key2), StandardCharsets.UTF_8), "Overwritten-Value-2");
            metadata.remove(key3);
            assertEquals(metadata.keys().size(), 2);
        }

        long timestampBeforeA;
        long timestampBeforeB;
        long timestampBeforeC;
        long timestampBeforeD;
        long timestampAfterD;
        try (RawdataProducer producer = client.producer("the-topic")) {
            timestampBeforeA = System.currentTimeMillis();
            producer.publish(RawdataMessage.builder().position("a").put("payload1", new byte[1000]).put("payload2", new byte[500]).build());
            Thread.sleep(5);
            timestampBeforeB = System.currentTimeMillis();
            producer.publish(RawdataMessage.builder().position("b").put("payload1", new byte[400]).put("payload2", new byte[700]).build());
            Thread.sleep(5);
            timestampBeforeC = System.currentTimeMillis();
            producer.publish(RawdataMessage.builder().position("c").put("payload1", new byte[700]).put("payload2", new byte[70]).build());
            Thread.sleep(5);
            timestampBeforeD = System.currentTimeMillis();
            producer.publish(RawdataMessage.builder().position("d").put("payload1", new byte[8050]).put("payload2", new byte[130]).build());
            Thread.sleep(5);
            timestampAfterD = System.currentTimeMillis();
        }

        RawdataMessage lastMessage = client.lastMessage("the-topic");
        assertNotNull(lastMessage);
        assertEquals(lastMessage.position(), "d");

        try (RawdataConsumer consumer = client.consumer("the-topic")) {
            assertEquals(consumer.receive(1, TimeUnit.SECONDS).position(), "a");
            assertEquals(consumer.receive(1, TimeUnit.SECONDS).position(), "b");
            assertEquals(consumer.receive(1, TimeUnit.SECONDS).position(), "c");
            assertEquals(consumer.receive(1, TimeUnit.SECONDS).position(), "d");
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

        {
            RawdataMetadataClient metadata = client.metadata("the-topic");
            assertEquals(metadata.keys().size(), 2);
            String key = "uh9458goqkadl";
            metadata.put(key, "Value-blah-blah".getBytes(StandardCharsets.UTF_8));
            assertEquals(metadata.keys().size(), 3);
        }
    }

    @Ignore
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
        assertEquals(new String(metadata.get(key3), StandardCharsets.UTF_8), "Value-3");
        metadata.put(key2, "Overwritten-Value-2".getBytes(StandardCharsets.UTF_8));
        assertEquals(metadata.keys().size(), 3);
        assertEquals(new String(metadata.get(key2), StandardCharsets.UTF_8), "Overwritten-Value-2");
        metadata.remove(key3);
        assertEquals(metadata.keys().size(), 2);
    }
}
