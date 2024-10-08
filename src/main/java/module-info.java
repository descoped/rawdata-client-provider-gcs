module io.descoped.rawdata.avro {
    requires io.descoped.rawdata.api;
    requires io.descoped.service.provider.api;
    requires org.slf4j;
    requires org.apache.avro;

    requires gax;
    requires google.cloud.storage;
    requires google.cloud.core;
    requires com.google.auth.oauth2;
    requires com.google.auth;

    provides io.descoped.rawdata.api.RawdataClientInitializer with
            io.descoped.rawdata.avro.cloudstorage.GCSRawdataClientInitializer,
            io.descoped.rawdata.avro.filesystem.FilesystemAvroRawdataClientInitializer;
}