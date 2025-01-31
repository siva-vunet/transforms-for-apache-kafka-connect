/*
 * Copyright 2019 Aiven Oy
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ExtractTopic<R extends ConnectRecord<R>> implements Transformation<R> {
    private static final Logger log = LoggerFactory.getLogger(ExtractTopic.class);

    private static final List<Schema.Type> SUPPORTED_TYPES_TO_CONVERT_FROM = Arrays.asList(
        Schema.Type.INT8,
        Schema.Type.INT16,
        Schema.Type.INT32,
        Schema.Type.INT64,
        Schema.Type.FLOAT32,
        Schema.Type.FLOAT64,
        Schema.Type.BOOLEAN,
        Schema.Type.STRING
    );

    private ExtractTopicConfig config;

    @Override
    public ConfigDef config() {
        return ExtractTopicConfig.config();
    }

    @Override
    public void configure(final Map<String, ?> settings) {
        this.config = new ExtractTopicConfig(settings);
    }

    @Override
    public R apply(final R record) {
        final SchemaAndValue schemaAndValue = getSchemaAndValue(record);

        final Optional<String> newTopic;
        if (config.fieldName().isPresent()) {
            newTopic = getNewTopicForNamedField(
                record.toString(), schemaAndValue.schema(), schemaAndValue.value(), config.fieldName().get());
        } else {
            newTopic = getNewTopicWithoutFieldName(
                record.toString(), schemaAndValue.schema(), schemaAndValue.value());
        }

        if (newTopic.isPresent()) {
            return record.newRecord(
                newTopic.get(),
                record.kafkaPartition(),
                record.keySchema(),
                record.key(),
                record.valueSchema(),
                record.value(),
                record.timestamp(),
                record.headers()
            );
        } else {
            return record;
        }
    }

    protected abstract String dataPlace();

    protected abstract SchemaAndValue getSchemaAndValue(final R record);

    private Optional<String> getNewTopicForNamedField(final String recordStr,
                                                      final Schema schema,
                                                      final Object value,
                                                      final String fieldName) {

        if (value == null) {
            throw new DataException(dataPlace() + " can't be null if field name is specified: " + recordStr);
        }
        final Optional<String> result;
        if (value instanceof Struct) {
            final Field field = schema.field(fieldName);
            if (field == null) {
                if (config.skipMissingOrNull()) {
                    return Optional.empty();
                } else {
                    throw new DataException(fieldName + " in " + dataPlace() + " schema can't be missing: "
                     + recordStr);
                }
            }
            if (!SUPPORTED_TYPES_TO_CONVERT_FROM.contains(field.schema().type())) {
                throw new DataException(fieldName + " schema type in " + dataPlace()
                    + " must be " + SUPPORTED_TYPES_TO_CONVERT_FROM
                    + ": " + recordStr);
            }
            final Struct struct = (Struct) value;
            result = Optional.ofNullable(struct.get(fieldName)).map(Object::toString);
        } else if (value instanceof Map) {
            final Map struct = new HashMap<>((Map<?, ?>) value);
            result = Optional.ofNullable(struct.get(fieldName)).map(Object::toString);

        } else {
            throw new DataException(dataPlace() + " schema type must be STRUCT or MAP if field name is specified: "
                + recordStr);
        }
        if (result.isPresent() && !result.get().equals("")) {
            return result;
        } else {
            if (config.skipMissingOrNull()) {
                return Optional.empty();
            } else {
                throw new DataException(fieldName + " in " + dataPlace() + " can't be null or empty: " + recordStr);
            }
        }
    }

    private Optional<String> getNewTopicWithoutFieldName(final String recordStr,
                                                         final Schema schema,
                                                         final Object value) {
        if (!SUPPORTED_TYPES_TO_CONVERT_FROM.contains(schema.type())) {
            throw new DataException(dataPlace() + " schema type must be "
                + SUPPORTED_TYPES_TO_CONVERT_FROM
                + " if field name is not specified: "
                + recordStr);
        }

        if (value == null || "".equals(value)) {
            if (config.skipMissingOrNull()) {
                return Optional.empty();
            } else {
                throw new DataException(dataPlace() + " can't be null or empty: " + recordStr);
            }
        }

        return Optional.of(value.toString());
    }

    public static class Key<R extends ConnectRecord<R>> extends ExtractTopic<R> {
        @Override
        protected SchemaAndValue getSchemaAndValue(final R record) {
            return new SchemaAndValue(record.keySchema(), record.key());
        }

        @Override
        protected String dataPlace() {
            return "key";
        }
    }

    public static class Value<R extends ConnectRecord<R>> extends ExtractTopic<R> {
        @Override
        protected SchemaAndValue getSchemaAndValue(final R record) {
            return new SchemaAndValue(record.valueSchema(), record.value());
        }

        @Override
        protected String dataPlace() {
            return "value";
        }
    }

    @Override
    public void close() {
    }
}
