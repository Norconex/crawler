/* Copyright 2019-2024 Norconex Inc.
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

import com.norconex.importer.handler.BaseDocHandlerConfig;

import com.norconex.commons.lang.convert.DimensionConverter;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.ImporterConfig;
import com.norconex.importer.handler.CommonMatchers;
import com.norconex.importer.handler.CommonRestrictions;

import lombok.Data;
import lombok.experimental.Accessors;

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
@Accessors(chain = true)
public class ImageTransformerConfig extends BaseDocHandlerConfig {

    public static final String DEFAULT_TARGET_FORMAT = "png";

    //TODO Maybe: default == null == keep same as source,
    // derived from detected content-type
    private String targetFormat = DEFAULT_TARGET_FORMAT;
    private Double rotation;
    private final Scale scale = new Scale();
    private final Crop crop = new Crop();

    /**
     * The matcher of content types to apply transformation on. No attempt to
     * transform documents of any other content types will be made. Default is
     * {@link CommonMatchers#IMAGE_IO_CONTENT_TYPES}.
     * @param contentTypeMatcher content type matcher
     * @return content type matcher
     */
    private final TextMatcher contentTypeMatcher =
            CommonMatchers.imageIOStandardContentTypes();

    /**
     * The matcher of content types to apply transformation on. No attempt to
     * transform documents of any other content types will be made. Default is
     * {@link CommonMatchers#IMAGE_IO_CONTENT_TYPES}.
     * @param contentTypeMatcher content type matcher
     * @return this
     */
    public ImageTransformerConfig setContentTypeMatcher(TextMatcher matcher) {
        contentTypeMatcher.copyFrom(matcher);
        return this;
    }

    @Data
    public static class Scale {
        private boolean stretch;
        private Double factor;
        private Integer height;
        private Integer width;
    }

    @Data
    public static class Crop {
        private int x;
        private int y;
        private Integer height;
        private Integer width;
    }
}
