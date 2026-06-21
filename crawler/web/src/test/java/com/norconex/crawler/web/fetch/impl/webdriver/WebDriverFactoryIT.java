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
package com.norconex.crawler.web.fetch.impl.webdriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;

import com.norconex.crawler.core.CrawlerException;

@Timeout(300)
class WebDriverFactoryIT {

    @Test
    void testCreate() throws Exception {
        Assumptions.assumeTrue(isLocalFirefoxAvailable(),
                "Local Firefox not detected.");

        var config = new WebDriverFetcherConfig();
        config.setBrowser(WebDriverBrowser.FIREFOX);
        try (var session = WebDriverFactory.createSession(config)) {
            var driver = session.driver();
            assertThat(driver).isNotNull();
            assertThat(driver.getClass().getSimpleName())
                    .containsIgnoringCase("firefox");
        }
    }

    @Test
    void testCreateSessionWithHtmlUnitChrome() throws Exception {
        var config = new WebDriverFetcherConfig();
        config.setBrowser(WebDriverBrowser.CHROME);
        config.setUseHtmlUnit(true);
        try (var session = WebDriverFactory.createSession(config)) {
            assertThat(session.driver())
                    .isNotNull()
                    .isInstanceOf(HtmlUnitDriver.class);
        }
    }

    @Test
    void testCreateSessionWithHtmlUnitFirefox() throws Exception {
        var config = new WebDriverFetcherConfig();
        config.setBrowser(WebDriverBrowser.FIREFOX);
        config.setUseHtmlUnit(true);
        try (var session = WebDriverFactory.createSession(config)) {
            assertThat(session.driver())
                    .isNotNull()
                    .isInstanceOf(HtmlUnitDriver.class);
        }
    }

    @Test
    void testCreateWithHtmlUnitChromeReturnsDriver() {
        var config = new WebDriverFetcherConfig();
        config.setBrowser(WebDriverBrowser.CHROME);
        config.setUseHtmlUnit(true);
        var driver = WebDriverFactory.create(config);
        try {
            assertThat(driver).isNotNull().isInstanceOf(HtmlUnitDriver.class);
        } finally {
            driver.quit();
        }
    }

    @Test
    void testCreateSessionWithHtmlUnitUnsupportedBrowserThrows() {
        var config = new WebDriverFetcherConfig();
        config.setBrowser(WebDriverBrowser.SAFARI);
        config.setUseHtmlUnit(true);
        assertThatExceptionOfType(CrawlerException.class)
                .isThrownBy(() -> WebDriverFactory.createSession(config));
    }

    @Test
    void testCreateSessionWithCapabilitiesDoesNotThrow() throws Exception {
        var config = new WebDriverFetcherConfig();
        config.setBrowser(WebDriverBrowser.CHROME);
        config.setUseHtmlUnit(true);
        config.setCapabilities(Map.of("se:custom-cap", "true"));
        try (var session = WebDriverFactory.createSession(config)) {
            assertThat(session.driver()).isNotNull();
        }
    }

    @Test
    void testCreateSessionWithArgumentsChrome() throws Exception {
        var config = new WebDriverFetcherConfig();
        config.setBrowser(WebDriverBrowser.CHROME);
        config.setUseHtmlUnit(true);
        config.getArguments().add("--headless");
        try (var session = WebDriverFactory.createSession(config)) {
            assertThat(session.driver()).isNotNull();
        }
    }

    @Test
    void testCreateSessionWithArgumentsFirefox() throws Exception {
        var config = new WebDriverFetcherConfig();
        config.setBrowser(WebDriverBrowser.FIREFOX);
        config.setUseHtmlUnit(true);
        config.getArguments().add("--headless");
        try (var session = WebDriverFactory.createSession(config)) {
            assertThat(session.driver()).isNotNull();
        }
    }

    private boolean isLocalFirefoxAvailable() {
        return AbstractWebDriverHttpFetcherIT.isLocalBrowserDetectable(
                WebDriverBrowser.FIREFOX);
    }
}
