package io.descoped.rawdata.avro.filesystem;

import io.descoped.rawdata.api.RawdataMetadataClient;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class FilesystemRawdataMetadataClient implements RawdataMetadataClient {

    final Path metadataFolder;
    final String topic;

    public FilesystemRawdataMetadataClient(Path metadataFolder, String topic) {
        this.metadataFolder = metadataFolder;
        this.topic = topic;
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public Set<String> keys() {
        try {
            return Files.list(metadataFolder)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .map(this::unescapeFilename)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    String escapeFilename(String filename) {
        String escaped = filename;
        if (filename.startsWith(".")) {
            escaped = filename.replaceAll("[.]", "...");
        }
        escaped = URLEncoder.encode(escaped, StandardCharsets.UTF_8);
        return escaped;
    }

    String unescapeFilename(String filename) {
        String unescaped = URLDecoder.decode(filename, StandardCharsets.UTF_8);
        if (unescaped.startsWith("...")) {
            unescaped = unescaped.replaceAll("[.][.][.]", ".");
        }
        return unescaped;
    }

    @Override
    public byte[] get(String key) {
        Path path = metadataFolder.resolve(Path.of(escapeFilename(key)));
        if (!Files.exists(path)) {
            return null;
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Path to metadata-file already exists, but is not a regular file, path: " + path.toString());
        }
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("Not allowed to read metadata-file at path: " + path.toString());
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public RawdataMetadataClient put(String key, byte[] value) {
        Path path = metadataFolder.resolve(Path.of(escapeFilename(key)));
        try {
            Files.write(path, value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    @Override
    public RawdataMetadataClient remove(String key) {
        Path path = metadataFolder.resolve(Path.of(escapeFilename(key)));
        try {
            Files.deleteIfExists(path);
            return this;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
