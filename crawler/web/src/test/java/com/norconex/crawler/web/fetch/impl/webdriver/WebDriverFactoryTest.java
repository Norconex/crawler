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
package com.norconex.crawler.web.fetch.impl.webdriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;

import com.norconex.crawler.core.CrawlerException;

class WebDriverFactoryTest {

    @Test
    void testCreateReturnsHtmlUnitDriver() {
        var cfg = new WebDriverFetcherConfig()
                .setBrowser(WebDriverBrowser.CHROME)
                .setUseHtmlUnit(true);

        var driver = WebDriverFactory.create(cfg);
        try {
            assertThat(driver).isInstanceOf(HtmlUnitDriver.class);
        } finally {
            driver.quit();
        }
    }

    @Test
    void testCreateSessionUsesSnifferAndFirefoxOptions() throws Exception {
        var sniffer = mock(HttpSniffer.class);

        var cfg = new WebDriverFetcherConfig()
                .setBrowser(WebDriverBrowser.FIREFOX)
                .setUseHtmlUnit(true)
                .setHttpSniffer(sniffer)
                .setCapabilities(Map.of("custom.cap", "value"));
        cfg.getArguments().addAll(List.of("-private-window"));

        var session = WebDriverFactory.createSession(cfg);
        try {
            assertThat(session.driver()).isInstanceOf(HtmlUnitDriver.class);
            verify(sniffer).configureBrowser(eq(WebDriverBrowser.FIREFOX),
                    any());
        } finally {
            session.close();
        }
    }

    @Test
    void testCreateSessionUnsupportedHtmlUnitBrowserThrows() {
        var cfg = new WebDriverFetcherConfig()
                .setBrowser(WebDriverBrowser.SAFARI)
                .setUseHtmlUnit(true);

        assertThatThrownBy(() -> WebDriverFactory.createSession(cfg))
                .isInstanceOf(CrawlerException.class)
                .hasMessageContaining("Unsupported HtmlUnit browser version");
    }
}
