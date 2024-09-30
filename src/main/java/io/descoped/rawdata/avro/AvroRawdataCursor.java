package io.descoped.rawdata.avro;

import de.huxhorn.sulky.ulid.ULID;
import io.descoped.rawdata.api.RawdataCursor;

class AvroRawdataCursor implements RawdataCursor {

    final ULID.Value ulid;
    final boolean inclusive;

    AvroRawdataCursor(ULID.Value ulid, boolean inclusive) {
        this.ulid = ulid;
        this.inclusive = inclusive;
    }
}
