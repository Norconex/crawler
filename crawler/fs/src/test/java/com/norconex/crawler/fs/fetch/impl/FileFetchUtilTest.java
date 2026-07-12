/* Copyright 2026 Norconex Inc.
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
package com.norconex.crawler.fs.fetch.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.fetch.FetchDirective;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class FileFetchUtilTest {

    @Test
    void testReferenceStartsWith() {
        assertThat(FileFetchUtil.referenceStartsWith(
                new FileFetchRequest(new Doc("ZIP:file:///tmp/a.zip"), //
                        FetchDirective.DOCUMENT),
                "zip:", "tar:")).isTrue();

        assertThat(FileFetchUtil.referenceStartsWith(
                new FileFetchRequest(new Doc("http://example.com"), //
                        FetchDirective.DOCUMENT),
                "zip:", "tar:")).isFalse();

        assertThat(FileFetchUtil.referenceStartsWith(null, "zip:")).isFalse();
    }

    @Test
    void testUriEncodeLocalPath() {
        assertThat(FileFetchUtil.uriEncodeLocalPath(
                "/tmp/a b/c#d/e:f.txt"))
                        .isEqualTo("/tmp/a%20b/c%23d/e%3af.txt");

        assertThat(FileFetchUtil.uriEncodeLocalPath(
                "file:///tmp/a b/c#d/e:f.txt"))
                        .isEqualTo("file:///tmp/a%20b/c%23d/e%3af.txt");

        assertThat(FileFetchUtil.uriEncodeLocalPath("zip:file:///tmp/a b.zip"))
                .isEqualTo("zip:file:///tmp/a b.zip");

        assertThat(FileFetchUtil.uriEncodeLocalPath(null)).isNull();
    }
}
