/* Copyright 2019-2026 Norconex Inc.
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
package com.norconex.crawler.web.doc.operations.sitemap.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.event.listeners.CrawlerLifeCycleListener;
import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.web.doc.operations.sitemap.SitemapContext;
import com.norconex.crawler.web.doc.operations.sitemap.SitemapRecord;
import com.norconex.crawler.web.doc.operations.sitemap.SitemapResolver;
import com.norconex.crawler.web.fetch.HttpMethod;
import com.norconex.crawler.web.fetch.WebFetchRequest;
import com.norconex.crawler.web.fetch.WebFetchResponse;
import com.norconex.crawler.web.ledger.WebCrawlerEntry;
import com.norconex.importer.doc.Doc;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
@Slf4j
public class GenericSitemapResolver extends CrawlerLifeCycleListener
        implements SitemapResolver, Configurable<GenericSitemapResolverConfig> {

    @JsonIgnore
    private final AtomicBoolean stopping = new AtomicBoolean();

    @EqualsAndHashCode.Include
    @ToString.Include
    @Getter
    private final GenericSitemapResolverConfig configuration =
            new GenericSitemapResolverConfig();

    @Override
    public void resolve(SitemapContext ctx) {
        var location = ctx.getLocation();
        if (stopping.get()) {
            LOG.debug(
                    "Skipping resolution of sitemap "
                            + "location (stop requested): {}",
                    location);
            return;
        }
        doResolve(ctx, new HashSet<>());
        //TODO log info about resolution?
    }

    private void doResolve(SitemapContext ctx, Set<String> resolvedIndices) {

        var location = ctx.getLocation();
        if (stopping.get()) {
            LOG.debug("Skipping sitemap (stop requested): {}", location);
            return;
        }
        // Sitemap indices can refer to each other.
        if (!resolvedIndices.add(location)) {
            LOG.debug("Sitemap already processed: {}", location);
            return;
        }

        // Sitemaps are read on every crawl, even when unmodified: an
        // unmodified sitemap says nothing about its pages, and skipping it
        // would leave them unseen in the crawl (orphans).
        List<SitemapRecord> childSitemaps = new ArrayList<>();
        var sitemapEntry = new WebCrawlerEntry(location);
        FetchResult fetchResult = null;

        try {
            var fetcher = ctx.getFetcher();
            LOG.info("Resolving sitemap: {}", location);
            // Execute the method.
            fetchResult = httpGet(fetcher, sitemapEntry, new Doc(location));
            var statusCode = fetchResult.response().getStatusCode();
            if (statusCode == HttpStatus.SC_OK) {
                childSitemaps.addAll(
                        processFetchedSitemap(ctx, fetchResult.doc()));
                LOG.info("         Resolved: {}", location);
            } else if (statusCode == HttpStatus.SC_NOT_FOUND) {
                LOG.debug("Sitemap not found : {}", location);
            } else {
                LOG.error(
                        "Could not obtain sitemap: {}. Expected status "
                                + "code {}, but got {}.",
                        location, HttpStatus.SC_OK, statusCode);
            }
        } catch (Exception e) {
            LOG.error(
                    "Cannot fetch sitemap: {} ({})",
                    location, e.getMessage(), e);
        } finally {
            if (fetchResult != null) {
                try {
                    fetchResult.doc().close();
                } catch (IOException e) {
                    LOG.error(
                            "Could not close sitemap file for: {}",
                            location, e);
                }
            }
        }

        for (SitemapRecord child : childSitemaps) {
            doResolve(ctx.withLocation(child.getLocation()), resolvedIndices);
        }
    }

    private List<SitemapRecord> processFetchedSitemap(
            SitemapContext ctx, Doc sitemapDoc) throws IOException {
        //NOTE: To be safe, we force caching to prevent connection/socket
        // timeouts (github #150).
        sitemapDoc.getInputStream().enforceFullCaching();

        List<SitemapRecord> childSitemaps = new ArrayList<>();
        var parser = new SitemapParser(configuration.isLenient(), stopping);
        childSitemaps.addAll(parser.parse(sitemapDoc, ctx.getUrlConsumer()));

        return childSitemaps;
    }

    private record FetchResult(WebFetchResponse response, Doc doc) {
    }

    // Follow redirects
    private FetchResult httpGet(
            Fetcher fetcher, WebCrawlerEntry entry, Doc doc)
            throws IOException {
        return httpGet(fetcher, entry, doc, 0);
    }

    private FetchResult httpGet(
            Fetcher fetcher, WebCrawlerEntry entry, Doc doc, int loop)
            throws IOException {

        var location = entry.getReference();
        var response = (WebFetchResponse) fetcher.fetch(
                new WebFetchRequest(doc, HttpMethod.GET));
        var redirectUrl = response.getRedirectTarget();
        if (StringUtils.isNotBlank(redirectUrl)
                && !redirectUrl.equalsIgnoreCase(location)) {
            if (loop >= 100) {
                LOG.error(
                        "Sitemap redirect loop detected. "
                                + "Last redirect: {} --> {}",
                        location, redirectUrl);
                return new FetchResult(response, doc);
            }
            LOG.info("         Redirect: {} --> {}", location, redirectUrl);

            // fetch redirect target then store back original URL
            entry.setReference(redirectUrl);
            var result =
                    httpGet(fetcher, entry, new Doc(redirectUrl), loop + 1);
            entry.setReference(location);
            return result;
        }
        return new FetchResult(response, doc);
    }

    //--- Life cycle events ----------------------------------------------------

    @Override
    protected void onCrawlerCrawlBegin(CrawlerEvent event) {
        // The same resolver can serve several runs in a JVM: a stop in a
        // previous one must not affect this one.
        stopping.set(false);
    }

    @Override
    protected void onCrawlerStopBegin(CrawlerEvent event) {
        stopping.set(true);
    }
}
