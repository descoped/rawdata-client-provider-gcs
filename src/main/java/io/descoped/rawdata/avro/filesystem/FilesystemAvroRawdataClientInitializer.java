package io.descoped.rawdata.avro.filesystem;

import io.descoped.rawdata.api.RawdataClientInitializer;
import io.descoped.rawdata.avro.AvroRawdataUtils;
import io.descoped.service.provider.api.ProviderName;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

@ProviderName("filesystem")
public class FilesystemAvroRawdataClientInitializer implements RawdataClientInitializer {

    @Override
    public String providerId() {
        return "filesystem";
    }

    @Override
    public Set<String> configurationKeys() {
        return Set.of(
                "local-temp-folder",
                "avro-file.max.seconds",
                "avro-file.max.bytes",
                "avro-file.sync.interval",
                "listing.min-interval-seconds",
                "filesystem.storage-folder"
        );
    }

    @Override
    public FilesystemRawdataClient initialize(Map<String, String> configuration) {
        Path localTempFolder = Path.of(configuration.get("local-temp-folder"));
        long avroMaxSeconds = Long.parseLong(configuration.get("avro-file.max.seconds"));
        long avroMaxBytes = Long.parseLong(configuration.get("avro-file.max.bytes"));
        int avroSyncInterval = Integer.parseInt(configuration.get("avro-file.sync.interval"));
        int minListingIntervalSeconds = Integer.parseInt(configuration.get("listing.min-interval-seconds"));
        Path storageFolder = Path.of(configuration.get("filesystem.storage-folder"));
        AvroRawdataUtils readOnlyFilesystemRawdataUtils = new FilesystemRawdataUtils(storageFolder);
        AvroRawdataUtils readWriteFilesystemRawdataUtils = new FilesystemRawdataUtils(storageFolder);
        return new FilesystemRawdataClient(localTempFolder, avroMaxSeconds, avroMaxBytes, avroSyncInterval, minListingIntervalSeconds, readOnlyFilesystemRawdataUtils, readWriteFilesystemRawdataUtils, storageFolder);
    }
}
