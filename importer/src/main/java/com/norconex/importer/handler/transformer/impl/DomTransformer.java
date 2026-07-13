/* Copyright 2015-2026 Norconex Inc.
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

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.importer.handler.CommonRestrictions;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.util.DomUtil;
import com.norconex.importer.util.chunk.ChunkedTextReader;
import com.norconex.importer.util.chunk.ChunkedTextUtil;
import com.norconex.importer.util.chunk.TextChunk;

import lombok.Data;

/**
 * <p>Extract the value of one or more elements or attributes into
 * a target field, or delete matching elements. Applies to
 * HTML, XHTML, or XML document.</p>
 * <p>
 * This class constructs a DOM tree from a document or field content.
 * That DOM tree is loaded entirely into memory. Use this tagger with caution
 * if you know you'll need to parse huge files. It may be preferable to use
 * {@link RegexTransformer} if this is a concern. Also, to help performance
 * and avoid re-creating DOM tree before every DOM extraction you want to
 * perform, try to combine multiple extractions in a single instance
 * of this Tagger.
 * </p>
 * <p>
 * The <a href="http://jsoup.org/">jsoup</a> parser library is used to load a
 * document content into a DOM tree. Elements are referenced using a
 * <a href="http://jsoup.org/cookbook/extracting-data/selector-syntax">
 * CSS or JQuery-like syntax</a>.
 * </p>
 * <p>Should be used as a pre-parse handler.</p>
 *
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 *
 * <h2>Content-types</h2>
 * <p>
 * By default, this filter is restricted to (applies only to) documents matching
 * the restrictions returned by
 * {@link CommonRestrictions#domContentTypes(String)}.
 * You can specify your own content types if you know they represent a file
 * with HTML or XML-like markup tags.
 * </p>
 *
 * <p>When used as a pre-parse handler,
 * this class attempts to detect the content character
 * encoding unless the character encoding
 * was specified using {@link #setSourceCharset(String)}. Since document
 * parsing converts content to UTF-8, UTF-8 is always assumed when
 * used as a post-parse handler.
 * </p>
 *
 * <p>You can control what gets extracted
 * exactly thanks to the "extract" argument of the new method
 * {@link DOMExtractDetails#setExtract(String)}. Possible values are:</p>
 * <ul>
 *   <li><b>text</b>: Default option when extract is blank. The text of
 *       the element, including combined children.</li>
 *   <li><b>html</b>: Extracts an element inner
 *       HTML (including children).</li>
 *   <li><b>outerHtml</b>: Extracts an element outer
 *       HTML (like "html", but includes the "current" tag).</li>
 *   <li><b>ownText</b>: Extracts the text owned by this element only;
 *       does not get the combined text of all children.</li>
 *   <li><b>data</b>: Extracts the combined data of a data-element (e.g.
 *       &lt;script&gt;).</li>
 *   <li><b>id</b>: Extracts the ID attribute of the element (if any).</li>
 *   <li><b>tagName</b>: Extract the name of the tag of the element.</li>
 *   <li><b>val</b>: Extracts the value of a form element
 *       (input, textarea, etc).</li>
 *   <li><b>className</b>: Extracts the literal value of the element's
 *       "class" attribute, which may include multiple class names,
 *       space separated.</li>
 *   <li><b>cssSelector</b>: Extracts a CSS selector that will uniquely
 *       select (identify) this element.</li>
 *   <li><b>attr(attributeKey)</b>: Extracts the value of the element
 *       attribute matching your replacement for "attributeKey"
 *       (e.g. "attr(title)" will extract the "title" attribute).</li>
 * </ul>
 *
 * <p>You can specify a <code>fromField</code>
 * as the source of the HTML to parse instead of using the document content.
 * If multiple values are present for that source field, DOM extraction will be
 * applied to each value.
 * </p>
 *
 * <p>You can specify a <code>defaultValue</code>
 * on each DOM extraction details. When no match occurred for a given selector,
 * the default value will be stored in the <code>toField</code> (as opposed
 * to not storing anything).  When matching blanks (see below) you will get
 * an empty string as opposed to the default value.
 * Empty strings and spaces are supported as default values
 * (the default value is now taken literally).
 * </p>
 *
 * <p>You can set <code>matchBlanks</code> to
 * <code>true</code> to match elements that are present
 * but have blank values. Blank values are empty values or values containing
 * white spaces only. Because white spaces are normalized by the DOM parser,
 * such matches will always return an empty string (spaces will be trimmed).
 * By default elements with blank values are not matched and are ignored.
 * </p>
 *
 * <p>You can specify which parser to use when reading
 * documents. The default is "html" and will normalize the content
 * as HTML. This is generally a desired behavior, but this can sometimes
 * have your selector fail. If you encounter this
 * problem, try switching to "xml" parser, which does not attempt normalization
 * on the content. The drawback with "xml" is you may not get all HTML-specific
 * selector options to work.  If you know you are dealing with XML to begin
 * with, specifying "xml" should be a good option.
 * </p>
 *
 * <h2>Content deletion from fields</h2>
 * <p>
 * As of 3.0.0, you can specify whether to delete any elements
 * matched by the selector. You can use with a "toField" or on its own.
 * Some options are ignored by deletions, such as
 * "extract" or "defaultValue".  Because taggers cannot modify the document
 * content, deletion only applies to metadata fields. Use {@link DOMDeleteTransformer}
 * to modify the document content.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.DOMTagger"
 *         fromField="(optional source field)"
 *         parser="[html|xml]"
 *         sourceCharset="(character encoding)">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple "dom" tags allowed -->
 *   <dom selector="(selector syntax)"
 *       toField="(target field)"
 *       extract="[text|html|outerHtml|ownText|data|tagName|val|className|cssSelector|attr(attributeKey)]"
 *       matchBlanks="[false|true]"
 *       defaultValue="(optional value to use when no match)"
 *       delete="[false|true]"
 *       {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}/>
 *
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="DOMTagger">
 *   <dom selector="div.firstName" toField="firstName" />
 *   <dom selector="div.lastName"  toField="lastName" />
 * </handler>
 * }
 * <p>
 * Given this HTML snippet...
 * </p>
 * <pre>
 * &lt;div class="firstName"&gt;Joe&lt;/div&gt;
 * &lt;div class="lastName"&gt;Dalton&lt;/div&gt;
 * </pre>
 * <p>
 * ... the above example will store "Joe" in a "firstName" field and "Dalton"
 * in a "lastName" field.
 * </p>
 * @see DOMDeleteTransformer
 */
@SuppressWarnings("javadoc")
@Data
public class DomTransformer
        implements ConfigurableDocHandler<DomTransformerConfig> {

    private final DomTransformerConfig configuration =
            new DomTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {

        // only proceed if we are dealing with a supported content type
        if (!configuration.getContentTypeMatcher().matches(
                docCtx.contentType().toString())) {
            return true;
        }

        ChunkedTextReader.builder()
                .charset(configuration.getSourceCharset())
                .fieldMatcher(configuration.getFieldMatcher())
                .maxChunkSize(-1) // disable chunking to not break the DOM.
                .build()
                .read(docCtx, chunk -> {
                    applyOperations(docCtx, chunk);
                    return true;
                });
        return true;
    }

    //NOTE: each operation are ran in isolation, the result being passed
    // to the next operation (updated Document or Metadata).
    private void applyOperations(DocHandlerContext docCtx, TextChunk chunk)
            throws IOException {

        if (StringUtils.isBlank(chunk.getText())) {
            return;
        }

        var doc = Jsoup.parse(
                chunk.getText(),
                docCtx.reference(),
                DomUtil.toJSoupParser(configuration.getParser()));

        var preserveOnly = new ArrayList<String>();

        for (DomOperation op : configuration.getOperations()) {
            List<String> extractedValues = new ArrayList<>();
            applyOperation(doc, op, extractedValues, preserveOnly);
            // set extracts on toField
            if (isNotBlank(op.getToField()) && !extractedValues.isEmpty()) {
                PropertySetter.orAppend(op.getOnSet()).apply(
                        docCtx.metadata(), op.getToField(), extractedValues);
            }
        }

        String newSourceContent;
        if (preserveOnly.isEmpty()) {
            newSourceContent = doc.toString();
        } else {
            newSourceContent = StringUtils.join(preserveOnly, "\n");
        }

        ChunkedTextUtil.writeBack(docCtx, chunk, newSourceContent);
    }

    // return possibly modify original content and any extractions
    // are added to the extractions argument.
    private void applyOperation(
            Document doc,
            DomOperation op,
            List<String> extractions,
            List<String> preserveOnly) {

        var extractedValues = handleExtractAndDelete(doc, op);

        // At this point, JSoup doc was only modified by dom deletions.
        // Extracted values are in a list.
        var toFieldSet = StringUtils.isNotBlank(op.getToField());

        if (toFieldSet) {
            extractions.addAll(extractedValues);
        }

        // preserve: if there are no "toField" and we were not deleting,
        // it means we are "preserving" so, we overwrite
        // the chunk doc with the extractions and we do not set extractions.
        if (!op.isDelete() && !toFieldSet) {
            preserveOnly.addAll(extractedValues);
        }
    }

    private List<String> handleExtractAndDelete(
            Document doc, DomOperation op) {
        List<String> extractedValues = new ArrayList<>();

        var elms = doc.select(StringUtils.trim(op.getSelector()));
        var hasDefault = op.getDefaultValue() != null;

        // no elements matching
        if (elms.isEmpty()) {
            if (hasDefault) {
                extractedValues.add(op.getDefaultValue());
            }
            return extractedValues;
        }

        // one or more elements matching
        for (Element elm : elms) {
            // we store in "toField" only if set, else we store
            // in original field, or body (JSoup Document) if no original field.
            var value = DomUtil.getElementValue(elm, op.getExtract());
            // JSoup normalizes white spaces and should always trim them,
            // but we force it here to ensure 100% consistency.
            value = StringUtils.trim(value);
            var matches = ((value != null)
                    && (op.isMatchBlanks() || !StringUtils.isBlank(value)));
            if (matches) {
                extractedValues.add(value);
            } else if (hasDefault) {
                extractedValues.add(op.getDefaultValue());
            }

            if (op.isDelete()) {
                elm.remove();
            }
        }
        return extractedValues;
    }
}
