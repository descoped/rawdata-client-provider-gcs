package io.descoped.rawdata.avro.cloudstorage;

import io.descoped.rawdata.api.RawdataClient;
import io.descoped.rawdata.api.RawdataClientInitializer;
import io.descoped.rawdata.api.RawdataConsumer;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataProducer;
import io.descoped.service.provider.api.ProviderConfigurator;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ExampleApp {

    public static void main(String[] args) throws Exception {

        Map<String, String> configuration = Map.of(
                "local-temp-folder", "temp",
                "avro-file.max.seconds", "3600",
                "avro-file.max.bytes", "10485760",
                "gcs.bucket-name", "my-awesome-test-bucket",
                "gcs.listing.min-interval-seconds", "3",
                "gcs.service-account.key-file", "secret/my_gcs_sa.json"
        );

        final RawdataClient client = ProviderConfigurator.configure(configuration,
                "gcs", RawdataClientInitializer.class);

        Thread consumerThread = new Thread(() -> consumeMessages(client));
        consumerThread.start();

        produceMessages(client);

        consumerThread.join();
    }

    static void consumeMessages(RawdataClient client) {
        try (RawdataConsumer consumer = client.consumer("my-rawdata-stream")) {
            for (; ; ) {
                RawdataMessage message = consumer.receive(30, TimeUnit.SECONDS);
                if (message != null) {
                    System.out.printf("Consumed message with id: %s%n", message.ulid());
                    if (message.position().equals("582AACB30")) {
                        return;
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void produceMessages(RawdataClient client) throws Exception {
        try (RawdataProducer producer = client.producer("my-rawdata-stream")) {
            producer.publish(RawdataMessage.builder().position("4BA210EC2")
                    .put("the-payload", "Hello 1".getBytes(StandardCharsets.UTF_8))
                    .build());
            producer.publish(RawdataMessage.builder().position("B827B4CCE")
                    .put("the-payload", "Hello 2".getBytes(StandardCharsets.UTF_8))
                    .put("metadata", ("created-time " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8))
                    .build());
            producer.publish(RawdataMessage.builder().position("582AACB30")
                    .put("the-payload", "Hello 3".getBytes(StandardCharsets.UTF_8))
                    .build());
        }
    }
}
