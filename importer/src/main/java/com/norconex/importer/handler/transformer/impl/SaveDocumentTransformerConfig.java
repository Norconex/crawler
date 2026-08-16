/* Copyright 2023 Norconex Inc.
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

import java.nio.file.Path;

import com.norconex.commons.lang.text.StringUtil;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Saves a copy of the document at its current processing state in
 * the specified directory. If no directory is specified, the default is
 * {@value SaveDocumentTransformerConfig#DEFAULT_SAVE_DIR_PATH}.
 * It is recommended to use this tagger as a pre-parse handler if you
 * want to save the original file.
 * </p>
 * <p>
 * Any maximum file path length value below
 * {@value StringUtil#TRUNCATE_HASH_LENGTH} is considered unlimited
 * (the default).
 * </p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/SaveDocumentTransformer">
 *      SaveDocumentTransformer configuration reference</a>
 */
@Data
@Accessors(chain = true)
public class SaveDocumentTransformerConfig extends BaseDocHandlerConfig {

    public static final String DEFAULT_SAVE_DIR_PATH = "./savedDocuments";
    public static final String DEFAULT_SPLIT_PATTERN = "[\\:/]";
    public static final String DEFAULT_DEFAULT_FILE_NAME = "default.file";

    private Path saveDir = Path.of(DEFAULT_SAVE_DIR_PATH);
    private int maxPathLength = -1; // including saveDir, min -1 or 10
    private String dirSplitPattern = DEFAULT_SPLIT_PATTERN;
    private boolean escape;
    private String pathToField;
    private String defaultFileName = DEFAULT_DEFAULT_FILE_NAME;
}
