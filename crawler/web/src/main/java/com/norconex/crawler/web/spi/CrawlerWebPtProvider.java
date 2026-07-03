/* Copyright 2023-2026 Norconex Inc.
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
package com.norconex.crawler.web.spi;

import java.util.List;
import java.util.function.Predicate;

import org.apache.commons.collections4.MultiMapUtils;
import org.apache.commons.collections4.MultiValuedMap;

import com.norconex.commons.lang.ClassFinder;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.spi.PolymorphicTypeProvider;
import com.norconex.commons.lang.event.EventListener;
import com.norconex.crawler.core.doc.operations.DocumentConsumer;
import com.norconex.crawler.core.doc.operations.checksum.MetadataChecksummer;
import com.norconex.crawler.core.doc.operations.filter.DocumentFilter;
import com.norconex.crawler.core.doc.operations.filter.MetadataFilter;
import com.norconex.crawler.core.doc.operations.filter.ReferenceFilter;
import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.web.doc.operations.canon.CanonicalLinkDetector;
import com.norconex.crawler.web.doc.operations.delay.DelayResolver;
import com.norconex.crawler.web.doc.operations.link.LinkExtractor;
import com.norconex.crawler.web.doc.operations.recrawl.RecrawlableResolver;
import com.norconex.crawler.web.doc.operations.robot.RobotsMetaProvider;
import com.norconex.crawler.web.doc.operations.robot.RobotsTxtProvider;
import com.norconex.crawler.web.doc.operations.scope.UrlScopeResolver;
import com.norconex.crawler.web.doc.operations.sitemap.SitemapLocator;
import com.norconex.crawler.web.doc.operations.sitemap.SitemapResolver;
import com.norconex.crawler.web.doc.operations.url.WebUrlNormalizer;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetcher;
import com.norconex.crawler.web.fetch.impl.playwright.PlaywrightFetcher;
import com.norconex.crawler.web.fetch.impl.webdriver.WebDriverFetcher;
import com.norconex.crawler.web.fetch.util.RedirectUrlProvider;

/**
 * <p>
 * For auto registering in {@link BeanMapper}.
 * </p>
 */
public class CrawlerWebPtProvider implements PolymorphicTypeProvider {

    private static final String WEB_BASE_PKG = "com.norconex.crawler.web";
    private static final String OPERATIONS_BASE_PKG =
            WEB_BASE_PKG + ".doc.operations";
    private static final String FILTER_BASE_PKG =
            OPERATIONS_BASE_PKG + ".filter";

    @Override
    public MultiValuedMap<Class<?>, Class<?>> getPolymorphicTypes() {

        MultiValuedMap<Class<?>, Class<?>> map =
                MultiMapUtils.newListValuedHashMap();
        addPolyType(map, CanonicalLinkDetector.class);
        addPolyType(map, MetadataChecksummer.class,
                OPERATIONS_BASE_PKG + ".checksum");
        addPolyType(map, EventListener.class,
                WEB_BASE_PKG + ".event.listeners");
        addPolyType(map, DelayResolver.class);
        addPolyType(map, DocumentFilter.class, FILTER_BASE_PKG);
        addPolyType(map, MetadataFilter.class, FILTER_BASE_PKG);
        addPolyType(map, ReferenceFilter.class, FILTER_BASE_PKG);
        addPolyType(map, LinkExtractor.class);
        addPolyType(map, DocumentConsumer.class,
                OPERATIONS_BASE_PKG + ".image");
        addPolyType(map, RecrawlableResolver.class);
        addPolyType(map, RedirectUrlProvider.class);
        addPolyType(map, RobotsTxtProvider.class);
        addPolyType(map, RobotsMetaProvider.class);
        addPolyType(map, SitemapLocator.class);
        addPolyType(map, SitemapResolver.class);
        addPolyType(map, WebUrlNormalizer.class);
        addPolyType(map, UrlScopeResolver.class);

        //        map.put(CrawlerConfig.class, WebCrawlerConfig.class);
        map.putAll(Fetcher.class, List.of(
                HttpClientFetcher.class,
                PlaywrightFetcher.class,
                WebDriverFetcher.class));

        // For unit test
        //        addPolyType(map, EventListener.class, "session.recovery");
        //        addPolyType(map, Committer.class, "session.recovery");

        return map;
    }

    private void addPolyType(
            MultiValuedMap<Class<?>, Class<?>> polyTypes,
            Class<?> baseClass) {
        addPolyType(polyTypes, baseClass, null);
    }

    private void addPolyType(
            MultiValuedMap<Class<?>, Class<?>> polyTypes,
            Class<?> baseClass,
            String basePackage) {
        polyTypes.putAll(baseClass, ClassFinder.findSubTypes(
                baseClass,
                basePackage == null
                        ? nm -> nm.startsWith(baseClass.getPackageName())
                        : filter(basePackage)));
    }

    private Predicate<String> filter(String basePackage) {
        return nm -> nm.startsWith(basePackage);
    }
}
