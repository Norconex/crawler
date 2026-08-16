/* Copyright 2010-2026 Norconex Inc.
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
package com.norconex.crawler.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.norconex.commons.lang.bean.jackson.JsonXmlCollection;
import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.doc.operations.checksum.DocumentChecksummer;
import com.norconex.crawler.core.doc.operations.checksum.MetadataChecksummer;
import com.norconex.crawler.core.doc.operations.checksum.impl.Md5DocumentChecksummer;
import com.norconex.crawler.core.doc.operations.spoil.SpoiledReferenceStrategizer;
import com.norconex.crawler.core.doc.pipelines.queue.ReferencesProvider;
import com.norconex.crawler.core.fetch.FetchDirectiveSupport;
import com.norconex.crawler.web.doc.WebDocMetadata;
import com.norconex.crawler.web.doc.operations.canon.CanonicalLinkDetector;
import com.norconex.crawler.web.doc.operations.canon.impl.GenericCanonicalLinkDetector;
import com.norconex.crawler.web.doc.operations.checksum.impl.LastModifiedMetadataChecksummer;
import com.norconex.crawler.web.doc.operations.delay.DelayResolver;
import com.norconex.crawler.web.doc.operations.delay.impl.GenericDelayResolver;
import com.norconex.crawler.web.doc.operations.link.LinkExtractor;
import com.norconex.crawler.web.doc.operations.link.impl.HtmlLinkExtractor;
import com.norconex.crawler.web.doc.operations.link.impl.HtmlLinkExtractorConfig;
import com.norconex.crawler.web.doc.operations.recrawl.RecrawlableResolver;
import com.norconex.crawler.web.doc.operations.recrawl.impl.GenericRecrawlableResolver;
import com.norconex.crawler.web.doc.operations.robot.RobotsMetaProvider;
import com.norconex.crawler.web.doc.operations.robot.RobotsTxtProvider;
import com.norconex.crawler.web.doc.operations.robot.impl.StandardRobotsMetaProvider;
import com.norconex.crawler.web.doc.operations.robot.impl.StandardRobotsTxtProvider;
import com.norconex.crawler.web.doc.operations.scope.UrlScopeResolver;
import com.norconex.crawler.web.doc.operations.scope.impl.GenericUrlScopeResolver;
import com.norconex.crawler.web.doc.operations.sitemap.SitemapLocator;
import com.norconex.crawler.web.doc.operations.sitemap.SitemapResolver;
import com.norconex.crawler.web.doc.operations.sitemap.impl.GenericSitemapLocator;
import com.norconex.crawler.web.doc.operations.sitemap.impl.GenericSitemapResolver;
import com.norconex.crawler.web.doc.operations.url.WebUrlNormalizer;
import com.norconex.crawler.web.doc.operations.url.impl.GenericUrlNormalizer;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetcher;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetcherConfig;
import com.norconex.crawler.web.fetch.impl.webdriver.WebDriverFetcher;
import com.norconex.importer.ImporterConfig;

import lombok.Data;
import lombok.experimental.Accessors;
import lombok.experimental.FieldNameConstants;

/**
 * <p>
 * Web Crawler configuration, adding more options to the base
 * {@link CrawlerConfig}.
 * </p>
 * <h2>Start URLs</h2>
 * <p>
 * Crawling begins with specifying one or more references to either documents
 * or starting points to documents you want to crawl. For a web crawl, those
 * references are URLs (e.g., web site home page, sitemap, etc.).
 * There are different ways to specify one or more "start" references
 * (repeatable, shortened to "ref"):
 * </p>
 * <ul>
 *   <li><b>ref:</b> Any regular URL
 *       (see {@link #setStartReferences(List)}).</li>
 *   <li><b>refsFile:</b> A path to a file containing a list of start URLs
 *       (see {@link #setStartReferencesFiles(List)}).
 *       One per line.</li>
 *   <li><b>sitemap:</b> A URL pointing to a sitemap XML file that contains
 *       the URLs to crawl (see {@link #setStartReferencesSitemaps(List)}).</li>
 *   <li><b>provider:</b> Your own class implementing
 *       {@link ReferencesProvider} to dynamically provide a list of start
 *       URLs (see {@link #setStartReferencesProviders(List)}).</li>
 * </ul>
 * <p>
 * <b>Scope: </b> To limit crawling to specific web domains, and avoid creating
 * many filters to that effect, you can tell the crawler to "stay" within
 * the web site "scope" with
 * {@link #setUrlScopeResolver(UrlScopeResolver)}.
 * </p>
 *
 * <h2>URL Normalization</h2>
 * <p>
 * Pages on web sites are often referenced using different URL
 * patterns. Such URL variations can fool the crawler into downloading the
 * same document multiple times. To avoid this, URLs are "normalized". That is,
 * they are converted so they are always formulated the same way.
 * By default, the crawler only applies normalization in ways that are
 * semantically equivalent (see {@link GenericUrlNormalizer}).
 * </p>
 *
 * <h2>Crawl Speed</h2>
 * <p>
 * <b>Be kind</b> to web sites you crawl. Being too aggressive can be
 * perceived as a cyber-attack by the targeted web site (e.g., DoS attack).
 * This can lead to your crawler being blocked.
 * </p>
 * <p>
 * For this reason, the crawler plays nice by default.  It will wait a
 * few seconds between each page download, regardless of the maximum
 * number of threads specified or whether pages crawled are on different
 * web sites. This can of course be changed to be as fast as you want.
 * See {@link GenericDelayResolver})
 * for changing default options. You can also provide your own "delay resolver"
 * by supplying a class implementing {@link DelayResolver}.
 * </p>
 *
 * <h2>Crawl Depth</h2>
 * <p>
 * The crawl depth represents how many level from the start URL the crawler
 * goes. From a browser user perspective, it can be seen as the number of
 * link "clicks" required from a start URL in order to get to a specific page.
 * The crawler will crawl as deep for as long as it discovers new URLs
 * not getting rejected by your configuration.  This is not always desirable.
 * For instance, a web site could have dynamically generated URLs with infinite
 * possibilities (e.g., dynamically generated web calendars). To avoid
 * infinite crawls, it is recommended to limit the maximum depth to something
 * reasonable for your site with {@link #setMaxDepth(int)}.
 * </p>
 *
 * <h2>Keeping Referenced Links</h2>
 * <p>
 * By default the crawler stores, as metadata, URLs extracted from
 * documents that are in scope. Exceptions
 * are pages discovered at the configured maximum depth
 * ({@link #setMaxDepth(int)}).
 * This can be changed using the
 * {@link #setKeepReferencedLinks(Set)} method.
 * Changing this setting has no incidence on what page gets crawled.
 * Possible options are:
 * </p>
 * <ul>
 *   <li><b>INSCOPE:</b> Default. Store "in-scope" links as
 *       {@link WebDocMetadata#REFERENCED_URLS}.</li>
 *   <li><b>OUTSCOPE:</b> Store "out-of-scope" links as
 *       {@link WebDocMetadata#REFERENCED_URLS_OUT_OF_SCOPE}.</li>
 *   <li><b>MAXDEPTH:</b> Also store links extracted on pages at max depth.
 *       Must be used with at least one other option to have any effect.</li>
 * </ul>
 *
 * <h2>Orphan documents</h2>
 * <p>
 * Orphans are valid documents, which on subsequent crawls can no longer be
 * reached (e.g. there are no longer referenced). This is
 * regardless whether the file has been deleted or not at the source.
 * You can tell the crawler how to handle those with
 * {@link #setOrphansStrategy(OrphansStrategy)}. Possible options are:
 * </p>
 * <ul>
 *   <li><b>PROCESS:</b> Default. Tries to crawl orphans normally
 *       as if they were still reachable by the crawler.</li>
 *   <li><b>IGNORE:</b> Does nothing with orphans
 *       (not deleted, not processed)..</li>
 *   <li><b>DELETE:</b> Orphans are sent to your Committer for deletion.</li>
 * </ul>
 *
 * <h2>Error Handling</h2>
 * <p>
 * By default the crawler logs exceptions while trying to prevent them
 * from terminating a crawling session. There might be cases where you want
 * the crawler to halt upon encountering some types of exceptions.
 * You can do so with {@link #setStopOnExceptions(List)}.
 * </p>
 *
 * <h2>Crawler Events</h2>
 * <p>
 * The crawler fires all kind of events to notify interested parties of such
 * things as when a document is rejected, imported, committed, etc.).
 * You can listen to crawler events using {@link #setEventListeners(List)}.
 * </p>
 *
 * <h2>Document Importing</h2>
 * <p>
 * The process of transforming, enhancing, parsing to extracting plain text
 * and many other document-specific processing activities are handled by the
 * Norconex Importer module. See {@link ImporterConfig} for many
 * additional configuration options.
 * </p>
 *
 * <h2>Bad Documents</h2>
 * <p>
 * On a fresh crawl, documents that are unreachable or not obtained
 * successfully for some reason are simply logged and ignored.
 * On the other hand, documents that were successfully crawled once
 * and are suddenly failing on a subsequent crawl are considered "spoiled".
 * You can decide whether to grace (retry next time), delete, or ignore
 * those spoiled documents with
 * {@link #setSpoiledReferenceStrategizer(SpoiledReferenceStrategizer)}.
 * </p>
 *
 * <h2>Committing Documents</h2>
 * <p>
 * The last step of a successful processing of a document is to
 * store it in your preferred target repository (or repositories).
 * For this to happen, you have to configure one or more Committers
 * corresponding to your needs or create a custom one.
 * You can have a look at available Committers here:
 * <a href="https://opensource.norconex.com/committers/">
 * https://opensource.norconex.com/committers/</a>
 * See {@link #setCommitters(List)}.
 * </p>
 *
 * <h2>HTTP Fetcher</h2>
 * <p>
 * To crawl and parse a document, it first needs to be downloaded. This is the
 * role of one or more HTTP Fetchers.  {@link HttpClientFetcher} is the
 * default implementation and can handle most web sites.
 * There might be cases where a more specialized way of obtaining web resources
 * is needed. For instance, JavaScript-generated web pages are often best
 * handled by web browsers. In such case you can use the
 * {@link WebDriverFetcher}. You can also use
 * {@link #setFetchers(List)} to supply your own fetcher implementation.
 * </p>
 *
 * <h2>HTTP Methods</h2>
 * <p>
 * A fetcher typically issues an HTTP GET request to obtain a document.
 * There might be cases where you first want to issue a separate HEAD request.
 * One example is to filter documents based on the HTTP HEAD response
 * information, thus possibly saving downloading large files you don't want.
 * </p>
 * <p>
 * You can tell the crawler how it should handle HTTP GET and HEAD requests
 * using using {@link #setDocumentFetchSupport(FetchDirectiveSupport)} and
 * {@link #setMetadataFetchSupport(FetchDirectiveSupport)} respectively.
 * For each, the options are:
 * </p>
 * <ul>
 *   <li><b>DISABLED:</b> No HTTP call will be made using that method.</li>
 *   <li>
 *     <b>OPTIONAL:</b> If the HTTP method is not supported by any fetcher or
 *     the HTTP request for it was not successful, the document can still be
 *     processed successfully by the other HTTP method. Only relevant when
 *     both HEAD and GET are enabled.
 *   </li>
 *   <li>
 *     <b>REQUIRED:</b> If the HTTP method is not supported by any fetcher or
 *     the HTTP request for it was not successful, the document will be
 *     rejected and won't go any further,
 *     even if the other HTTP method was or could have been successful.
 *     Only relevant when both HEAD and GET are enabled.
 *   </li>
 * </ul>
 * <p>
 * If you enable only one HTTP method (default), then specifying
 * OPTIONAL or REQUIRED for it have the same effect.
 * At least one method needs to be enabled for an HTTP request to be attempted.
 * By default HEAD requests are DISABLED and GET are REQUIRED. If you are
 * unsure what settings to use, keep the defaults.
 * </p>
 *
 * <h2>Filtering Unwanted Documents</h2>
 * <p>
 * Without filtering, you would typically crawl many documents you are not
 * interested in.
 * There are different types filtering offered to you, occurring at different
 * time during a URL crawling process. The sooner in a URL processing
 * life-cycle you filter out a document the more you can improve the
 * crawler performance.  It may be important for you to
 * understand the differences:
 * </p>
 * <ul>
 *   <li>
 *     <b>Reference filters:</b> The fastest way to exclude a document.
 *     The filtering rule applies on the URL, before any HTTP request is made
 *     for that URL. Rejected documents are not queued for processing.
 *     They are not downloaded (thus no URLs are extracted). The
 *     specified "delay" between downloads is not applied (i.e. no delay
 *     for rejected documents).
 *   </li>
 *   <li>
 *     <p>
 *     <b>Metadata filters:</b> Applies filtering on a document metadata fields.
 *     </p>
 *     <p>
 *     If {@link #getMetadataFetchSupport()} value forces a distinct call
 *     for fetching metadata, these filters will be invoked after the crawler
 *     performs an HTTP HEAD request.
 *     It gives you the opportunity to filter documents based on the HTTP HEAD
 *     response to potentially save a more expensive HTTP GET request for
 *     download (but results in two HTTP requests for valid documents --
 *     HEAD and GET). Filtering occurs before URLs are extracted (since
 *     no content is downloaded.
 *     </p>
 *     <p>
 *     When {@link #getMetadataFetchSupport()} does not invoke making a
 *     distinct call for metadata, these filters
 *     will be invoked on the metadata of the HTTP response
 *     obtained from an HTTP GET request (as the document is downloaded).
 *     Filtering occurs after URLs are extracted.
 *     </p>
 *   </li>
 *   <li>
 *     <b>Document filters:</b> Use when having access to the document itself
 *     (and its content) is required to apply filtering. Always triggered
 *     after a document is downloaded and after URLs are extracted,
 *     but before it is imported (Importer module).
 *   </li>
 *   <li>
 *     <b>Importer filters:</b> The Importer module also offers document
 *     filtering options. At that point a document is already downloaded
 *     and its links extracted.  There are two types of filtering offered
 *     by the Importer: before and after document parsing (assuming you
 *     configured at least one parser).  Use filters before parsing if you
 *     need to filter on raw content or want to avoid parsing some documents.
 *     Use filters after parsing when you need to read the content
 *     as plain text.
 *   </li>
 * </ul>
 *
 * <h2>Robot Directives</h2>
 * <p>
 * By default, the crawler tries to respect instructions a web site has put
 * in place for the benefit of crawlers. The following is a list of some of the
 * popular ones. Where {@code null} can be set to disable support
 * for specific instructions, you can achieve the equivalent in XML
 * configuration by declaring the corresponding option as a self-closed tag.
 * </p>
 * <ul>
 *   <li>
 *     <b>"robots.txt" rules:</b> Rules defined in a "robots.txt" file at the
 *     root of a web site.
 *     Defaults to {@link StandardRobotsTxtProvider}.
 *     Set to {@code null} via
 *     {@link #setRobotsTxtProvider(RobotsTxtProvider)} to disable
 *     support for "robots.txt" rules.
 *   </li>
 *   <li>
 *     <b>Robots metadata rules:</b> Rules provided via the HTTP response
 *     header <code>X-Robots-Tag</code> for a given document.
 *     Defaults to {@link StandardRobotsMetaProvider}.
 *     Set to {@code null} via
 *     {@link #setRobotsMetaProvider(RobotsMetaProvider)} to disable
 *     support for robots metadata rules.
 *   </li>
 *   <li>
 *     <b>HTML "nofollow":</b> Most HTML-oriented link extractors support
 *     the <code>rel="nofollow"</code> attribute set on HTML links and offer
 *     a way to disable this instruction. E.g.,
 *     {@link HtmlLinkExtractorConfig#setIgnoreNofollow(boolean)}.
 *   </li>
 *   <li>
 *     <b>Sitemap:</b> Sitemaps XML files contain as listing of
 *     website URLs typically worth crawling.  They can be detected by
 *     looking at usual website locations or via robots.txt instructions, or
 *     they can be specified via {@link #setStartReferencesSitemaps(List)}.
 *     Defaults to {@link GenericSitemapResolver}, which
 *     offers support for disabling sitemap detection to rely only
 *     on sitemap start references.
 *     Setting it to {@code null} via
 *     {@link #setSitemapResolver(SitemapResolver)} effectively disables
 *     sitemap support altogether, and is thus incompatible with sitemaps
 *     specified as start references.
*    </li>
 *   <li>
 *     <b>Canonical URLs:</b> The crawler will reject URLs that are
 *     non-canonical, as per HTML <code>&lt;meta ...&gt;</code> or
 *     HTTP response instructions.
 *     Defaults to {@link GenericCanonicalLinkDetector}.
 *     Set to {@code null} via
 *     {@link #setCanonicalLinkDetector(CanonicalLinkDetector)} to disable
 *     support canonical links (increasing the chance of getting duplicates).
 *     </li>
 *   <li>
 *     <b>Fetcher-specific:</b> Fetcher implementations may support additional
 *     web site instructions with corresponding configuration options.
 *     For example, the default HTTP Fetcher ({@link HttpClientFetcher})
 *     supports the <code>If-Modified-Since</code> for web sites supporting it
 *     (only affects incremental crawls). To turn that off, use
 *     {@link HttpClientFetcherConfig#setIfModifiedSinceDisabled(boolean)}.
 *     See fetcher documentation for additional options.
 *   </li>
 * </ul>
 *
 * <h2>Re-crawl Frequency</h2>
 * <p>
 * The crawler will crawl any given URL at most one time per crawling session.
 * It is possible to skip documents that are not yet "ready" to be re-crawled
 * to speed up each crawling sessions.
 * Sitemap.xml directives to that effect are respected by default
 * ("frequency" and "lastmod"). You can have your own conditions for re-crawl
 * with {@link #setRecrawlableResolver(RecrawlableResolver)}.
 * This feature can be used for instance, to crawl a "news" section of your
 * site more frequently than let's say, an "archive" section of your site.
 * </p>
 *
 * <h2>Change Detection (Checksums)</h2>
 * <p>
 * To find out if a document has changed from one crawling session to another,
 * the crawler creates and keeps a digital signature, or checksum of each
 * crawled documents. Upon crawling the same URL again, a new checksum
 * is created and compared against the previous one. Any difference indicates
 * a modified document. There are two checksums at play, tested at
 * different times. One obtained from
 * a document metadata (default is {@link LastModifiedMetadataChecksummer},
 * and one from the document itself {@link Md5DocumentChecksummer}. You can
 * provide your own implementation. See:
 * {@link #setMetadataChecksummer(MetadataChecksummer)} and
 * {@link #setDocumentChecksummer(DocumentChecksummer)}.
 * </p>
 *
 * <h2>Deduplication</h2>
 * <p>
 * The crawler can attempt to detect and reject documents considered as
 * duplicates within a crawler session.  A document will be considered
 * duplicate if there was already a document processed with the same
 * metadata or document checksum. To enable this feature, set
 * {@link #setMetadataDeduplicate(boolean)} and/or
 * {@link #setDocumentDeduplicate(boolean)} to <code>true</code>. Setting
 * those will have no effect if the corresponding checksummers are
 * {@code null} or checksums are otherwise not are being generated.
 * </p>
 * <p>
 * Deduplication can impact crawl performance.  It is recommended you
 * use it only if you can't distinguish duplicates via other means
 * (URL normalizer, canonical URL support, etc.).  Also, you should only
 * enable this feature if you know your checksummer(s) will generate
 * a checksum that is acceptably unique to you.
 * </p>
 *
 * <h2>URL Extraction</h2>
 * <p>
 * To be able to crawl a web site, links need to be extracted from
 * web pages.  It is the job of a link extractor.  It is possible to use
 * multiple link extractor for different type of content.  By default,
 * the {@link HtmlLinkExtractor} is used, but you can add others or
 * provide your own with {@link #setLinkExtractors(List)}.
 * </p>
 * <p>
 * There might be
 * cases where you want a document to be parsed by the Importer and establish
 * which links to process yourself during the importing phase (for more
 * advanced use cases). In such cases, you can identify a document metadata
 * field to use as a URL holding tanks after importing has occurred.
 * URLs in that field will become eligible for crawling.
 * See {@link #setPostImportLinks(TextMatcher)}.
 * </p>
 */
@Data
@Accessors(chain = true)
@FieldNameConstants
//@JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
public class WebCrawlerConfig extends CrawlerConfig {

    /**
     * Flags for storing as metadata a page referenced links.
     * Default will only store linked that are "in-scope".
     * See class documentation for details.
     */
    public enum ReferencedLinkType {
        INSCOPE, OUTSCOPE, MAXDEPTH;
    }

    @JsonXmlCollection(entryName = "sitemap")
    private final List<String> startReferencesSitemaps = new ArrayList<>();

    private final Set<ReferencedLinkType> keepReferencedLinks =
            new HashSet<>(List.of(ReferencedLinkType.INSCOPE));

    /**
     * The strategy to use to determine if a URL is in scope.
     */
    private UrlScopeResolver urlScopeResolver = new GenericUrlScopeResolver();

    private final List<WebUrlNormalizer> urlNormalizers =
            new ArrayList<>(Arrays.asList(new GenericUrlNormalizer()));

    /**
     * The delay resolver dictating the minimum amount of time to wait
     * between web requests. Defaults to {@link GenericDelayResolver}.
     */
    private DelayResolver delayResolver = new GenericDelayResolver();

    /**
     * The canonical link detector.
     * Defaults to {@link GenericCanonicalLinkDetector}.
     * Set to {@code null} to disable canonical link detection.
     */
    private CanonicalLinkDetector canonicalLinkDetector =
            new GenericCanonicalLinkDetector();

    private final List<LinkExtractor> linkExtractors =
            new ArrayList<>(Arrays.asList(new HtmlLinkExtractor()));

    private TextMatcher postImportLinks = new TextMatcher();
    /**
     * Whether to keep the Importer-populated fields
     * from {@link #getPostImportLinks()}. By default, those are deleted
     * from a document when the URLs they contain are queued for processing
     * or otherwise evaluated.
     * @see #setPostImportLinks(TextMatcher)
     */
    private boolean postImportLinksKeep;

    /**
     * The provider of robots.txt rules for a site (if applicable).
     * Defaults to {@link StandardRobotsTxtProvider}.
     * Set to {@code null} to disable.
     */
    private RobotsTxtProvider robotsTxtProvider =
            new StandardRobotsTxtProvider();

    /**
     * The provider of robots metadata rules for a page (if applicable).
     * Defaults to {@link StandardRobotsMetaProvider}.
     * Set to {@code null} to disable.
     */
    private RobotsMetaProvider robotsMetaProvider =
            new StandardRobotsMetaProvider();

    /**
     * The resolver of web site sitemaps (if applicable).
     * Defaults to {@link GenericSitemapResolver}.
     * Set to {@code null} to disable all sitemap support, or
     * see class documentation to disable sitemap detection only.
     * @see SitemapLocator
     */
    private SitemapResolver sitemapResolver = new GenericSitemapResolver();

    /**
     * The locator of sitemaps (if applicable).
     * Defaults to {@link GenericSitemapLocator}.
     * Set to {@code null} to disable locating sitemaps
     * (relying on sitemaps defined as start reference, if any).
     * @see SitemapResolver
     */
    private SitemapLocator sitemapLocator = new GenericSitemapLocator();

    /**
     * The resolver that indicates whether a given URL is ready to be
     * crawled by a new crawl session. Usually amounts to checking if enough
     * time has passed between two crawl sessions.
     * Defaults to {@link GenericRecrawlableResolver}.
     */
    private RecrawlableResolver recrawlableResolver =
            new GenericRecrawlableResolver();

    public WebCrawlerConfig() {
        setMetadataChecksummer(new LastModifiedMetadataChecksummer());
        setFetchers(List.of(new HttpClientFetcher()));
    }

    //--- Accessors ------------------------------------------------------------

    /**
     * Gets sitemap URLs to be used as starting points for crawling.
     * @return sitemap URLs (never {@code null})
     * @since 2.3.0
     */
    public List<String> getStartReferencesSitemaps() {
        return Collections.unmodifiableList(startReferencesSitemaps);
    }

    /**
     * Sets the sitemap URLs used as starting points for crawling.
     * @param startReferencesSitemaps sitemap URLs
     * @return this
     * @since 3.0.0
     */
    public WebCrawlerConfig setStartReferencesSitemaps(
            List<String> startReferencesSitemaps) {
        CollectionUtil.setAll(
                this.startReferencesSitemaps, startReferencesSitemaps);
        CollectionUtil.removeBlanks(this.startReferencesSitemaps);
        return this;
    }

    /**
     * Gets what type of referenced links to keep, if any.
     * Those links are URLs extracted by link extractors. See class
     * documentation for more details.
     * @return preferences for keeping links
     * @since 3.0.0
     */
    public Set<ReferencedLinkType> getKeepReferencedLinks() {
        return Collections.unmodifiableSet(keepReferencedLinks);
    }

    /**
     * Sets whether to keep referenced links and what to keep.
     * Those links are URLs extracted by link extractors. See class
     * documentation for more details.
     * @param keepReferencedLinks option for keeping links
     * @return this
     * @since 3.0.0
     */
    public WebCrawlerConfig setKeepReferencedLinks(
            Set<ReferencedLinkType> keepReferencedLinks) {
        CollectionUtil.setAll(this.keepReferencedLinks, keepReferencedLinks);
        return this;
    }

    /**
     * Gets link extractors.
     * @return link extractors
     */
    public List<LinkExtractor> getLinkExtractors() {
        return Collections.unmodifiableList(linkExtractors);
    }

    /**
     * Sets link extractors.
     * @param linkExtractors link extractors
     * @return this
     * @since 3.0.0
     */
    public WebCrawlerConfig setLinkExtractors(
            List<LinkExtractor> linkExtractors) {
        CollectionUtil.setAll(this.linkExtractors, linkExtractors);
        return this;
    }

    /**
     * Gets a field matcher used to identify post-import metadata fields
     * holding URLs to consider for crawling.
     * @return field matcher
     * @since 3.0.0
     */
    public TextMatcher getPostImportLinks() {
        return postImportLinks;
    }

    /**
     * Set a field matcher used to identify post-import metadata fields
     * holding URLs to consider for crawling.
     * @param fieldMatcher field matcher
     * @return this
     * @since 3.0.0
     */
    public WebCrawlerConfig setPostImportLinks(TextMatcher fieldMatcher) {
        postImportLinks.copyFrom(fieldMatcher);
        return this;
    }

    /**
     * Gets URL normalizers. Executed in the order provided.
     * Defaults to a single {@link GenericUrlNormalizer} with its default
     * settings.
     * @return URL normalizers
     */
    public List<WebUrlNormalizer> getUrlNormalizers() {
        return Collections.unmodifiableList(urlNormalizers);
    }

    /**
     * Sets URL normalizers. Executed in the order provided.
     * Defaults to a single {@link GenericUrlNormalizer} with its default
     * settings.
     * @param urlNormalizers URL normalizers
     * @return this
     */
    public WebCrawlerConfig setUrlNormalizers(
            List<WebUrlNormalizer> urlNormalizers) {
        CollectionUtil.setAll(this.urlNormalizers, urlNormalizers);
        return this;
    }

}
