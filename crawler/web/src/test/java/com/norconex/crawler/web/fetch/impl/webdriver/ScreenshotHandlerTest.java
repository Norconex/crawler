/* Copyright 2020-2026 Norconex Inc.
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
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.WebElement;

import com.norconex.crawler.core.junit.WithLogLevel;
import com.norconex.crawler.web.fetch.util.AbstractScreenshotHandler;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.crawler.web.fetch.util.DocImageHandlerConfig.DirStructure;
import com.norconex.crawler.web.fetch.util.DocImageHandlerConfig.Target;
import com.norconex.crawler.web.mocks.MockWebDriver;
import com.norconex.crawler.web.stubs.CrawlDocStubs;
import com.norconex.importer.doc.Doc;

import java.nio.file.Path;

@Timeout(30)
@WithLogLevel(value = "OFF", classes = AbstractScreenshotHandler.class) // tests expect exceptions
class ScreenshotHandlerTest {

    @Test
    void testWriteRead() {
        var h = new WebDriverScreenshotHandler();
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

    @Test
    void testExceptionSwallow() {
        var driver = mock(MockWebDriver.class);
        when(driver.getScreenshotAs(OutputType.BYTES))
                .thenThrow(UnsupportedOperationException.class);
        var doc = mock(Doc.class);
        var h = spy(new WebDriverScreenshotHandler());
        assertThatNoException().isThrownBy(() -> {
            h.takeScreenshot(driver, doc);
        });
        verify(h, times(0)).getConfiguration();
    }

    @Test
    void testCaptureScreenshotBytesNoSelector() throws Exception {
        var expectedBytes = new byte[] { 1, 2, 3, 4 };
        var driver = mock(MockWebDriver.class);
        when(driver.getScreenshotAs(OutputType.BYTES))
                .thenReturn(expectedBytes);

        var handler = new WebDriverScreenshotHandler();
        // default config has no CSS selector
        var result = handler.captureScreenshotBytes(driver);

        assertThat(result).isEqualTo(expectedBytes);
    }

    @Test
    void testCaptureScreenshotBytesWithCssSelector(@TempDir Path tempDir)
            throws Exception {
        // Build a valid 20x20 PNG in memory
        var img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 20, 20);
        g.dispose();
        var baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        var pngBytes = baos.toByteArray();

        var element = mock(WebElement.class);
        when(element.getLocation()).thenReturn(new Point(0, 0));
        when(element.getSize()).thenReturn(new Dimension(10, 10));

        var driver = mock(MockWebDriver.class);
        when(driver.getScreenshotAs(OutputType.BYTES)).thenReturn(pngBytes);
        when(driver.findElement(any())).thenReturn(element);

        var handler = new WebDriverScreenshotHandler();
        handler.getConfiguration().setCssSelector("body");

        var result = handler.captureScreenshotBytes(driver);

        assertThat(result).isNotEmpty();
    }

    @Test
    void testTakeScreenshotSuccessWithMetadataTarget(@TempDir Path tempDir)
            throws Exception {
        // Build a minimal valid PNG
        var img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        var baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        var pngBytes = baos.toByteArray();

        var driver = mock(MockWebDriver.class);
        when(driver.getScreenshotAs(OutputType.BYTES)).thenReturn(pngBytes);

        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var handler = new WebDriverScreenshotHandler();
        handler.getConfiguration()
                .setTargets(List.of(Target.METADATA))
                .setImageFormat("png");

        assertThatNoException().isThrownBy(
                () -> handler.takeScreenshot(driver, doc));
    }
}
