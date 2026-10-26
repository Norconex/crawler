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
package com.norconex.crawler.web.fetch.impl.playwright;

import static com.norconex.commons.lang.config.Configurable.configure;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.awt.Dimension;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.net.ProxySettings;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.crawler.core.doc.operations.filter.impl.GenericReferenceFilter;
import com.norconex.crawler.web.fetch.impl.playwright.PlaywrightFetcherConfig.WaitElementType;

@Timeout(30)
class PlaywrightFetcherConfigTest {

    @Test
    void testWriteReadFetcher() {
        var f = new PlaywrightFetcher();
        var c = f.getConfiguration();

        c.setBrowser(PlaywrightBrowser.FIREFOX);
        c.setHeadless(false);
        c.setExecutablePath(Paths.get("/some/browser/path"));
        c.setIgnoreHttpsErrors(true);
        c.setUserAgent("TestBot/1.0 (+https://example.com/bot)");
        c.setSlowMo(Duration.ofMillis(100));
        c.setPageLoadTimeout(Duration.ofSeconds(15));
        c.setEarlyPageScript("document.title = 'early';");
        c.setLatePageScript("document.title = 'late';");
        c.setWaitForElementType(WaitElementType.ID);
        c.setWaitForElementSelector("main-content");
        c.setWaitForElementTimeout(Duration.ofSeconds(5));
        c.setWindowSize(new Dimension(1280, 720));
        c.setBrowserMaxNavigations(50);
        c.setBrowserMaxAge(Duration.ofMinutes(30));
        c.setCleanupInterval(Duration.ofSeconds(20));
        c.setArgs(List.of("--disable-gpu", "--no-sandbox"));
        c.setProxySettings(
                new ProxySettings("proxy.example.com", 3128));

        c.setReferenceFilters(List.of(configure(
                new GenericReferenceFilter(),
                cfg -> cfg.setValueMatcher(TextMatcher.regex("https?://.*")))));

        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(f));
    }

    @Test
    void testWriteReadDefaults() {
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT
                        .assertWriteRead(new PlaywrightFetcher()));
    }

    @Test
    void testWriteReadAllBrowsers() {
        for (var browser : PlaywrightBrowser.values()) {
            var f = new PlaywrightFetcher();
            f.getConfiguration().setBrowser(browser);
            assertThatNoException().isThrownBy(
                    () -> BeanMapper.DEFAULT.assertWriteRead(f));
        }
    }

    @Test
    void testWriteReadAllWaitElementTypes() {
        for (var type : WaitElementType.values()) {
            var f = new PlaywrightFetcher();
            f.getConfiguration()
                    .setWaitForElementType(type)
                    .setWaitForElementSelector("someSelector");
            assertThatNoException().isThrownBy(
                    () -> BeanMapper.DEFAULT.assertWriteRead(f));
        }
    }
}
