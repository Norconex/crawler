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
package com.norconex.crawler.fs.fetch.impl.m365graph;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.session.CrawlerAttributes;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class M365GraphFetcherTest {

    @Test
    void testAcceptRequest() {
        var f = new M365GraphFetcher();
        assertThat(f.acceptRequest(new FileFetchRequest(new Doc(
                "m365sp://tenant/sites/site123/drives/drive123/items/item123"),
                DOCUMENT))).isTrue();
        assertThat(f.acceptRequest(new FileFetchRequest(new Doc(
                "m365od://tenant/users/user123/drives/drive123/items/item123"),
                DOCUMENT))).isTrue();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("s3://bucket/key"), DOCUMENT))).isFalse();
    }

    @Test
    void testWriteRead() {
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(
                        FsTestUtil.randomize(M365GraphFetcherConfig.class)));
    }

    @Test
    void testSharePointReferenceParseAndFormat() {
        var ref = M365GraphReference.parse(
                "m365sp://tenant/sites/site123/drives/drive123/items/item123");
        assertThat(ref.kind()).isEqualTo(M365GraphReference.Kind.ITEM);
        assertThat(ref.mode()).isEqualTo(M365GraphReference.Mode.SHAREPOINT);
        assertThat(ref.tenantId()).isEqualTo("tenant");
        assertThat(ref.siteId()).isEqualTo("site123");
        assertThat(ref.driveId()).isEqualTo("drive123");
        assertThat(ref.itemId()).isEqualTo("item123");
        assertThat(ref.itemApiPath())
                .isEqualTo("/sites/site123/drives/drive123/items/item123");
        assertThat(ref.toReference())
                .isEqualTo(
                        "m365sp://tenant/sites/site123/drives/drive123/items/item123");
    }

    @Test
    void testOneDriveReferenceParseAndFormat() {
        var ref = M365GraphReference.parse(
                "m365od://tenant/users/user123/drives/drive123/items/item123");
        assertThat(ref.kind()).isEqualTo(M365GraphReference.Kind.ITEM);
        assertThat(ref.mode()).isEqualTo(M365GraphReference.Mode.ONEDRIVE);
        assertThat(ref.tenantId()).isEqualTo("tenant");
        assertThat(ref.userId()).isEqualTo("user123");
        assertThat(ref.driveId()).isEqualTo("drive123");
        assertThat(ref.itemId()).isEqualTo("item123");
        assertThat(ref.itemApiPath())
                .isEqualTo("/users/user123/drives/drive123/items/item123");
        assertThat(ref.toReference())
                .isEqualTo(
                        "m365od://tenant/users/user123/drives/drive123/items/item123");
    }

    @Test
    void testSharePointSiteEntryReference() {
        var ref = M365GraphReference.parse("m365sp://tenant/sites/site123");
        assertThat(ref.kind()).isEqualTo(M365GraphReference.Kind.SITE);
        assertThat(ref.isDiscoveryEntry()).isTrue();
        assertThat(ref.drivesApiPath()).isEqualTo("/sites/site123/drives");
        assertThat(ref.toReference())
                .isEqualTo("m365sp://tenant/sites/site123");
    }

    @Test
    void testSharePointSiteUrlEntryReference() {
        var ref = M365GraphReference.parse(
                "m365sp://tenant/siteurl?url=https%3A%2F%2Fcontoso.sharepoint.com%2Fsites%2Fengineering");
        assertThat(ref.kind()).isEqualTo(M365GraphReference.Kind.SITE_URL);
        assertThat(ref.siteUrl())
                .isEqualTo("https://contoso.sharepoint.com/sites/engineering");
        assertThat(ref.resolveSiteApiPath())
                .isEqualTo("/sites/contoso.sharepoint.com:/sites/engineering");
    }

    @Test
    void testOneDriveUserEntryReference() {
        var ref = M365GraphReference.parse("m365od://tenant/users/user123");
        assertThat(ref.kind()).isEqualTo(M365GraphReference.Kind.USER);
        assertThat(ref.isDiscoveryEntry()).isTrue();
        assertThat(ref.drivesApiPath()).isEqualTo("/users/user123/drives");
        assertThat(ref.toReference())
                .isEqualTo("m365od://tenant/users/user123");
    }

    @Test
    void testDriveReferenceApiAndChild() {
        var ref = M365GraphReference.parse(
                "m365od://tenant/users/user123/drives/drive123");
        assertThat(ref.kind()).isEqualTo(M365GraphReference.Kind.DRIVE);
        assertThat(ref.driveRootApiPath())
                .isEqualTo("/users/user123/drives/drive123/root");
        assertThat(ref.child("root").itemApiPath())
                .isEqualTo("/users/user123/drives/drive123/items/root");
    }

    @Test
    void testDefaultStatusClassification() {
        var fetcher = new M365GraphFetcher();
        assertThat(fetcher.isValidStatus(200)).isTrue();
        assertThat(fetcher.isValidStatus(404)).isFalse();
        assertThat(fetcher.isNotFoundStatus(404)).isTrue();
        assertThat(fetcher.isNativeRetryStatus(429)).isTrue();
        assertThat(fetcher.getConfiguration().isNativeRetryEnabled())
                .isFalse();
    }

    @Test
    void testCustomStatusClassification() {
        var fetcher = new M365GraphFetcher();
        fetcher.getConfiguration()
                .setValidStatusCodes(List.of(200, 206))
                .setNotFoundStatusCodes(List.of(404, 410))
                .setNativeRetryStatusCodes(List.of(429))
                .setNativeRetryEnabled(true);

        assertThat(fetcher.isValidStatus(206)).isTrue();
        assertThat(fetcher.isNotFoundStatus(410)).isTrue();
        assertThat(fetcher.isNativeRetryStatus(503)).isFalse();
        assertThat(fetcher.getConfiguration().isNativeRetryEnabled())
                .isTrue();
    }

    @Test
    void testSourceDeltaEnabledOnIncrementalStartup() {
        var fetcher = new M365GraphFetcher();
        fetcher.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA));

        assertThat(fetcher.isSourceDeltaEnabled()).isTrue();
    }

    @Test
    void testSourceDeltaDisabledWithoutIncrementalSourceDelta() {
        var fetcher = new M365GraphFetcher();
        fetcher.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.CRAWLER_SCAN));
        assertThat(fetcher.isSourceDeltaEnabled()).isFalse();

        fetcher.fetcherStartup(mockSession(false,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA));
        assertThat(fetcher.isSourceDeltaEnabled()).isFalse();
    }

    @Test
    void testRejectInvalidReference() {
        assertThatThrownBy(() -> M365GraphReference.parse(
                "m365sp://tenant/invalid/path"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Invalid SharePoint reference");
    }

    private static CrawlerSession mockSession(
            boolean incremental,
            CrawlerConfig.ChangeDiscovery changeDiscovery) {
        var config = new CrawlerConfig().setChangeDiscovery(changeDiscovery);
        var context = mock(CrawlerContext.class);
        when(context.getCrawlConfig()).thenReturn(config);

        var sessionAttributes = mock(CrawlerAttributes.class);
        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(context);
        when(session.getSessionAttributes()).thenReturn(sessionAttributes);
        when(session.isIncremental()).thenReturn(incremental);
        return session;
    }
}
