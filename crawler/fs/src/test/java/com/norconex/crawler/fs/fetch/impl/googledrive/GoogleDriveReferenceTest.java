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
package com.norconex.crawler.fs.fetch.impl.googledrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.bean.BeanMapper;

class GoogleDriveReferenceTest {

    @Test
    void testUserEntryParseAndFormat() {
        var ref = GoogleDriveReference.parse(
                "gdrive://workspace-01/users/user@example.com");
        assertThat(ref.kind()).isEqualTo(GoogleDriveReference.Kind.USER);
        assertThat(ref.workspaceId()).isEqualTo("workspace-01");
        assertThat(ref.userId()).isEqualTo("user@example.com");
        assertThat(ref.isDiscoveryEntry()).isTrue();
        assertThat(ref.toReference())
                .isEqualTo("gdrive://workspace-01/users/user@example.com");
    }

    @Test
    void testSharedDriveItemParseAndFormat() {
        var ref = GoogleDriveReference.parse(
                "gdrive://workspace-01/drives/drive-123/items/item-456");
        assertThat(ref.kind()).isEqualTo(GoogleDriveReference.Kind.ITEM);
        assertThat(ref.workspaceId()).isEqualTo("workspace-01");
        assertThat(ref.driveId()).isEqualTo("drive-123");
        assertThat(ref.itemId()).isEqualTo("item-456");
        assertThat(ref.itemApiPath()).isEqualTo("/files/item-456");
        assertThat(ref.toReference()).isEqualTo(
                "gdrive://workspace-01/drives/drive-123/items/item-456");
    }

    @Test
    void testChildFromDriveReference() {
        var ref = GoogleDriveReference.parse(
                "gdrive://workspace-01/drives/drive-123");
        var child = ref.child("item-789");
        assertThat(child.kind()).isEqualTo(GoogleDriveReference.Kind.ITEM);
        assertThat(child.toReference())
                .isEqualTo(
                        "gdrive://workspace-01/drives/drive-123/items/item-789");
    }

    @Test
    void testRejectInvalidReference() {
        assertThatThrownBy(() -> GoogleDriveReference.parse(
                "gdrive://workspace-01/invalid/path"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Invalid Google Drive reference");
    }

    @Test
    void testRejectInvalidScheme() {
        assertThatThrownBy(() -> GoogleDriveReference.parse(
                "m365sp://tenant/sites/site123"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(
                                "Unsupported Google Drive scheme in reference");
    }

    @Test
    void testConfigWriteRead() {
        var cfg = new GoogleDriveFetcherConfig()
                .setApplicationName("test-app")
                .setClientEmail("svc@example.com")
                .setDelegatedUser("user@example.com")
                .setPrivateKey(
                        "-----BEGIN PRIVATE KEY-----\nQUJD\n-----END PRIVATE KEY-----");
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(cfg));
    }

    @Test
    void testDefaultTextExportPolicy() {
        var cfg = new GoogleDriveFetcherConfig();
        assertThat(cfg.defaultExportMimeTypes().get(
                "application/vnd.google-apps.document"))
                        .isEqualTo("text/plain");
    }
}
