package io.descoped.rawdata.avro.filesystem;

import io.descoped.rawdata.avro.AvroFileMetadata;
import io.descoped.rawdata.avro.RawdataAvroFile;

import java.nio.file.Path;

class FilesystemAvroFileMetadata extends AvroFileMetadata {

    final Path storageFolder;

    FilesystemAvroFileMetadata(Path storageFolder) {
        this.storageFolder = storageFolder;
    }

    @Override
    public RawdataAvroFile toRawdataAvroFile(String topic) {
        return new FilesystemRawdataAvroFile(storageFolder.resolve(topic).resolve(toFilename()));
    }
}
