package io.descoped.rawdata.avro.filesystem;

import io.descoped.rawdata.avro.AvroFileMetadata;
import io.descoped.rawdata.avro.AvroRawdataUtils;
import io.descoped.rawdata.avro.RawdataAvroFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class FilesystemRawdataUtils implements AvroRawdataUtils {

    final Path storageFolder;

    FilesystemRawdataUtils(Path storageFolder) {
        this.storageFolder = storageFolder;
    }

    static String topic(Path path) {
        return path.getParent().getFileName().toString();
    }

    static String filename(Path path) {
        return path.getFileName().toString();
    }

    static final Pattern filenamePattern = Pattern.compile("(?<from>[^_]+)_(?<count>[0123456789]+)_(?<lastBlockOffset>[0123456789]+)_(?<position>.+)\\.avro");

    static Matcher filenameMatcherOf(Path path) {
        String filename = filename(path);
        Matcher filenameMatcher = filenamePattern.matcher(filename);
        if (!filenameMatcher.matches()) {
            throw new RuntimeException("GCS filename does not match filenamePattern. filename=" + filename);
        }
        return filenameMatcher;
    }

    /**
     * @return lower-bound (inclusive) timestamp of this file range
     */
    long getFromTimestamp(Path path) {
        Matcher filenameMatcher = filenameMatcherOf(path);
        String from = filenameMatcher.group("from");
        return AvroRawdataUtils.parseTimestamp(from);
    }

    /**
     * @return lower-bound (inclusive) position of this file range
     */
    String getFirstPosition(Path path) {
        Matcher filenameMatcher = filenameMatcherOf(path);
        String position = filenameMatcher.group("position");
        return position;
    }

    /**
     * @return count of messages in the file
     */
    long getMessageCount(Path path) {
        Matcher filenameMatcher = filenameMatcherOf(path);
        long count = Long.parseLong(filenameMatcher.group("count"));
        return count;
    }

    /**
     * @return count of messages in the file
     */
    static long getOffsetOfLastBlock(Path path) {
        Matcher filenameMatcher = filenameMatcherOf(path);
        long offset = Long.parseLong(filenameMatcher.group("lastBlockOffset"));
        return offset;
    }

    @Override
    public NavigableMap<Long, RawdataAvroFile> getTopicBlobs(String topic) {
        try {
            NavigableMap<Long, RawdataAvroFile> map = new TreeMap<>();
            Path topicFolder = storageFolder.resolve(topic);
            if (!topicFolder.toFile().isDirectory()) {
                return map;
            }
            Files.list(topicFolder).filter(path -> path.toFile().isFile() && path.toFile().length() > 0).forEach(path -> {
                long fromTimestamp = getFromTimestamp(path);
                map.put(fromTimestamp, new FilesystemRawdataAvroFile(path));
            });
            return map;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public AvroFileMetadata newAvrofileMetadata() {
        return new FilesystemAvroFileMetadata(storageFolder);
    }
}
