package io.descoped.rawdata.avro.cloudstorage;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import io.descoped.rawdata.avro.AvroFileMetadata;
import io.descoped.rawdata.avro.AvroRawdataUtils;
import io.descoped.rawdata.avro.RawdataAvroFile;

import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

class GCSRawdataUtils implements AvroRawdataUtils {

    final Storage storage;
    final String bucket;

    GCSRawdataUtils(Storage storage, String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    static final Pattern topicAndFilenamePattern = Pattern.compile("(?<topic>.+)/(?<filename>[^/]+)");

    static final Pattern topicAndMetadataFilenamePattern = Pattern.compile("(?<topic>.+)/metadata/(?<filename>[^/]+)");

    static Matcher topicMatcherOf(BlobId blobId) {
        Matcher topicAndFilenameMatcher = topicAndFilenamePattern.matcher(blobId.getName());
        if (!topicAndFilenameMatcher.matches()) {
            throw new RuntimeException("GCS BlobId does not match topicAndFilenamePattern. blobId=" + blobId.getName());
        }
        return topicAndFilenameMatcher;
    }

    static String topic(BlobId blobId) {
        Matcher topicAndFilenameMatcher = topicMatcherOf(blobId);
        String topic = topicAndFilenameMatcher.group("topic");
        return topic;
    }

    static String filename(BlobId blobId) {
        Matcher topicAndFilenameMatcher = topicMatcherOf(blobId);
        String filename = topicAndFilenameMatcher.group("filename");
        return filename;
    }

    static final Pattern filenamePattern = Pattern.compile("(?<from>[^_]+)_(?<count>[0123456789]+)_(?<lastBlockOffset>[0123456789]+)_(?<position>.+)\\.avro");

    static Matcher filenameMatcherOf(BlobId blobId) {
        String filename = filename(blobId);
        Matcher filenameMatcher = filenamePattern.matcher(filename);
        if (!filenameMatcher.matches()) {
            throw new RuntimeException("GCS filename does not match filenamePattern. filename=" + filename);
        }
        return filenameMatcher;
    }

    /**
     * @return lower-bound (inclusive) timestamp of this file range
     */
    long getFromTimestamp(BlobId blobId) {
        Matcher filenameMatcher = filenameMatcherOf(blobId);
        String from = filenameMatcher.group("from");
        return AvroRawdataUtils.parseTimestamp(from);
    }

    /**
     * @return lower-bound (inclusive) position of this file range
     */
    String getFirstPosition(BlobId blobId) {
        Matcher filenameMatcher = filenameMatcherOf(blobId);
        String position = filenameMatcher.group("position");
        return position;
    }

    /**
     * @return count of messages in the file
     */
    long getMessageCount(BlobId blobId) {
        Matcher filenameMatcher = filenameMatcherOf(blobId);
        long count = Long.parseLong(filenameMatcher.group("count"));
        return count;
    }

    /**
     * @return count of messages in the file
     */
    static long getOffsetOfLastBlock(BlobId blobId) {
        Matcher filenameMatcher = filenameMatcherOf(blobId);
        long offset = Long.parseLong(filenameMatcher.group("lastBlockOffset"));
        return offset;
    }

    Stream<Blob> listTopicFiles(String bucketName, String topic) {
        Page<Blob> page = storage.list(bucketName, Storage.BlobListOption.prefix(topic + "/"));
        Stream<Blob> stream = StreamSupport.stream(page.iterateAll().spliterator(), false);
        return stream.filter(blob -> !blob.isDirectory() && blob.getSize() > 0)
                .filter(blob -> !topicAndMetadataFilenamePattern.matcher(blob.getName()).matches());
    }

    @Override
    public NavigableMap<Long, RawdataAvroFile> getTopicBlobs(String topic) {
        NavigableMap<Long, RawdataAvroFile> map = new TreeMap<>();
        listTopicFiles(bucket, topic).forEach(blob -> {
            long fromTimestamp = getFromTimestamp(blob.getBlobId());
            map.put(fromTimestamp, new GCSRawdataAvroFile(storage, blob));
        });
        return map;
    }

    @Override
    public AvroFileMetadata newAvrofileMetadata() {
        return new GCSAvroFileMetadata(storage, bucket);
    }
}
