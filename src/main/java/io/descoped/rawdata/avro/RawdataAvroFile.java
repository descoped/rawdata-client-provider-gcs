package io.descoped.rawdata.avro;

import org.apache.avro.file.SeekableInput;

import java.nio.file.Path;

public interface RawdataAvroFile {

    SeekableInput seekableInput();

    long getOffsetOfLastBlock();

    void copyFrom(Path source);
}
