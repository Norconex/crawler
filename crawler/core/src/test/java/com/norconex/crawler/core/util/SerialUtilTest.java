/* Copyright 2025-2026 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.crawler.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.commons.lang3.SerializationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.ProcessingStatus;

@Timeout(30)
class SerialUtilTest {

    @Test
    void toJsonString_simpleObject_producesJson() {
        var entry = new CrawlerEntry("http://example.com");
        var json = SerialUtil.toJsonString(entry);
        assertThat(json).contains("http://example.com");
    }

    @Test
    void fromJson_validJson_reconstructsObject() {
        var original = new CrawlerEntry("http://test.com");
        original.setProcessingStatus(ProcessingStatus.QUEUED);
        var json = SerialUtil.toJsonString(original);

        var restored = SerialUtil.fromJson(json, CrawlerEntry.class);

        assertThat(restored).isNotNull();
        assertThat(restored.getReference()).isEqualTo("http://test.com");
        assertThat(restored.getProcessingStatus())
                .isEqualTo(ProcessingStatus.QUEUED);
    }

    @Test
    void fromJson_nullString_returnsNull() {
        assertThat(SerialUtil.fromJson((String) null, CrawlerEntry.class))
                .isNull();
    }

    @Test
    void fromJson_emptyString_throwsException() {
        // Empty string is not valid JSON → expect a SerializationException
        assertThatThrownBy(
                () -> SerialUtil.fromJson("", CrawlerEntry.class))
                        .isInstanceOf(SerializationException.class);
    }

    @Test
    void toJsonString_simple_producesNonBlankJson() {
        // toJsonString has @NonNull, just verify basic contract
        var entry = new CrawlerEntry("ref://item2");
        assertThat(SerialUtil.toJsonString(entry)).isNotBlank();
    }

    @Test
    void roundTrip_stringValue() {
        // Verify a simple object with a date field round-trips cleanly
        var entry = new CrawlerEntry("ref://item");
        entry.setDepth(5);
        var json = SerialUtil.toJsonString(entry);
        var restored = SerialUtil.fromJson(json, CrawlerEntry.class);
        assertThat(restored.getDepth()).isEqualTo(5);
    }

    @Test
    void fromJson_invalidJson_throwsException() {
        assertThatThrownBy(
                () -> SerialUtil.fromJson("{invalid}", CrawlerEntry.class))
                        .isInstanceOf(SerializationException.class);
    }

    @Test
    void jsonFactory_returnsFactory() {
        assertThat(SerialUtil.jsonFactory()).isNotNull();
    }

    @Test
    void getMapper_returnsNonNullObjectMapper() {
        assertThat(SerialUtil.getMapper()).isNotNull();
    }

    @Test
    void toJsonReader_returnsReadableReader() throws Exception {
        var entry = new CrawlerEntry("ref://reader-test");
        try (var reader = SerialUtil.toJsonReader(entry)) {
            assertThat(reader).isNotNull();
            char[] buf = new char[256];
            int read = reader.read(buf);
            assertThat(read).isGreaterThan(0);
            assertThat(new String(buf, 0, read)).contains("reader-test");
        }
    }

    @Test
    void fromJson_reader_reconstructsObject() {
        var original = new CrawlerEntry("ref://from-reader");
        original.setProcessingStatus(ProcessingStatus.PROCESSING);
        var json = SerialUtil.toJsonString(original);
        var reader = new java.io.StringReader(json);
        var restored = SerialUtil.fromJson(reader, CrawlerEntry.class);
        assertThat(restored.getReference()).isEqualTo("ref://from-reader");
        assertThat(restored.getProcessingStatus())
                .isEqualTo(ProcessingStatus.PROCESSING);
    }

    @Test
    void jsonGenerator_createsGenerator() throws Exception {
        var os = new java.io.ByteArrayOutputStream();
        try (var gen = SerialUtil.jsonGenerator(os)) {
            assertThat(gen).isNotNull();
            gen.writeStartObject();
            gen.writeStringProperty("key", "value");
            gen.writeEndObject();
        }
        assertThat(os.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"key\"");
    }

    @Test
    void jsonParser_createsParser() throws Exception {
        var jsonBytes = "{\"reference\":\"ref://parsed\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var is = new java.io.ByteArrayInputStream(jsonBytes);
        try (var parser = SerialUtil.jsonParser(is)) {
            assertThat(parser).isNotNull();
            var entry = SerialUtil.fromJson(parser, CrawlerEntry.class);
            assertThat(entry.getReference()).isEqualTo("ref://parsed");
        }
    }

    @Test
    void fromJson_jsonNode_reconstructsObject() throws Exception {
        var entry = new CrawlerEntry("ref://from-node");
        entry.setDepth(3);
        var json = SerialUtil.toJsonString(entry);
        var node = SerialUtil.getMapper().readTree(json);
        var restored = SerialUtil.fromJson(node, CrawlerEntry.class);
        assertThat(restored.getReference()).isEqualTo("ref://from-node");
        assertThat(restored.getDepth()).isEqualTo(3);
    }
}
