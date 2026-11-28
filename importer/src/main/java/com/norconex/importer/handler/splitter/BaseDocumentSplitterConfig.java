/* Copyright 2023-2026 Norconex Inc.
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

import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.BaseDocHandlerConfig;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class BaseDocumentSplitterConfig extends BaseDocHandlerConfig {

    /**
     * Whether to discard the original document after it was split. Only
     * applies if the document was actually split.
     * @param discardOriginal <code>true</code> to discard original document
     *     after effective split.
     * @return <code>true</code> when discarding original document
     *     after effective split, <code>false</code> otherwise.
     */
    private boolean discardOriginal;

    /**
     * The fields of the document being split that the documents this
     * splitter makes get a copy of, matched on the field name. By default
     * (<code>.*</code>) they get all of them, so a field of the whole
     * document (an author, a department) stays on each of its parts, and a
     * condition on such a field applies to them too. Narrow it to copy only
     * some, or leave the pattern blank to copy none. Either way, a split
     * document gets the fields the splitter itself sets on it.
     * @param parentMetadataMatcher matches the names of the fields to copy
     * @return matches the names of the fields to copy
     */
    private final TextMatcher parentMetadataMatcher = TextMatcher.regex(".*");

    /**
     * Whether this splitter also applies to the parts it made itself, at any
     * depth, when they come back through the flow (every part goes through
     * all the handlers again, from the first). By default it leaves its own
     * parts as they are. Parts made by other splitters are not affected, and
     * conditions still decide whether a part reaches this splitter.
     * @param applyToOwnParts <code>true</code> for this splitter to split
     *     the parts it made, too
     * @return <code>true</code> when this splitter applies to its own parts
     */
    private boolean applyToOwnParts;
}
