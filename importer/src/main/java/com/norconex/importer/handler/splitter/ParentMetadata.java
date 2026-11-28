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
package com.norconex.importer.handler.splitter;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.text.TextMatcher;

/**
 * What a splitter gives the documents it makes of the fields of the document it
 * split.
 */
public final class ParentMetadata {

    private ParentMetadata() {
    }

    /**
     * Copies to a part the fields of its parent whose name the matcher
     * matches. A matcher with no pattern, or a blank one, copies nothing.
     * @param part the fields of the part being made
     * @param parent the fields of the document being split
     * @param matcher matches the names of the fields to copy
     */
    public static void copy(
            Properties part, Properties parent, TextMatcher matcher) {
        if (matcher == null || StringUtils.isBlank(matcher.getPattern())) {
            return;
        }
        part.loadFromMap(parent.matchKeys(matcher));
    }
}
