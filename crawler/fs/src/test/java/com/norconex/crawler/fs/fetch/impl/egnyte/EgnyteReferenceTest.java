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
package com.norconex.crawler.fs.fetch.impl.egnyte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.bean.BeanMapper;

class EgnyteReferenceTest {

    @Test
    void testFolderParseAndFormat() {
        var ref = EgnyteReference.parse("egnyte://acme/folders/root");
        assertThat(ref.kind()).isEqualTo(EgnyteReference.Kind.FOLDER);
        assertThat(ref.domain()).isEqualTo("acme");
        assertThat(ref.folderId()).isEqualTo("root");
        assertThat(ref.isDiscoveryEntry()).isTrue();
        assertThat(ref.toReference()).isEqualTo("egnyte://acme/folders/root");
    }

    @Test
    void testItemParseAndFormat() {
        var ref =
                EgnyteReference.parse("egnyte://acme/folders/root/items/12345");
        assertThat(ref.kind()).isEqualTo(EgnyteReference.Kind.ITEM);
        assertThat(ref.domain()).isEqualTo("acme");
        assertThat(ref.folderId()).isEqualTo("root");
        assertThat(ref.itemId()).isEqualTo("12345");
        assertThat(ref.itemFileApiPath()).isEqualTo("/fs-ids/12345");
        assertThat(ref.toReference())
                .isEqualTo("egnyte://acme/folders/root/items/12345");
    }

    @Test
    void testChildFromFolderReference() {
        var ref = EgnyteReference.parse("egnyte://acme/folders/abc");
        var child = ref.child("def");
        assertThat(child.kind()).isEqualTo(EgnyteReference.Kind.ITEM);
        assertThat(child.toReference())
                .isEqualTo("egnyte://acme/folders/abc/items/def");
    }

    @Test
    void testRejectInvalidReference() {
        assertThatThrownBy(
                () -> EgnyteReference.parse("egnyte://acme/invalid/path"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Invalid Egnyte reference");
    }

    @Test
    void testRejectInvalidScheme() {
        assertThatThrownBy(
                () -> EgnyteReference.parse("box://acme/folders/root"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(
                                "Unsupported Egnyte scheme in reference");
    }

    @Test
    void testConfigWriteRead() {
        var cfg = new EgnyteFetcherConfig()
                .setApiBaseUrl("https://{domain}.egnyte.com/pubapi/v1")
                .setAccessToken("test-token");
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(cfg));
    }
}
