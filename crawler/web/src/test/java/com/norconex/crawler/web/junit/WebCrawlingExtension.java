/* Copyright 2024-2026 Norconex Inc.
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
package com.norconex.crawler.web.junit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetcher;
import com.norconex.crawler.web.junit.WebCrawlingTest.DefaultWebCrawlerConfigModifier;
import com.norconex.crawler.web.stubs.CrawlerConfigStubs;

/**
 * JUnit 5 extension backing the {@link WebCrawlingTest} annotation.
 * Resolves {@link WebCrawlerConfig} and {@link CrawlerContext} parameters
 * for test methods.
 */
public class WebCrawlingExtension implements ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(WebCrawlingExtension.class);
    private static final String CONFIG_KEY = "web-crawl-config";

    @Override
    public boolean supportsParameter(
            ParameterContext paramCtx, ExtensionContext extCtx)
            throws ParameterResolutionException {
        var type = paramCtx.getParameter().getType();
        return type == WebCrawlerConfig.class || type == CrawlerContext.class;
    }

    @Override
    public Object resolveParameter(
            ParameterContext paramCtx, ExtensionContext extCtx)
            throws ParameterResolutionException {
        var config = extCtx.getStore(NAMESPACE).computeIfAbsent(
                CONFIG_KEY, k -> buildConfig(extCtx), WebCrawlerConfig.class);
        var type = paramCtx.getParameter().getType();

        if (type == WebCrawlerConfig.class) {
            return config;
        }
        if (type == CrawlerContext.class) {
            return mockCrawlContext(config);
        }
        return null;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private WebCrawlerConfig buildConfig(ExtensionContext extCtx) {
        var annotation = findAnnotation(extCtx);

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("nx-webcrawl-test-");
        } catch (IOException e) {
            throw new ParameterResolutionException(
                    "Cannot create temp directory for test", e);
        }
        // Register temp directory cleanup after the test method completes
        extCtx.getStore(NAMESPACE).put(
                CONFIG_KEY + ".workDir",
                (AutoCloseable) () -> {
                    try (var walk = Files.walk(tempDir)) {
                        walk.sorted(Comparator.reverseOrder())
                                .forEach(p -> p.toFile().delete());
                    } catch (IOException e) {
                        // ignore cleanup errors
                    }
                });

        var config = CrawlerConfigStubs.memoryCrawlerConfig(tempDir);

        // Apply modifier (default = DefaultWebCrawlerConfigModifier sets delay=0)
        Class<? extends Consumer> modifierClass =
                annotation != null
                        ? annotation.configModifier()
                        : DefaultWebCrawlerConfigModifier.class;
        try {
            ((Consumer<WebCrawlerConfig>) modifierClass
                    .getDeclaredConstructor().newInstance()).accept(config);
        } catch (Exception e) {
            throw new ParameterResolutionException(
                    "Cannot instantiate configModifier: " + modifierClass, e);
        }

        // Apply YAML config string from annotation if provided
        if (annotation != null && StringUtils.isNotBlank(annotation.config())) {
            BeanMapper.DEFAULT.read(
                    config, new StringReader(annotation.config()), Format.YAML);
        }

        return config;
    }

    private WebCrawlingTest findAnnotation(ExtensionContext extCtx) {
        // Method-level annotation takes precedence over class-level
        var methodAnn = extCtx.getRequiredTestMethod()
                .getAnnotation(WebCrawlingTest.class);
        if (methodAnn != null) {
            return methodAnn;
        }
        return extCtx.getRequiredTestClass()
                .getAnnotation(WebCrawlingTest.class);
    }

    /**
     * Creates a Mockito mock of {@link CrawlerContext} that returns the
     * given config from {@code getCrawlConfig()} and an initialized
     * {@link HttpClientFetcher} from {@code getFetcher()}.
     */
    private CrawlerContext mockCrawlContext(WebCrawlerConfig config) {
        var ctx = mock(CrawlerContext.class);
        when(ctx.getCrawlConfig()).thenReturn(config);

        // Create and initialize an HTTP fetcher (calls fetcherStartup internally)
        var fetcher = new HttpClientFetcher();
        fetcher.accept(CrawlerEvent.builder()
                .name(CrawlerEvent.CRAWLER_CRAWL_BEGIN)
                .source(mock(CrawlerSession.class))
                .build());
        when(ctx.getFetcher()).thenReturn(fetcher);
        return ctx;
    }
}
