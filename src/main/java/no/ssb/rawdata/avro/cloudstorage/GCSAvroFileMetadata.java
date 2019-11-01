package no.ssb.rawdata.avro.cloudstorage;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import no.ssb.rawdata.avro.AvroFileMetadata;
import no.ssb.rawdata.avro.RawdataAvroFile;

class GCSAvroFileMetadata extends AvroFileMetadata {

    final Storage storage;
    final String bucket;

    GCSAvroFileMetadata(Storage storage, String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    @Override
    public RawdataAvroFile toRawdataAvroFile(String topic) {
        return new GCSRawdataAvroFile(storage, BlobId.of(bucket, topic + "/" + toFilename()));
    }
}
