package io.descoped.rawdata.avro.cloudstorage;

import com.google.cloud.storage.Storage;
import io.descoped.rawdata.api.RawdataMetadataClient;
import io.descoped.rawdata.avro.AvroRawdataClient;
import io.descoped.rawdata.avro.AvroRawdataUtils;

import java.nio.file.Path;

public class GCSRawdataClient extends AvroRawdataClient {

    final Storage storage;
    final String bucketName;

    public GCSRawdataClient(Path tmpFileFolder, long avroMaxSeconds, long avroMaxBytes, int avroSyncInterval, int fileListingMinIntervalSeconds, AvroRawdataUtils readOnlyAvroRawdataUtils, AvroRawdataUtils readWriteAvroRawdataUtils, Storage storage, String bucketName) {
        super(tmpFileFolder, avroMaxSeconds, avroMaxBytes, avroSyncInterval, fileListingMinIntervalSeconds, readOnlyAvroRawdataUtils, readWriteAvroRawdataUtils);
        this.storage = storage;
        this.bucketName = bucketName;
    }

    @Override
    public RawdataMetadataClient metadata(String topic) {
        return new GCSRawdataMetadataClient(storage, bucketName, topic);
    }
}
