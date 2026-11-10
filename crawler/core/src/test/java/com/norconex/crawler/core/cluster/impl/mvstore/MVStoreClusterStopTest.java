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
package com.norconex.crawler.core.cluster.impl.mvstore;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.session.CrawlerSession;

/**
 * Stopping must tell listeners: {@link CrawlerEvent#CRAWLER_STOP_REQUEST_BEGIN}
 * then {@link CrawlerEvent#CRAWLER_STOP_REQUEST_END}, for every cluster type
 * (listeners such as the sitemap resolver rely on them to stop their work).
 */
class MVStoreClusterStopTest {

    @Test
    void testStopFiresStopEvents() {
        var session = mock(CrawlerSession.class);
        var cluster = new MVStoreCluster(new MVStoreClusterConnectorConfig());
        cluster.bindSession(session);

        cluster.stop();

        var order = inOrder(session);
        order.verify(session).fire(
                CrawlerEvent.CRAWLER_STOP_REQUEST_BEGIN, session);
        order.verify(session).fire(
                CrawlerEvent.CRAWLER_STOP_REQUEST_END, session);
    }
}
