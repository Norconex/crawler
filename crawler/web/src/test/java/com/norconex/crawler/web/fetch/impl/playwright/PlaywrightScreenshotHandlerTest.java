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
package com.norconex.crawler.web.fetch.impl.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.norconex.crawler.core.junit.WithLogLevel;
import com.norconex.crawler.web.fetch.util.AbstractScreenshotHandler;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.io.CachedStreamFactory;
import com.norconex.crawler.web.fetch.util.DocImageHandlerConfig.DirStructure;
import com.norconex.crawler.web.fetch.util.DocImageHandlerConfig.Target;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class PlaywrightScreenshotHandlerTest {

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    @Test
    void testNoArgConstructorCreatesHandler() {
        assertThat(new PlaywrightScreenshotHandler()).isNotNull();
    }

    @Test
    void testStreamFactoryConstructorCreatesHandler() {
        var factory = new CachedStreamFactory();
        assertThat(new PlaywrightScreenshotHandler(factory)).isNotNull();
    }

    // -------------------------------------------------------------------------
    // captureScreenshotBytes — no selector
    // -------------------------------------------------------------------------

    @Test
    void testCaptureScreenshotBytesWithoutSelector() {
        var expected = new byte[] { 1, 2, 3 };
        var page = mock(Page.class);
        when(page.screenshot()).thenReturn(expected);

        var handler = new PlaywrightScreenshotHandler();
        // no CSS selector configured

        var result = handler.captureScreenshotBytes(page);

        assertThat(result).isEqualTo(expected);
        verify(page).screenshot();
    }

    // -------------------------------------------------------------------------
    // captureScreenshotBytes — with selector
    // -------------------------------------------------------------------------

    @Test
    void testCaptureScreenshotBytesWithSelector() {
        var expected = new byte[] { 4, 5, 6 };
        var locator = mock(Locator.class);
        when(locator.screenshot()).thenReturn(expected);

        var page = mock(Page.class);
        when(page.locator(".my-class")).thenReturn(locator);

        var handler = new PlaywrightScreenshotHandler();
        handler.getConfiguration().setCssSelector(".my-class");

        var result = handler.captureScreenshotBytes(page);

        assertThat(result).isEqualTo(expected);
        verify(page).locator(".my-class");
        verify(locator).screenshot();
    }

    // -------------------------------------------------------------------------
    // takeScreenshot — exception is swallowed
    // -------------------------------------------------------------------------

    @Test
    @WithLogLevel(value = "OFF", classes = AbstractScreenshotHandler.class) // tests expect exceptions
    void testTakeScreenshotSwallowsException() {
        var page = mock(Page.class);
        when(page.screenshot()).thenThrow(new RuntimeException("boom"));
        var doc = mock(Doc.class);

        var handler = new PlaywrightScreenshotHandler();
        assertThatNoException()
                .isThrownBy(() -> handler.takeScreenshot(page, doc));
    }

    // -------------------------------------------------------------------------
    // serialization round-trip
    // -------------------------------------------------------------------------

    @Test
    void testWriteRead() {
        var h = new PlaywrightScreenshotHandler();
        h.getConfiguration()
                .setCssSelector("body .content")
                .setImageFormat("jpg")
                .setTargetDir(Paths.get("/tmp/blah"))
                .setTargetDirStructure(DirStructure.URL2PATH)
                .setTargetDirField("docImage")
                .setTargetMetaField("docMeta")
                .setTargets(List.of(Target.DIRECTORY, Target.METADATA));

        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(h));
    }
}
