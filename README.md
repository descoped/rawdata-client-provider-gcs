# rawdata-client-provider-gcs

![Build Status](https://img.shields.io/github/actions/workflow/status/descoped/rawdata-client-provider-gcs/coverage-and-sonar-analysis.yml)
![Latest Tag](https://img.shields.io/github/v/tag/descoped/rawdata-client-provider-gcs)
![Renovate](https://img.shields.io/badge/renovate-enabled-brightgreen.svg)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=descoped_rawdata-client-provider-gcs&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=descoped_rawdata-client-provider-gcs) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=descoped_rawdata-client-provider-gcs&metric=coverage)](https://sonarcloud.io/summary/new_code?id=descoped_rawdata-client-provider-gcs)
[![Snyk Security Score](https://snyk.io/test/github/descoped/rawdata-client-provider-gcs/badge.svg)](https://snyk.io/test/github/descoped/rawdata-client-provider-gcs)

Rawdata provider for Google Cloud Storage.

Rawdata topics are organized such that each topic has a separate folder 
of Avro files in GCS. All files in the topic folder are part of the stream, 
and each file is named using the following pattern: <br/>
`/<topic-name>/<timestamp>_<count>_<last-block-offset>_<position>.avro` <br/>
where:
- `<topic-name>` is the name of the topic (or stream)
- `<timestamp>` is the timestamp of the first message in the file
- `<count>` is the number of messages in the file
- `<last-block-offset>` The position of the start of the last block in the file.
RawdataClient.lastMessage() uses this to efficiently seek to the last block.
- `<position>` is the value of the first position in the file

Producers will keep a local Avro file to buffer all published 
messages. When producer is closed, or a time or size limit is 
reached, the local Avro file is uploaded to GCS into the configured
bucket and appropriate topic folder. After uploading a file, producers
will truncate and re-use the local-file to continue buffering (unless
the producer was closed). Files on GCS are named so that the file name 
contains the timestamp of the first and last message in the file.

Consumers list all files relevant to a topic on-demand and will wait 
at least a configured amount of seconds between list operations. 
Consumers know which file(s) to read based on the file-names and the
requested read operations. Consumers are able to detect new files 
created on GCS while tailing the stream.

## Configuration Options
| Configuration Key | Example | Required | Description |
| ----------------- |:-------:|:--------:| ----------- |
| local-temp-folder |temp |  yes | Path to local folder where topic folders and buffer-files can be created |
| avro-file.max.seconds | 3600 | yes | Max number of seconds in a producer window |
| avro-file.max.bytes | 10485760 | yes | Max number of bytes in a producer window |
| avro-file.sync.interval | 524288 | yes | Block sync threshold in bytes. Will start a new Avro block after message that breaks this threshold is written |
| gcs.bucket-name | test-bucket | yes | Name of bucket |
| gcs.listing.min-interval-seconds | 60 | yes | Minimum number-of seconds between GCS list operations |
| gcs.service-account.key-file | secret/my_gcs_sa.json | yes | Path to json service-account key file |
| listing.min-interval-seconds | 0 | yes | Minimum number-of seconds between filesystem list operations |
| filesystem.storage-folder | rawdata/storage | yes | Path to rawdata storage folder |

## Example usage of gcs provider
```java
    Map<String, String> configuration = Map.of(
            "local-temp-folder", "temp",
            "avro-file.max.seconds", "3600",
            "avro-file.max.bytes", "10485760",
            "avro-file.sync.interval", "524288",
            "gcs.bucket-name", "my-awesome-test-bucket",
            "gcs.listing.min-interval-seconds", "3",
            "gcs.service-account.key-file", "secret/my_gcs_sa.json"
    );

    RawdataClient client = ProviderConfigurator.configure(configuration,
            "gcs", RawdataClientInitializer.class);
```

## Example usage of filesystem provider
```java
    Map<String, String> configuration = Map.of(
            "local-temp-folder", "temp",
            "avro-file.max.seconds", "3600",
            "avro-file.max.bytes", "10485760",
            "avro-file.sync.interval", "524288",
            "listing.min-interval-seconds", "0",
            "filesystem.storage-folder", "rawdata/storage"
    );

    RawdataClient client = ProviderConfigurator.configure(configuration,
            "filesystem", RawdataClientInitializer.class);
```

For the full example, see the ExampleApp.java file in the src/test/java
