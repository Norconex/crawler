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

/**
 * Sizes Tika's JVM-wide pool of reusable SAX parsers and DOM builders.
 * <p>
 * Tika 4 offers no configuration setting for it, and when every pooled
 * parser is in use, it logs a contention warning and builds a throwaway
 * parser for each XML document parsed beyond the pool size (HTML, XHTML,
 * Office Open XML, ...). A crawl parsing with more threads than the pool
 * holds (10 by default) pays that on every such document.
 * </p>
 * <p>
 * The pool being shared by the whole JVM, the largest size asked for wins:
 * the first explicit request is applied as is, later ones only ever grow
 * it (never below the size Tika has, whoever set it). Asking for nothing leaves Tika's pool alone. Resizing rebuilds the
 * pool, so asking again for a size already applied does nothing.
 * </p>
 */
@Slf4j
final class TikaXmlParserPool {
    private static Integer appliedSize;

    private TikaXmlParserPool() {
    }

    static synchronized void configure(Integer requested) throws IOException {
        if (requested == null) {
            return;
        }
        if (requested <= 0) {
            throw new IllegalArgumentException(
                    "xmlParserPoolSize must be positive.");
        }
        var current = XMLReaderUtils.getPoolSize();
        // After the first request, the size Tika has now counts too: it may
        // have been grown by something else, and must not shrink.
        var target = appliedSize == null
                ? requested
                : Math.max(Math.max(appliedSize, current), requested);
        if (target > requested) {
            LOG.info("Tika XML parser pool size {} asked for, keeping the "
                    + "larger size {} (JVM-wide).", requested, target);
        }
        if (target == current) {
            appliedSize = target;
            return;
        }
        try {
            XMLReaderUtils.setPoolSize(target);
        } catch (TikaException e) {
            throw new IOException("Could not configure Tika XML parser pool.",
                    e);
        }
        appliedSize = target;
        LOG.info("Tika XML parser pool size: {} (JVM-wide).", target);
    }
}
