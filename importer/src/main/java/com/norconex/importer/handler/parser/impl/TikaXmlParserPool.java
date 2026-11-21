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

import java.io.IOException;

import org.apache.tika.exception.TikaException;
import org.apache.tika.utils.XMLReaderUtils;

import lombok.extern.slf4j.Slf4j;

/** Applies the shared XML pool policy once, before default parsing starts. */
@Slf4j
final class TikaXmlParserPool {
    private static Integer initializedSize;

    private TikaXmlParserPool() {
    }

    static synchronized void configure(Integer requested) throws IOException {
        if (requested != null && requested <= 0) {
            throw new IllegalArgumentException(
                    "xmlParserPoolSize must be positive.");
        }
        if (initializedSize != null) {
            if (requested != null && !requested.equals(initializedSize)) {
                throw new IOException("Conflicting JVM-wide xmlParserPoolSize: "
                        + requested + "; already initialized as "
                        + initializedSize);
            }
            if (XMLReaderUtils.getPoolSize() != initializedSize) {
                throw new IOException(
                        "Tika XML parser pool was changed externally.");
            }
            return;
        }
        try {
            if (requested != null
                    && requested != XMLReaderUtils.getPoolSize()) {
                XMLReaderUtils.setPoolSize(requested);
            }
            initializedSize = XMLReaderUtils.getPoolSize();
            LOG.info("Tika XML parser pool size: {} (JVM-wide).",
                    initializedSize);
        } catch (TikaException e) {
            throw new IOException("Could not configure Tika XML parser pool.",
                    e);
        }
    }
}
