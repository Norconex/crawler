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
package com.norconex.crawler.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.jeasy.random.FieldPredicates.inClass;
import static org.jeasy.random.FieldPredicates.named;
import static org.jeasy.random.FieldPredicates.ofType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.NullInputStream;
import org.apache.commons.lang3.ArrayUtils;
import org.jeasy.random.EasyRandom;
import org.jeasy.random.EasyRandomParameters;
import org.jeasy.random.api.Randomizer;
import org.jeasy.random.randomizers.misc.BooleanRandomizer;
import org.jeasy.random.randomizers.number.IntegerRandomizer;
import org.jeasy.random.randomizers.number.LongRandomizer;
import org.jeasy.random.randomizers.number.NumberRandomizer;
import org.jeasy.random.randomizers.text.StringRandomizer;

import com.norconex.committer.core.Committer;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.impl.MemoryCommitter;
import com.norconex.commons.lang.CircularRange;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.io.CachedInputStream;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.Crawler;
import com.norconex.crawler.core.cluster.ClusterConnector;
import com.norconex.crawler.core.cluster.impl.hazelcast.HazelcastClusterConnector;
import com.norconex.crawler.core.doc.operations.DocumentConsumer;
import com.norconex.crawler.core.doc.operations.checksum.DocumentChecksummer;
import com.norconex.crawler.core.doc.operations.checksum.MetadataChecksummer;
import com.norconex.crawler.core.doc.operations.checksum.impl.GenericMetadataChecksummer;
import com.norconex.crawler.core.doc.operations.checksum.impl.Md5DocumentChecksummer;
import com.norconex.crawler.core.doc.operations.filter.DocumentFilter;
import com.norconex.crawler.core.doc.operations.filter.MetadataFilter;
import com.norconex.crawler.core.doc.operations.filter.ReferenceFilter;
import com.norconex.crawler.core.doc.operations.filter.impl.ExtensionReferenceFilter;
import com.norconex.crawler.core.doc.operations.filter.impl.GenericMetadataFilter;
import com.norconex.crawler.core.doc.operations.filter.impl.GenericReferenceFilter;
import com.norconex.crawler.core.doc.operations.spoil.SpoiledReferenceStrategizer;
import com.norconex.crawler.core.doc.operations.spoil.impl.GenericSpoiledReferenceStrategizer;
import com.norconex.crawler.core.doc.pipelines.queue.ReferencesProvider;
import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.web.doc.operations.canon.CanonicalLinkDetector;
import com.norconex.crawler.web.doc.operations.canon.impl.GenericCanonicalLinkDetector;
import com.norconex.crawler.web.doc.operations.checksum.impl.LastModifiedMetadataChecksummer;
import com.norconex.crawler.web.doc.operations.delay.DelayResolver;
import com.norconex.crawler.web.doc.operations.delay.impl.BaseDelayResolverConfig.DelayResolverScope;
import com.norconex.crawler.web.doc.operations.delay.impl.GenericDelayResolver;
import com.norconex.crawler.web.doc.operations.filter.impl.SegmentCountUrlFilter;
import com.norconex.crawler.web.doc.operations.image.impl.FeaturedImageResolver;
import com.norconex.crawler.web.doc.operations.link.LinkExtractor;
import com.norconex.crawler.web.doc.operations.link.impl.DomLinkExtractor;
import com.norconex.crawler.web.doc.operations.recrawl.RecrawlableResolver;
import com.norconex.crawler.web.doc.operations.robot.RobotsMetaProvider;
import com.norconex.crawler.web.doc.operations.robot.RobotsTxtProvider;
import com.norconex.crawler.web.doc.operations.robot.impl.StandardRobotsMetaProvider;
import com.norconex.crawler.web.doc.operations.robot.impl.StandardRobotsTxtProvider;
import com.norconex.crawler.web.doc.operations.scope.UrlScopeResolver;
import com.norconex.crawler.web.doc.operations.scope.impl.GenericUrlScopeResolver;
import com.norconex.crawler.web.doc.operations.sitemap.SitemapLocator;
import com.norconex.crawler.web.doc.operations.sitemap.SitemapResolver;
import com.norconex.crawler.web.doc.operations.sitemap.impl.GenericSitemapLocator;
import com.norconex.crawler.web.doc.operations.url.WebUrlNormalizer;
import com.norconex.crawler.web.doc.operations.url.impl.GenericUrlNormalizer;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpAuthConfig;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpAuthMethod;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetcher;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetcherConfig;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetcherConfig.CookieSpec;
import com.norconex.importer.ImporterConfig;
import com.norconex.importer.doc.Doc;

import lombok.NonNull;

@SuppressWarnings("null")
public final class WebTestUtil {

    public static final String TEST_CRAWLER_ID = "test-crawler";
    public static final String TEST_CRAWL_SESSION_ID = "test-session";
    public static final EasyRandom RANDOMIZER = createRandomizer();

    public static <T> T randomize(Class<T> cls) {
        return RANDOMIZER.nextObject(cls);
    }

    /**
     * Gets the {@link MemoryCommitter} from first committer of the first
     * crawler from a crawl session (assuming the first committer is
     * a {@link MemoryCommitter}).  If that committer does not
     * exists or is not a memory committer, an exception is thrown.
     * @param crawler crawl session
     * @return Memory committer
     */
    public static MemoryCommitter firstCommitter(@NonNull Crawler crawler) {
        return (MemoryCommitter) crawler
                .getCrawlConfig()
                .getCommitters()
                .get(0);
    }

    /**
     * Gets the first {@link MemoryCommitter} encountered from all registered
     * committers, or null if there are none.
     * @param config crawler config
     * @return Memory committer
     */
    public static MemoryCommitter
            memoryCommitter(@NonNull CrawlerConfig config) {
        return (MemoryCommitter) config
                .getCommitters()
                .stream()
                .filter(MemoryCommitter.class::isInstance)
                .findFirst()
                .orElse(null);
    }

    public static HttpClientFetcher
            firstHttpFetcher(@NonNull Crawler crawler) {
        return (HttpClientFetcher) crawler
                .getCrawlConfig()
                .getFetchers()
                .get(0);
    }

    public static HttpClientFetcherConfig firstHttpFetcherConfig(
            @NonNull CrawlerConfig crawlConfig) {
        return ((HttpClientFetcher) crawlConfig
                .getFetchers().get(0)).getConfiguration();
    }

    public static HttpClientFetcherConfig firstHttpFetcherConfig(
            @NonNull Crawler crawler) {
        return firstHttpFetcher(crawler).getConfiguration();
    }

    public static Set<String> sortedRequestReferences(MemoryCommitter c) {
        return c.getAllRequests().stream()
                .map(CommitterRequest::getReference)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public static Set<String> sortedUpsertReferences(MemoryCommitter c) {
        return c.getUpsertRequests().stream()
                .map(UpsertRequest::getReference)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public static Set<String> sortedDeleteReferences(MemoryCommitter c) {
        return c.getDeleteRequests().stream()
                .map(DeleteRequest::getReference)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public static String lastSortedRequestReference(MemoryCommitter c) {
        return sortedRequestReferences(c).stream()
                .reduce((first, second) -> second)
                .orElse(null);
    }

    public static String lastSortedUpsertReference(MemoryCommitter c) {
        return sortedUpsertReferences(c).stream()
                .reduce((first, second) -> second)
                .orElse(null);
    }

    public static String lastSortedDeleteReference(MemoryCommitter c) {
        return sortedDeleteReferences(c).stream()
                .reduce((first, second) -> second)
                .orElse(null);
    }

    public static void ignoreAllIgnorables(Crawler crawler) {
        ignoreAllIgnorables(
                (WebCrawlerConfig) crawler.getCrawlConfig());
    }

    public static void ignoreAllIgnorables(WebCrawlerConfig config) {
        config.setCanonicalLinkDetector(null)
                .setRobotsMetaProvider(null)
                .setRobotsTxtProvider(null)
                .setSitemapLocator(null)
                .setSitemapResolver(null);
    }

    public static DelayResolver delayResolver(long ms) {
        return Configurable.configure(new GenericDelayResolver(),
                c -> c.setDefaultDelay(Duration.ofMillis(ms)));
    }

    public static ZonedDateTime daysAgo(int days) {
        return ZonedDateTime.now().minusDays(days).withNano(0);
    }

    public static String daysAgoRFC(int days) {
        return rfcFormat(daysAgo(days));
    }

    public static String rfcFormat(ZonedDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }

    public static String docText(Doc doc) {
        return toString(doc.getInputStream());
    }

    public static String docText(UpsertRequest doc) {
        return toString(doc.getContent());
    }

    public static String toString(InputStream is) {
        try {
            return IOUtils.toString(is, UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String resourceAsString(String resourcePath) {
        return toString(WebTestUtil.class
                .getResourceAsStream(resourcePath));
    }

    @SafeVarargs
    private static <T> Randomizer<T> randomOneOf(T... values) {
        return () -> randomOneOfValues(values);
    }

    @SafeVarargs
    private static <T> Randomizer<T> randomInstanceOf(
            Class<? extends T>... subtypes) {
        var easyRandom = new EasyRandom();
        return () -> {
            if (subtypes.length == 0)
                return null;
            var index = ThreadLocalRandom.current()
                    .nextInt(subtypes.length);
            return easyRandom.nextObject(subtypes[index]);
        };
    }

    @SafeVarargs
    private static <T> T randomOneOfValues(T... values) {
        if (ArrayUtils.isEmpty(values)) {
            return null;
        }
        return values[new Random().nextInt(values.length - 1)];
    }

    private static EasyRandom createRandomizer() {
        return new EasyRandom(new EasyRandomParameters()
                .seed(System.currentTimeMillis())
                .collectionSizeRange(1, 5)
                .randomizationDepth(5)
                .scanClasspathForConcreteTypes(false)
                .overrideDefaultInitialization(true)
                .randomize(
                        File.class,
                        () -> new File(new StringRandomizer(
                                100).getRandomValue()))
                .randomize(
                        Path.class,
                        () -> Path.of(new StringRandomizer(
                                100).getRandomValue()))
                .randomize(
                        Long.class,
                        () -> Math.abs(new LongRandomizer()
                                .getRandomValue()))
                .randomize(
                        Integer.class,
                        () -> Math.abs(
                                new IntegerRandomizer()
                                        .getRandomValue()))
                .randomize(ImporterConfig.class,
                        ImporterConfig::new)
                .randomize(
                        UpsertRequest.class,
                        () -> new UpsertRequest(
                                new StringRandomizer(
                                        100).getRandomValue(),
                                new Properties(),
                                new NullInputStream()))
                .randomize(
                        DeleteRequest.class,
                        () -> new DeleteRequest(
                                new StringRandomizer(
                                        100).getRandomValue(),
                                new Properties()))
                .randomize(Committer.class,
                        MemoryCommitter::new)
                .randomize(
                        SpoiledReferenceStrategizer.class,
                        GenericSpoiledReferenceStrategizer::new)
                .randomize(
                        AtomicBoolean.class,
                        () -> new AtomicBoolean(
                                new BooleanRandomizer()
                                        .getRandomValue()))
                .randomize(
                        UrlScopeResolver.class,
                        GenericUrlScopeResolver::new)
                .randomize(
                        WebUrlNormalizer.class,
                        GenericUrlNormalizer::new)
                .randomize(
                        CanonicalLinkDetector.class,
                        GenericCanonicalLinkDetector::new)
                .randomize(
                        RobotsMetaProvider.class,
                        StandardRobotsMetaProvider::new)
                .randomize(
                        SitemapLocator.class,
                        GenericSitemapLocator::new)
                .randomize(
                        ReferenceFilter.class,
                        randomInstanceOf(
                                ExtensionReferenceFilter.class,
                                GenericReferenceFilter.class,
                                SegmentCountUrlFilter.class))
                .randomize(
                        MetadataFilter.class,
                        randomInstanceOf(
                                ExtensionReferenceFilter.class,
                                GenericReferenceFilter.class,
                                GenericMetadataFilter.class,
                                SegmentCountUrlFilter.class))
                .randomize(
                        DocumentFilter.class,
                        randomInstanceOf(
                                ExtensionReferenceFilter.class,
                                GenericReferenceFilter.class,
                                GenericMetadataFilter.class,
                                SegmentCountUrlFilter.class))
                .randomize(
                        MetadataChecksummer.class,
                        randomInstanceOf(
                                GenericMetadataChecksummer.class,
                                LastModifiedMetadataChecksummer.class))
                .randomize(
                        DocumentChecksummer.class,
                        Md5DocumentChecksummer::new)
                .randomize(ClusterConnector.class,
                        randomInstanceOf(
                                HazelcastClusterConnector.class))

                .excludeType(SitemapResolver.class::equals)
                .excludeType(DocumentConsumer.class::equals)
                .excludeType(FeaturedImageResolver.class::equals)
                .excludeType(RecrawlableResolver.class::equals)
                .excludeType(ReferencesProvider.class::equals)
                .excludeType(BiPredicate.class::equals)
                .excludeType(Class.class::equals)
                .excludeType(HttpClientFetcherConfig.class::equals)

                .randomize(Charset.class,
                        () -> StandardCharsets.UTF_8)
                .randomize(CircularRange.class, () -> {
                    int a = new NumberRandomizer()
                            .getRandomValue();
                    int b = new NumberRandomizer()
                            .getRandomValue();
                    return CircularRange.between(
                            Math.min(a, b),
                            Math.max(a, b));
                })
                .randomize(
                        CachedInputStream.class,
                        CachedInputStream::nullInputStream)
                .randomize(Fetcher.class,
                        HttpClientFetcher::new)
                .randomize(
                        RobotsTxtProvider.class,
                        StandardRobotsTxtProvider::new)
                .randomize(
                        Pattern.class,
                        () -> Pattern.compile(
                                new StringRandomizer(
                                        20).getRandomValue()))
                .randomize(DelayResolver.class, () -> {
                    var resolv = new GenericDelayResolver();
                    resolv.getConfiguration()
                            .setScope(DelayResolverScope.CRAWLER);
                    return resolv;
                })
                .randomize(DomLinkExtractor.class, () -> {
                    var extractor = new DomLinkExtractor();
                    extractor.getConfiguration()
                            .addLinkSelector(
                                    "text");
                    return extractor;
                })
                .randomize(LinkExtractor.class, () -> {
                    var extractor = new DomLinkExtractor();
                    extractor.getConfiguration()
                            .addLinkSelector(
                                    "text");
                    return extractor;
                })
                .randomize(
                        f -> "cookieSpec".equals(
                                f.getName()),
                        () -> CookieSpec.STRICT)
                .randomize(
                        named(HttpAuthConfig.Fields.method)
                                .and(ofType(HttpAuthMethod.class))
                                .and(inClass(HttpAuthConfig.class)),
                        randomOneOf(HttpAuthMethod
                                .values())));
    }
}
