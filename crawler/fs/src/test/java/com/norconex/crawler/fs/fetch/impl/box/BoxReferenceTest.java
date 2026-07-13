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
package com.norconex.crawler.fs.fetch.impl.box;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.bean.BeanMapper;

class BoxReferenceTest {

    @Test
    void testFolderParseAndFormat() {
        var ref = BoxReference.parse("box://ent-01/folders/0");
        assertThat(ref.kind()).isEqualTo(BoxReference.Kind.FOLDER);
        assertThat(ref.enterpriseId()).isEqualTo("ent-01");
        assertThat(ref.folderId()).isEqualTo("0");
        assertThat(ref.isDiscoveryEntry()).isTrue();
        assertThat(ref.toReference()).isEqualTo("box://ent-01/folders/0");
    }

    @Test
    void testItemParseAndFormat() {
        var ref = BoxReference.parse("box://ent-01/folders/0/items/12345");
        assertThat(ref.kind()).isEqualTo(BoxReference.Kind.ITEM);
        assertThat(ref.enterpriseId()).isEqualTo("ent-01");
        assertThat(ref.folderId()).isEqualTo("0");
        assertThat(ref.itemId()).isEqualTo("12345");
        assertThat(ref.itemFileApiPath()).isEqualTo("/files/12345");
        assertThat(ref.toReference())
                .isEqualTo("box://ent-01/folders/0/items/12345");
    }

    @Test
    void testChildFromFolderReference() {
        var ref = BoxReference.parse("box://ent-01/folders/123");
        var child = ref.child("456");
        assertThat(child.kind()).isEqualTo(BoxReference.Kind.ITEM);
        assertThat(child.toReference())
                .isEqualTo("box://ent-01/folders/123/items/456");
    }

    @Test
    void testRejectInvalidReference() {
        assertThatThrownBy(
                () -> BoxReference.parse("box://ent-01/invalid/path"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Invalid Box reference");
    }

    @Test
    void testRejectInvalidScheme() {
        assertThatThrownBy(
                () -> BoxReference.parse("gdrive://tenant/folders/0"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(
                                "Unsupported Box scheme in reference");
    }

    @Test
    void testConfigWriteRead() {
        var cfg = new BoxFetcherConfig()
                .setApiBaseUrl("https://api.box.com/2.0")
                .setAccessToken("test-token");
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(cfg));
    }
}
