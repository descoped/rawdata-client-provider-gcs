package io.descoped.rawdata.avro.cloudstorage;

import com.google.cloud.storage.BlobId;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GCSRawdataUtilsTest {

    @Test
    public void testFilename() {
        String filename = GCSRawdataUtils.filename(BlobId.of("any", "abc/123/something/20210421054707/20210421054707/2021-04-21T05:47:10.694Z_100_343_1.avro"));
        Assert.assertEquals(filename, "2021-04-21T05:47:10.694Z_100_343_1.avro");
    }
}