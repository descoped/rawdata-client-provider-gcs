package io.descoped.rawdata.avro;

import java.util.NavigableMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

class TopicAvroFileCache {

    final AvroRawdataUtils gcsRawdataUtils;
    final String topic;
    final int minListingIntervalSeconds;

    final AtomicReference<NavigableMap<Long, RawdataAvroFile>> topicBlobsByFromTimestampRef = new AtomicReference<>();
    final AtomicLong timestampOfLastListing = new AtomicLong(0);

    TopicAvroFileCache(AvroRawdataUtils gcsRawdataUtils, String topic, int minListingIntervalSeconds) {
        this.gcsRawdataUtils = gcsRawdataUtils;
        this.topic = topic;
        this.minListingIntervalSeconds = minListingIntervalSeconds;
    }

    NavigableMap<Long, RawdataAvroFile> blobsByTimestamp() {
        if ((System.currentTimeMillis() - timestampOfLastListing.get()) >= TimeUnit.SECONDS.toMillis(minListingIntervalSeconds)) {
            // refresh entire cache by listing all files from GCS
            topicBlobsByFromTimestampRef.set(gcsRawdataUtils.getTopicBlobs(topic));
            timestampOfLastListing.set(System.currentTimeMillis());
        }
        return topicBlobsByFromTimestampRef.get();
    }

    void clear() {
        topicBlobsByFromTimestampRef.set(null);
        timestampOfLastListing.set(0);
    }
}
