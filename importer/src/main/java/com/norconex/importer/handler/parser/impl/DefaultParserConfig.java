/* Copyright 2010-2026 Norconex Inc.
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
package com.norconex.importer.handler.parser.impl;

import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.norconex.importer.handler.BaseDocHandlerConfig;

import lombok.Data;
import lombok.NonNull;
import lombok.experimental.Accessors;

/**
 * Parser class when no other handlers are specified.
 * The importer uses Apache Tika parser in its own way with default
 * settings common for most senarios.
 * If you want to use and configure Tika yourself, use
 * TikaParser.
 */
@Data
@Accessors(chain = true)
public class DefaultParserConfig extends BaseDocHandlerConfig {
    private Path errorsSaveDir;

    @JsonProperty("ocr")
    private final OcrConfig ocrConfig = new OcrConfig();

    @NonNull
    @JsonProperty("embedded")
    private final EmbeddedConfig embeddedConfig = new EmbeddedConfig();

    @JsonProperty("grobid")
    private final GrobidConfig grobidConfig = new GrobidConfig();
}
