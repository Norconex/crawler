/* Copyright 2014-2024 Norconex Inc.
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
package com.norconex.importer.handler.splitter.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.splitter.BaseDocumentSplitterConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Split files with Coma-Separated values (or any other characters, like tab)
 * into one document per line.</p>
 *
 * <p>Can be used both as a pre-parse (text documents) or post-parse handler
 * documents.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.splitter.impl.CsvSplitter"
 *          separatorCharacter=""
 *          quoteCharacter=""
 *          escapeCharacter=""
 *          useFirstRowAsFields="(false|true)"
 *          linesToSkip="(integer)"
 *          referenceColumn="(column name or position from 1)"
 *          contentColumns="(csv list of column/position to use as content)" >
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="CsvSplitter"
 *     separatorCharacter=","
 *     quoteCharacter="'"
 *     escapeCharacter="\"
 *     useFirstRowAsFields="true"
 *     linesToSkip="0"
 *     referenceColumn="clientId"
 *     contentColumns="orgDesc" />
 * }
 * <p>
 * Given this sample CSV file content...
 * </p>
 * <pre>
 * 'clientId','clientName','clientOrg','orgDesc'
 * '123','Joe Dalton','ACME Inc.','Organization\'s description'
 * '345','Avrel Dalton','Daisy Town','Another one'
 * </pre>
 * <p>
 * ... the above example will split the file into two documents (one for each
 * row after the header row):
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class CsvSplitterConfig extends BaseDocumentSplitterConfig {

    public static final char DEFAULT_SEPARATOR_CHARACTER = ',';
    public static final char DEFAULT_QUOTE_CHARACTER = '"';
    public static final char DEFAULT_ESCAPE_CHARACTER = '\\';

    /**
     * Matcher of one or more fields to use as the source of content to split
     * into new documents, instead of the original document content.
     * @param fieldMatcher field matcher
     * @return field matcher
     */
    private final TextMatcher fieldMatcher = new TextMatcher();

    public CsvSplitterConfig setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }

    /**
     * The value-separator character. Default is the comma character (,).
     * @param separatorCharacter value-separator character
     * @return value-separator character
     */
    private char separatorCharacter = DEFAULT_SEPARATOR_CHARACTER;

    /**
     * The value's surrounding quotes character.  Default is the
     * double-quote character (").
     * @param quoteCharacter value's surrounding quotes character
     * @return value's surrounding quotes character
     */
    private char quoteCharacter = DEFAULT_QUOTE_CHARACTER;

    /**
     * The escape character.  Default is the backslash character (\).
     * @param escapeCharacter escape character
     * @return escape character
     */
    private char escapeCharacter = DEFAULT_ESCAPE_CHARACTER;

    /**
     * Whether to use the first row as field names for values.
     * Default is <code>false</code>.
     * @param useFirstRowAsFields <code>true</code> if using first row as
     *        field names
     * @return <code>true</code> if using first row as field names.
     */
    private boolean useFirstRowAsFields;

    /**
     * The number of lines to skip before starting to parse lines.
     * Default is <code>0</code>.
     * @param linesToSkip the number of lines to skip
     * @return the number of lines to skip
     */
    private int linesToSkip;

    /**
     * The column containing the unique document reference. Can be either
     * a column name or position, starting at <code>1</code>.
     * @param referenceColumn column name or position
     * @return column name or position
     */
    private String referenceColumn;

    private final List<String> contentColumns = new ArrayList<>();

    /**
     * One or several columns containing the text to be considered as
     * the document "content".
     * @return content columns
     */
    public List<String> getContentColumns() {
        return Collections.unmodifiableList(contentColumns);
    }

    /**
     * One or several columns containing the text to be considered as
     * the document "content".
     * @param contentColumns content columns
     * @return this instance
     */
    public CsvSplitterConfig setContentColumns(List<String> contentColumns) {
        CollectionUtil.setAll(this.contentColumns, contentColumns);
        return this;
    }
}
