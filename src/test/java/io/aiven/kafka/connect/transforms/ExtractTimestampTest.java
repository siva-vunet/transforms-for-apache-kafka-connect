/*
 * Copyright 2020 Aiven Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.kafka.connect.transforms;

import  com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Timestamp;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

abstract class ExtractTimestampTest {
    private static final String FIELD = "test_field";

    @Test
    void recordNotStructOrMap() {
        final SinkRecord originalRecord = record(SchemaBuilder.INT8_SCHEMA, (byte) 123);
        final Throwable e = assertThrows(DataException.class, () -> transformation().apply(originalRecord));
        assertEquals(keyOrValue() + " type must be STRUCT or MAP: " + originalRecord,
                e.getMessage());
    }

    @Test
    void recordStructNull() {
        final Schema schema = SchemaBuilder.struct().schema();
        final SinkRecord originalRecord = record(schema, null);
        final Throwable e = assertThrows(DataException.class, () -> transformation().apply(originalRecord));
        assertEquals(keyOrValue() + " can't be null: " + originalRecord,
                e.getMessage());
    }

    @Test
    void recordMapNull() {
        final SinkRecord originalRecord = record(null, null);
        final Throwable e = assertThrows(DataException.class, () -> transformation().apply(originalRecord));
        assertEquals(keyOrValue() + " can't be null: " + originalRecord,
                e.getMessage());
    }

    @Test
    void structWithMissingField() {
        final Schema schema = SchemaBuilder.struct()
                .field(FIELD, Schema.INT64_SCHEMA)
                .build();
        final SinkRecord originalRecord = record(null, new Struct(schema));
        final Throwable e = assertThrows(DataException.class, () -> transformation().apply(originalRecord));
        assertEquals(FIELD + " field must be present and its value can't be null: " + originalRecord,
                e.getMessage());
    }

    @Test
    void mapWithMissingField() {
        final SinkRecord originalRecord = record(null, new HashMap<>());
        final Throwable e = assertThrows(DataException.class, () -> transformation().apply(originalRecord));
        assertEquals(FIELD + " field must be present and its value can't be null: " + originalRecord,
                e.getMessage());
    }

    @Test
    void structWithNullField() {
        final Schema schema = SchemaBuilder.struct()
                .field(FIELD, Schema.OPTIONAL_INT64_SCHEMA)
                .build();
        final SinkRecord originalRecord = record(null, new Struct(schema).put(FIELD, null));
        final Throwable e = assertThrows(DataException.class, () -> transformation().apply(originalRecord));
        assertEquals(FIELD + " field must be present and its value can't be null: " + originalRecord,
                e.getMessage());
    }

    @Test
    void mapWithNullField() {
        final HashMap<Object, Object> valueMap = new HashMap<>();
        valueMap.put(FIELD, null);
        final SinkRecord originalRecord = record(null, valueMap);
        final Throwable e = assertThrows(DataException.class, () -> transformation().apply(originalRecord));
        assertEquals(FIELD + " field must be present and its value can't be null: " + originalRecord,
                e.getMessage());
    }

    @Test
    void structWithFieldOfIncorrectType() {
        final Schema schema = SchemaBuilder.struct()
                .field(FIELD, Schema.STRING_SCHEMA)
                .build();
        final SinkRecord originalRecord = record(null, new Struct(schema).put(FIELD, "aaa"));
        final Throwable e = assertThrows(DataException.class, () -> transformation().apply(originalRecord));
        assertEquals(FIELD + " field must be INT64 or org.apache.kafka.connect.data.Timestamp: "
                        + originalRecord,
                e.getMessage());
    }

    @Test
    void mapWithFieldOfIncorrectType() {
        final HashMap<Object, Object> valueMap = new HashMap<>();
        valueMap.put(FIELD, "aaa");
        final SinkRecord originalRecord = record(null, valueMap);
        final Throwable e = assertThrows(DataException.class, () -> transformation().apply(originalRecord));
        assertEquals(FIELD + " field must be INT64 or org.apache.kafka.connect.data.Timestamp: "
                        + originalRecord,
                e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void structWithOptionalIntField(final boolean optional) {
        final Schema schema;
        if (optional) {
            schema = SchemaBuilder.struct()
                    .field(FIELD, Schema.OPTIONAL_INT64_SCHEMA)
                    .build();
        } else {
            schema = SchemaBuilder.struct()
                    .field(FIELD, Schema.INT64_SCHEMA)
                    .build();
        }
        final long timestamp = 11363151277L;
        final SinkRecord originalRecord = record(null, new Struct(schema).put(FIELD, timestamp));
        final SinkRecord transformedRecord = transformation().apply(originalRecord);
        assertEquals(setNewTimestamp(originalRecord, timestamp), transformedRecord);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void structWithOptIntField(final boolean optional) {
        final Schema schema;
        if (optional) {
            schema = SchemaBuilder.struct()
                    .field(FIELD, Schema.OPTIONAL_INT64_SCHEMA)
                    .build();
        } else {
            schema = SchemaBuilder.struct()
                    .field(FIELD, Schema.INT64_SCHEMA)
                    .build();
        }
        final long timestamp = 11363151277L;
        final SinkRecord originalRecord = record(null, new Struct(schema).put(FIELD, timestamp));
        final SinkRecord transformedRecord = transformation().apply(originalRecord);
        assertEquals(setNewTimestamp(originalRecord, timestamp), transformedRecord);
    }

    @ParameterizedTest
    @EnumSource(value = ExtractTimestampConfig.TimestampResolution.class, names = {"MILLISECONDS", "SECONDS"})
    void structWithIntField(final ExtractTimestampConfig.TimestampResolution tsResolution) {
        final Schema schema = SchemaBuilder.struct().field(FIELD, Schema.INT64_SCHEMA).build();
        final ZonedDateTime datetime = ZonedDateTime.of(
                2020, 11, 15, 1, 2, 3, 4,
                ZoneId.of("UTC")
        );
        final Instant instance = datetime.toInstant();
        final long timestamp;
        if (tsResolution == ExtractTimestampConfig.TimestampResolution.SECONDS) {
            timestamp = instance.getEpochSecond();
        } else {
            timestamp = instance.toEpochMilli();
        }
        final Map props = new HashMap<String, String>();
        props.put(ExtractTimestampConfig.EPOCH_RESOLUTION_CONFIG, tsResolution.resolution());
        final SinkRecord originalRecord = record(null, new Struct(schema).put(FIELD, timestamp));
        final ConnectRecord transformedRecord = transformation(props).apply(originalRecord);
        assertEquals(setNewTimestamp(originalRecord, instance.toEpochMilli()), transformedRecord);
    }

    @ParameterizedTest
    @EnumSource(value = ExtractTimestampConfig.TimestampResolution.class, names = {"MILLISECONDS", "SECONDS"})
    void mapWithIntField(final ExtractTimestampConfig.TimestampResolution tsResolution) {
        final ZonedDateTime datetime = ZonedDateTime.of(
                2020, 11, 15, 1, 2, 3, 4,
                ZoneId.of("UTC")
        );
        final Instant instance = datetime.toInstant();
        final Map props = new HashMap<String, String>();
        props.put(ExtractTimestampConfig.EPOCH_RESOLUTION_CONFIG, tsResolution.resolution());
        final long timestamp;
        if (tsResolution == ExtractTimestampConfig.TimestampResolution.SECONDS) {
            timestamp = instance.getEpochSecond();
        } else {
            timestamp = instance.toEpochMilli();
        }
        final SinkRecord originalRecord = record(null, ImmutableMap.of(FIELD, timestamp));
        final ConnectRecord transformedRecord = transformation(props).apply(originalRecord);
        assertEquals(setNewTimestamp(originalRecord, instance.toEpochMilli()), transformedRecord);
    }

    @ParameterizedTest
    @EnumSource(value = ExtractTimestampConfig.TimestampResolution.class, names = {"MILLISECONDS", "SECONDS"})
    void structWithTimestampField(final ExtractTimestampConfig.TimestampResolution tsResolution) {
        final Schema schema = SchemaBuilder.struct()
                .field(FIELD, Timestamp.SCHEMA)
                .build();
        final ZonedDateTime datetime = ZonedDateTime.of(
                2020, 11, 15, 1, 2, 3, 4,
                ZoneId.of("UTC")
        );
        final Instant instant = datetime.toInstant();
        final Map props = new HashMap<String, String>();
        props.put(ExtractTimestampConfig.EPOCH_RESOLUTION_CONFIG, tsResolution.resolution());
        final SinkRecord originalRecord = record(null, new Struct(schema).put(FIELD, Date.from(instant)));
        final ConnectRecord transformedRecord = transformation(props).apply(originalRecord);
        assertEquals(setNewTimestamp(originalRecord, instant.toEpochMilli()), transformedRecord);
    }

    @ParameterizedTest
    @EnumSource(value = ExtractTimestampConfig.TimestampResolution.class, names = {"MILLISECONDS", "SECONDS"})
    void mapWithTimestampField(final ExtractTimestampConfig.TimestampResolution tsResolution) {
        final ZonedDateTime datetime = ZonedDateTime.of(
                2020, 11, 15, 1, 2, 3, 4,
                ZoneId.of("UTC")
        );
        final Instant instant = datetime.toInstant();
        final Map props = new HashMap<String, String>();
        props.put(ExtractTimestampConfig.EPOCH_RESOLUTION_CONFIG, tsResolution.resolution());
        final SinkRecord originalRecord = record(null, ImmutableMap.of(FIELD, Date.from(instant)));
        final ConnectRecord transformedRecord = transformation(props).apply(originalRecord);
        assertEquals(setNewTimestamp(originalRecord, instant.toEpochMilli()), transformedRecord);
    }

    private ExtractTimestamp<SinkRecord> transformation() {
        return transformation(new HashMap<>());
    }

    private ExtractTimestamp<SinkRecord> transformation(final Map<String, String> props) {
        props.put("field.name", FIELD);
        final ExtractTimestamp<SinkRecord> transform = createTransformationObject();
        transform.configure(props);
        return transform;
    }

    protected abstract SinkRecord record(final Schema schema, final Object data);

    protected SinkRecord record(final Schema keySchema,
                                final Object key,
                                final Schema valueSchema,
                                final Object value) {
        return new SinkRecord("original_topic", 0,
                keySchema, key,
                valueSchema, value,
                123L,
                456L, TimestampType.CREATE_TIME);
    }

    private SinkRecord setNewTimestamp(final SinkRecord record, final long newTimestamp) {
        return record.newRecord(record.topic(),
                record.kafkaPartition(),
                record.keySchema(),
                record.key(),
                record.valueSchema(),
                record.value(),
                newTimestamp,
                record.headers()
        );
    }

    protected abstract String keyOrValue();

    protected abstract ExtractTimestamp<SinkRecord> createTransformationObject();
}
