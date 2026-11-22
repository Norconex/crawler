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

import com.norconex.importer.handler.BaseDocHandlerConfig;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class TikaParserConfig extends BaseDocHandlerConfig {
    /**
     * Maximum number of top-level documents this handler parses at once.
     * Zero (the default) sets no limit. All documents share the same Tika
     * parser either way; a limit only makes the extra ones wait.
     */
    private int poolSize;

    public TikaParserConfig setPoolSize(int poolSize) {
        if (poolSize < 0) {
            throw new IllegalArgumentException(
                    "poolSize must be zero or greater.");
        }
        this.poolSize = poolSize;
        return this;
    }

    /**
     * The path to a Tika JSON configuration file (Tika 4 no longer supports XML).
     */
    private String tikaConfigFile;
}
