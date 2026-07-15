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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.safari.SafariOptions;

import com.norconex.commons.lang.net.Host;

class WebDriverBrowserTest {

    // -------------------------------------------------------------------------
    // of(String)
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(
        {
                "chrome,  CHROME",
                "CHROME,  CHROME",
                "Chrome,  CHROME",
                "firefox, FIREFOX",
                "FIREFOX, FIREFOX",
                "edge,    EDGE",
                "EDGE,    EDGE",
                "safari,  SAFARI",
                "opera,   OPERA",
                "custom,  CUSTOM"
        }
    )
    void testOfCaseInsensitive(String name, String expectedEnum) {
        assertThat(WebDriverBrowser.of(name))
                .isEqualTo(WebDriverBrowser.valueOf(expectedEnum));
    }

    @Test
    void testOfUnknownReturnsNull() {
        assertThat(WebDriverBrowser.of("unknown")).isNull();
    }

    @Test
    void testOfNullReturnsNull() {
        assertThat(WebDriverBrowser.of(null)).isNull();
    }

    // -------------------------------------------------------------------------
    // createOptions
    // -------------------------------------------------------------------------

    @Test
    void testCreateOptionsChromeReturnsNonNull() {
        var location = new WebDriverLocation(null, null, null);
        var options = WebDriverBrowser.CHROME.createOptions(location);
        assertThat(options).isNotNull().isInstanceOf(ChromeOptions.class);
    }

    @Test
    void testCreateOptionsFirefoxReturnsNonNull() {
        var location = new WebDriverLocation(null, null, null);
        var options = WebDriverBrowser.FIREFOX.createOptions(location);
        assertThat(options).isNotNull().isInstanceOf(FirefoxOptions.class);
    }

    @Test
    void testCreateOptionsEdgeReturnsNonNull() {
        var location = new WebDriverLocation(null, null, null);
        var options = WebDriverBrowser.EDGE.createOptions(location);
        assertThat(options).isNotNull().isInstanceOf(EdgeOptions.class);
    }

    @Test
    void testCreateOptionsSafariReturnsNonNull() {
        var location = new WebDriverLocation(null, null, null);
        var options = WebDriverBrowser.SAFARI.createOptions(location);
        assertThat(options).isNotNull();
    }

    @Test
    void testCreateOptionsOperaReturnsNonNull() {
        var location = new WebDriverLocation(null, null, null);
        var options = WebDriverBrowser.OPERA.createOptions(location);
        assertThat(options).isNotNull();
    }

    @Test
    void testCreateOptionsCustomReturnsNonNull() {
        var location = new WebDriverLocation(null, null, null);
        var options = WebDriverBrowser.CUSTOM.createOptions(location);
        assertThat(options)
                .isNotNull()
                .isInstanceOf(WebDriverBrowser.CustomDriverOptions.class);
    }

    @Test
    void testCreateOptionsChromeWithBrowserPathReturnsNonNull() {
        var location = new WebDriverLocation(null, Path.of("chrome"), null);
        var options = WebDriverBrowser.CHROME.createOptions(location);
        assertThat(options).isNotNull().isInstanceOf(ChromeOptions.class);
    }

    @Test
    void testCreateOptionsFirefoxWithBrowserPathReturnsNonNull() {
        var location = new WebDriverLocation(null, Path.of("firefox"), null);
        var options = WebDriverBrowser.FIREFOX.createOptions(location);
        assertThat(options).isNotNull().isInstanceOf(FirefoxOptions.class);
    }

    @Test
    void testCreateOptionsEdgeWithBrowserPathReturnsNonNull() {
        var location = new WebDriverLocation(null, Path.of("edge"), null);
        var options = WebDriverBrowser.EDGE.createOptions(location);
        assertThat(options).isNotNull().isInstanceOf(EdgeOptions.class);
    }

    @ParameterizedTest
    @CsvSource({ "CHROME", "FIREFOX", "EDGE", "SAFARI", "OPERA", "CUSTOM" })
    void testDriverFactoryLambdaIsInvoked(String name) throws Exception {
        var browser = WebDriverBrowser.valueOf(name);
        var location = new WebDriverLocation(Path.of("missing-driver.exe"),
                null, null);
        var options = browser.createOptions(location);

        var field = WebDriverBrowser.class.getDeclaredField("driverFactory");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var driverFactory =
                (BiFunction<WebDriverLocation, Object, ?>) field.get(browser);

        try {
            driverFactory.apply(location, options);
        } catch (Throwable ignored) {
            // Driver availability is outside the scope of this test.
        }
    }

    // -------------------------------------------------------------------------
    // configureProxy
    // -------------------------------------------------------------------------

    @Test
    void testConfigureProxyChrome() {
        var location = new WebDriverLocation(null, null, null);
        var options = WebDriverBrowser.CHROME.createOptions(location);
        var host = new Host("proxy.example.com", 3128);
        assertThatCode(
                () -> WebDriverBrowser.CHROME.configureProxy(options, host))
                        .doesNotThrowAnyException();
    }

    @Test
    void testConfigureProxyFirefox() {
        var location = new WebDriverLocation(null, null, null);
        var options = WebDriverBrowser.FIREFOX.createOptions(location);
        var host = new Host("proxy.example.com", 3128);
        assertThatCode(
                () -> WebDriverBrowser.FIREFOX.configureProxy(options, host))
                        .doesNotThrowAnyException();
    }

    @Test
    void testConfigureProxyEdge() {
        var location = new WebDriverLocation(null, null, null);
        var options = WebDriverBrowser.EDGE.createOptions(location);
        var host = new Host("proxy.example.com", 3128);
        assertThatCode(
                () -> WebDriverBrowser.EDGE.configureProxy(options, host))
                        .doesNotThrowAnyException();
    }

    @Test
    void testConfigureProxyCustom() {
        var location = new WebDriverLocation(null, null, null);
        var options = WebDriverBrowser.CUSTOM.createOptions(location);
        var host = new Host("proxy.example.com", 3128);
        assertThatCode(
                () -> WebDriverBrowser.CUSTOM.configureProxy(options, host))
                        .doesNotThrowAnyException();
    }

    @Test
    void testConfigureProxySafari() {
        var location = new WebDriverLocation(null, null, null);
        var options = (SafariOptions) WebDriverBrowser.SAFARI
                .createOptions(location);
        var host = new Host("proxy.example.com", 3128);

        assertThatCode(
                () -> WebDriverBrowser.SAFARI.configureProxy(options, host))
                        .doesNotThrowAnyException();
    }

    @Test
    void testConfigureProxyOpera() {
        var location = new WebDriverLocation(null, null, null);
        var options = WebDriverBrowser.OPERA.createOptions(location);
        var host = new Host("proxy.example.com", 3128);

        assertThatCode(
                () -> WebDriverBrowser.OPERA.configureProxy(options, host))
                        .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // getHtmlUnitBrowser
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = { "CHROME", "FIREFOX", "EDGE" })
    void testGetHtmlUnitBrowserIsNonNullForSupportedBrowsers(String name) {
        var browser = WebDriverBrowser.valueOf(name);
        assertThat(browser.getHtmlUnitBrowser()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = { "SAFARI", "OPERA", "CUSTOM" })
    void testGetHtmlUnitBrowserIsNullForUnsupportedBrowsers(String name) {
        var browser = WebDriverBrowser.valueOf(name);
        assertThat(browser.getHtmlUnitBrowser()).isNull();
    }

    // -------------------------------------------------------------------------
    // CustomDriverOptions
    // -------------------------------------------------------------------------

    @Test
    void testCustomDriverOptionsGetExtraCapabilityAlwaysNull() {
        var options = new WebDriverBrowser.CustomDriverOptions();
        assertThat(options.getCapability("anything")).isNull();
    }

    @Test
    void testCustomDriverOptionsIsNotNull() {
        assertThat(new WebDriverBrowser.CustomDriverOptions()).isNotNull();
    }

    @Test
    void testBuilderBuildCreatesSessionWithDefaultConstructor()
            throws Exception {
        var builder = newBuilder();
        invokeBuilder(builder, "location",
                new WebDriverLocation(null, null, null));
        invokeBuilder(builder, "options",
                new WebDriverBrowser.CustomDriverOptions());
        invokeBuilder(builder, "driverClass", StubWebDriver.class);

        var build = builder.getClass().getDeclaredMethod("build");
        build.setAccessible(true);
        var session = (DriverSession) build.invoke(builder);

        assertThat(session.driver()).isInstanceOf(StubWebDriver.class);
        session.close();
    }

    @Test
    void testBuilderBuildCreatesSessionWithOptionsConstructor()
            throws Exception {
        var builder = newBuilder();
        invokeBuilder(builder, "location",
                new WebDriverLocation(null, null, null));
        invokeBuilder(builder, "options",
                new WebDriverBrowser.CustomDriverOptions());
        invokeBuilder(builder, "driverClass", StubOptionsWebDriver.class);

        var build = builder.getClass().getDeclaredMethod("build");
        build.setAccessible(true);
        var session = (DriverSession) build.invoke(builder);

        assertThat(session.driver()).isInstanceOf(StubOptionsWebDriver.class);
        session.close();
    }

    @Test
    void testBuilderCreateDriverServiceForChrome() throws Exception {
        var service = invokeCreateDriverService(
                org.openqa.selenium.chrome.ChromeDriver.class);
        assertThat(service).isNotNull();
    }

    @Test
    void testBuilderCreateDriverServiceForFirefox() throws Exception {
        var service = invokeCreateDriverService(FirefoxDriver.class);
        assertThat(service).isNotNull();
    }

    @Test
    void testBuilderCreateDriverServiceForEdge() throws Exception {
        var service = invokeCreateDriverService(EdgeDriver.class);
        assertThat(service).isNotNull();
    }

    private Object
            invokeCreateDriverService(Class<? extends WebDriver> driverClass)
                    throws Exception {
        var builder = newBuilder();
        invokeBuilder(builder, "driverClass", driverClass);
        var method =
                builder.getClass().getDeclaredMethod("createDriverService");
        method.setAccessible(true);
        return method.invoke(builder);
    }

    private Object newBuilder() throws Exception {
        Class<?> type = Class.forName(
                WebDriverBrowser.class.getName() + "$WebDriverBuilder");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private void invokeBuilder(Object builder, String methodName, Object arg)
            throws Exception {
        Method method = null;
        for (Method candidate : builder.getClass().getDeclaredMethods()) {
            if (!candidate.getName().equals(methodName)
                    || candidate.getParameterCount() != 1) {
                continue;
            }
            if (candidate.getParameterTypes()[0].isAssignableFrom(
                    arg.getClass())) {
                method = candidate;
                break;
            }
        }
        assertThat(method).isNotNull();
        method.setAccessible(true);
        method.invoke(builder, arg);
    }

    static class StubWebDriver implements WebDriver {
        @Override
        public void get(String url) {
        }

        @Override
        public String getCurrentUrl() {
            return null;
        }

        @Override
        public String getTitle() {
            return null;
        }

        @Override
        public List<WebElement> findElements(By by) {
            return Collections.emptyList();
        }

        @Override
        public WebElement findElement(By by) {
            return null;
        }

        @Override
        public String getPageSource() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public void quit() {
        }

        @Override
        public Set<String> getWindowHandles() {
            return Collections.emptySet();
        }

        @Override
        public String getWindowHandle() {
            return null;
        }

        @Override
        public TargetLocator switchTo() {
            return null;
        }

        @Override
        public Navigation navigate() {
            return null;
        }

        @Override
        public Options manage() {
            return null;
        }
    }

    static class StubOptionsWebDriver extends StubWebDriver {
        public StubOptionsWebDriver(
                WebDriverBrowser.CustomDriverOptions options) {
        }
    }
}
