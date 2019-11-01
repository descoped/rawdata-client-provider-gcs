package no.ssb.rawdata.avro.cloudstorage;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import no.ssb.rawdata.api.RawdataClient;
import no.ssb.rawdata.api.RawdataClientInitializer;
import no.ssb.rawdata.api.RawdataConsumer;
import no.ssb.rawdata.api.RawdataMessage;
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
public class AvroRawdataClientIntegrationTest {

    RawdataClient client;

    @BeforeMethod
    public void createRawdataClient() throws IOException {
        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put("gcs.bucket-name", "bip-drone-dependency-cache");
        configuration.put("local-temp-folder", "target/_tmp_avro_");
        configuration.put("avro-file.max.seconds", "3");
        configuration.put("avro-file.max.bytes", Long.toString(2 * 1024)); // 2 KiB
        configuration.put("avro-file.sync.interval", Long.toString(200));
        configuration.put("gcs.listing.min-interval-seconds", "3");
        configuration.put("gcs.service-account.key-file", "secret/gcs_sa_test.json");

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
        Storage storage = GCSRawdataClientInitializer.getWritableStorage(Path.of(configuration.get("gcs.service-account.key-file")));
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
    public void thatMostFunctionsWorkWhenIntegratedWithGCS() throws Exception {
        assertNull(client.lastMessage("the-topic"));

        long timestampBeforeA;
        long timestampBeforeB;
        long timestampBeforeC;
        long timestampBeforeD;
        long timestampAfterD;
        try (RawdataProducer producer = client.producer("the-topic")) {
            producer.buffer(producer.builder().position("a").put("payload1", new byte[1000]).put("payload2", new byte[500]));
            timestampBeforeA = System.currentTimeMillis();
            producer.publish("a");
            Thread.sleep(5);
            producer.buffer(producer.builder().position("b").put("payload1", new byte[400]).put("payload2", new byte[700]));
            timestampBeforeB = System.currentTimeMillis();
            producer.publish("b");
            Thread.sleep(5);
            producer.buffer(producer.builder().position("c").put("payload1", new byte[700]).put("payload2", new byte[70]));
            timestampBeforeC = System.currentTimeMillis();
            producer.publish("c");
            Thread.sleep(5);
            producer.buffer(producer.builder().position("d").put("payload1", new byte[8050]).put("payload2", new byte[130]));
            timestampBeforeD = System.currentTimeMillis();
            producer.publish("d");
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
    }
}
