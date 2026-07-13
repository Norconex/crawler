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
package com.norconex.importer.handler.transformer.impl;

import static org.apache.commons.lang3.ObjectUtils.anyNotNull;
import static org.apache.commons.lang3.ObjectUtils.firstNonNull;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.IOException;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.convert.DimensionConverter;
import com.norconex.commons.lang.img.MutableImage;
import com.norconex.importer.ImporterConfig;
import com.norconex.importer.handler.CommonRestrictions;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.util.MatchUtil;

import lombok.Data;

/**
 * <p>
 * Transforms an image using common image operations.
 * </p>
 * <p>
 * This class should only be used as a pre-parsing handler, on image files.
 * It may also be appropriate to disable parsing of those images if you
 * want to keep the transformed version intact. This can be done with
 * {@link ParseConfig}, obtained via the {@link ImporterConfig}.
 * </p>
 *
 * <h2>Content-types</h2>
 * <p>
 * By default, this filter is restricted to (applies only to) documents matching
 * the restrictions returned by
 * {@link CommonRestrictions#imageIOStandardContentTypes(String)}.
 * You can specify your own content types if you know they represent a supported
 * image.
 * </p>
 *
 * <h2>Image dimension format</h2>
 * <p>
 * For a list of supported image dimension formats, refer to
 * {@link DimensionConverter}.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.transformer.impl.ImageTransformer"
 *      targetFormat="(jpg, png, gif, bmp, wbmp, or other supported format)">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <scale
 *       stretch="[false|true]"
 *       factor="(decimal value ratio factor, default is 1)"
 *       dimension="(target dimension, in pixels, format: [width]x[height])" />
 *
 *   <rotate degrees="(-360 to 360)"/>
 *
 *   <crop
 *       x="(top-left x-axis, default 0)"
 *       y="(top-left y-axis, default 0)"
 *       dimension="(crop dimension, in pixels, format: [width]x[height])"/>
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="ImageTransformer" targetFormat="png">
 *   <scale dimension="400x250" />
 * </handler>
 * }
 * <h4>Usage example:</h4>
 * <p>
 * The above example converts images to PNG while scaling it to a maximum
 * dimension of 400 pixels wide and 250 pixel high.
 * </p>
 *
 * @see ExternalHandler
 */
@SuppressWarnings("javadoc")
@Data
public class ImageTransformer
        implements ConfigurableDocHandler<ImageTransformerConfig> {

    private final ImageTransformerConfig configuration =
            new ImageTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {

        // only proceed if we are dealing with a supported content type
        if (!MatchUtil.matchesContentType(
                configuration.getContentTypeMatcher(), docCtx.contentType())) {
            return true;
        }

        try (var input = docCtx.input().asInputStream();
                var output = docCtx.output().asOutputStream();) {
            var img = new MutableImage(input);
            transformImage(img);
            img.write(output, configuration.getTargetFormat());
        }
        return true;
    }

    public void transformImage(MutableImage image) {
        // Scale
        if (configuration.getScale().getFactor() != null) {
            image.scaleFactor(configuration.getScale().getFactor());
        }
        // If either of height and width is set, we scale. If only
        // one is set, we use the same value for both height and weight.
        // Same for crop below.
        var sheight = configuration.getScale().getHeight();
        var swidth = configuration.getScale().getWidth();
        if (anyNotNull(sheight, swidth)) {
            var dimension = new Dimension(
                    firstNonNull(swidth, sheight),
                    firstNonNull(sheight, swidth));
            if (configuration.getScale().isStretch()) {
                image.stretch(dimension);
            } else {
                image.scale(dimension);
            }
        }

        // Rotate
        if (configuration.getRotation() != null) {
            image.rotate(configuration.getRotation());
        }

        // Crop
        var cheight = configuration.getCrop().getHeight();
        var cwidth = configuration.getCrop().getWidth();
        if (anyNotNull(cheight, cwidth)) {
            image.crop(
                    new Rectangle(
                            configuration.getCrop().getX(),
                            configuration.getCrop().getY(),
                            firstNonNull(cwidth, cheight),
                            firstNonNull(cheight, cwidth)));
        }
    }
}
