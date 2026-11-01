/* Copyright 2022-2026 Norconex Inc.
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
package com.norconex.importer.doc;

import static com.norconex.importer.TestUtil.toCachedInputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.io.CachedInputStream;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.ImporterRuntimeException;
import com.norconex.importer.TestUtil;

@Timeout(30)
@SuppressWarnings("null")
class DocTest {

    @Test
    void testDocInfo() {
        var di1 = new Doc("ref");
        di1.setContentType(ContentType.BMP);
        di1.setParentReferences(List.of("parentRef"));
        di1.addParentReference("parentRef2");
        di1.setCharset(StandardCharsets.US_ASCII);

        var di2 = new Doc(di1);
        assertThat(di1).isEqualTo(di2);

        var di4 = new Doc(di1);
        assertThat(di1).isEqualTo(di4);

        di4 = di4.withReference("ref2");
        assertThat(di1).isNotEqualTo(di4);

        assertThat(di4.getParentReferences())
                .containsExactly("parentRef", "parentRef2");
    }

    @SuppressWarnings("resource")
    @Test
    void testDoc() {
        var doc = new Doc("ref.html")
                .setInputStream(toCachedInputStream("a test"));
        assertThat(doc.getReference()).isEqualTo(doc.getReference());
        assertThat(TestUtil.contentAsString(doc)).isEqualTo("a test");

        doc = new Doc("ref.html").setInputStream(toCachedInputStream("a test"));
        assertThat(doc.getReference()).isEqualTo("ref.html");

        var props = new Properties();
        props.add("test", "value");
        doc = new Doc("ref.html")
                .setInputStream(
                        CachedInputStream.cache(InputStream.nullInputStream()))
                .setMetadata(props);
        assertThat(doc.getMetadata().size()).isOne();
        assertThat(doc.getMetadata().getString("test")).isEqualTo("value");
    }

    @Test
    void testDispose() {
        // we can get content a few times until disposed
        @SuppressWarnings("resource")
        var doc = new Doc("ref.html")
                .setInputStream(toCachedInputStream("a test"));
        assertThatNoException().isThrownBy(() -> {
            TestUtil.contentAsString(doc);
            TestUtil.contentAsString(doc);
            doc.close();
        });
        assertThatException().isThrownBy(() -> TestUtil.contentAsString(doc));

        // becomes OK to read when assigning a *new* stream
        doc.setInputStream(doc.getInputStream());
        assertThatException().isThrownBy(() -> TestUtil.contentAsString(doc));

        doc.setInputStream(toCachedInputStream("a test"));
        assertThatNoException().isThrownBy(() -> TestUtil.contentAsString(doc));
    }

    @Test
    void testSetInputStream() {
        // setting both a CachedInputStream or other stream should be OK
        @SuppressWarnings("resource")
        var doc = new Doc("ref.html").setInputStream(
                CachedInputStream.cache(InputStream.nullInputStream()));

        doc.setInputStream(doc.getStreamFactory().newInputStream("a test"));
        assertThat(TestUtil.contentAsString(doc)).isEqualTo("a test");

        doc.setInputStream(new ByteArrayInputStream("a test".getBytes()));
        assertThat(TestUtil.contentAsString(doc)).isEqualTo("a test");

        // IO exceptions should come back as importer runtime exception
        assertThatExceptionOfType(ImporterRuntimeException.class)
                .isThrownBy(
                        //NOSONAR
                        () -> doc.setInputStream(
                                TestUtil.failingInputStream()));
    }
}
