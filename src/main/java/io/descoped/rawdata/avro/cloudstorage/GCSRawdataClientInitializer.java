package io.descoped.rawdata.avro.cloudstorage;

import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import io.descoped.rawdata.api.RawdataClientInitializer;
import io.descoped.rawdata.avro.AvroRawdataUtils;
import io.descoped.service.provider.api.ProviderName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

@ProviderName("gcs")
public class GCSRawdataClientInitializer implements RawdataClientInitializer {

    @Override
    public String providerId() {
        return "gcs";
    }

    @Override
    public Set<String> configurationKeys() {
        return Set.of(
                "local-temp-folder",
                "avro-file.max.seconds",
                "avro-file.max.bytes",
                "avro-file.sync.interval",
                "gcs.bucket-name",
                "gcs.listing.min-interval-seconds",
                "gcs.credential-provider",
                "gcs.service-account.key-file"
        );
    }

    @Override
    public GCSRawdataClient initialize(Map<String, String> configuration) {
        String bucket = configuration.get("gcs.bucket-name");
        Path localTempFolder = Path.of(configuration.get("local-temp-folder"));
        long avroMaxSeconds = Long.parseLong(configuration.get("avro-file.max.seconds"));
        long avroMaxBytes = Long.parseLong(configuration.get("avro-file.max.bytes"));
        int avroSyncInterval = Integer.parseInt(configuration.get("avro-file.sync.interval"));
        int gcsFileListingMaxIntervalSeconds = Integer.parseInt(configuration.get("gcs.listing.min-interval-seconds"));
        String credentialProvider = configuration.getOrDefault("gcs.credential-provider", "service-account");

        GoogleCredentials credentials;
        if ("service-account".equalsIgnoreCase(credentialProvider)) {
            try {
                Path serviceAccountKeyPath = Path.of(configuration.get("gcs.service-account.key-file"));
                credentials = ServiceAccountCredentials.fromStream(Files.newInputStream(serviceAccountKeyPath, StandardOpenOption.READ));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else if ("compute-engine".equalsIgnoreCase(credentialProvider)) {
            credentials = ComputeEngineCredentials.create();
        } else {
            throw new IllegalArgumentException("'gcs.credential-provider' must be one of 'service-account' or 'compute-engine'");
        }

        AvroRawdataUtils readOnlyGcsRawdataUtils = new GCSRawdataUtils(getReadOnlyStorage(credentials), bucket);
        Storage writableStorage = getWritableStorage(credentials);
        AvroRawdataUtils readWriteGcsRawdataUtils = new GCSRawdataUtils(writableStorage, bucket);
        return new GCSRawdataClient(localTempFolder, avroMaxSeconds, avroMaxBytes, avroSyncInterval, gcsFileListingMaxIntervalSeconds, readOnlyGcsRawdataUtils, readWriteGcsRawdataUtils, writableStorage, bucket);
    }

    static Storage getWritableStorage(GoogleCredentials sourceCredentials) {
        GoogleCredentials scopedCredentials = sourceCredentials.createScoped(Arrays.asList("https://www.googleapis.com/auth/devstorage.read_write"));
        Storage storage = StorageOptions.newBuilder().setCredentials(scopedCredentials).build().getService();
        return storage;
    }

    static Storage getReadOnlyStorage(GoogleCredentials sourceCredentials) {
        GoogleCredentials scopedCredentials = sourceCredentials.createScoped(Arrays.asList("https://www.googleapis.com/auth/devstorage.read_only"));
        Storage storage = StorageOptions.newBuilder().setCredentials(scopedCredentials).build().getService();
        return storage;
    }
}
