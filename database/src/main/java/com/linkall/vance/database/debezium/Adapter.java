package com.linkall.vance.database.debezium;

import io.cloudevents.CloudEvent;
import org.apache.kafka.connect.source.SourceRecord;

public interface Adapter {
    CloudEvent adapt(SourceRecord record);
}
