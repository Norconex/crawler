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
package com.norconex.importer.handler.splitter;

import com.norconex.importer.handler.BaseDocHandlerConfig;

import lombok.Data;
import lombok.experimental.Accessors;

@SuppressWarnings("javadoc")
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
}
