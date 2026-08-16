/* Copyright 2020-2026 Norconex Inc.
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
package com.norconex.importer.handler;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.norconex.commons.lang.event.EventManager;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.io.CachedOutputStream;
import com.norconex.commons.lang.io.CachedStreamFactory;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.ImporterEvent;
import com.norconex.importer.charset.CharsetUtil;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.handler.condition.Condition;
import com.norconex.importer.handler.parser.ParseState;
import com.norconex.importer.util.ReadAdapter;
import com.norconex.importer.util.WriteAdapter;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

@Builder
@Data
@Accessors(fluent = true)
@Getter
public class DocHandlerContext {

    private final List<Doc> childDocs = new ArrayList<>();

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Getter(value = AccessLevel.NONE)
    @Setter(value = AccessLevel.NONE)
    private CachedOutputStream out;

    @Getter(value = AccessLevel.PACKAGE)
    @NonNull
    private final Doc doc;

    //    @Getter(value = AccessLevel.NONE)
    //    @Setter(value = AccessLevel.NONE)
    //    private final HandlerDoc handlerDoc;

    @NonNull
    @Default
    private ParseState parseState = ParseState.PRE;

    /**
     * Closest wrapping {@link Condition}, if applicable.
     * @param condition a condition
     * @return condition
     */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Condition condition;

    @NonNull
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final EventManager eventManager;

    private Object rejectedBy;

    public Properties metadata() {
        return doc.getMetadata();
    }

    public String reference() {
        return doc.getReference();
    }

    public ContentType contentType() {
        return doc.getContentType();
    }

    public List<String> parentReferences() {
        return doc.getParentReferences();
    }

    public Charset charset() {
        return doc.getCharset();
    }

    public CachedStreamFactory streamFactory() {
        return doc.getStreamFactory();
    }

    public boolean isRejected() {
        return rejectedBy != null;
    }

    /**
     * Return the most appropriate charset for the current context. Logic
     * (in order):
     * <ul>
     *  <li>If the document has been parsed already, return UTF-8.</li>
     *  <li>If the provided Charset if not {@code null}, return it.</li>
     *  <li>If the document has a Charset on it, return it.</li>
     *  <li>Fallback to UTF-8.</li>
     * </ul>
     * @param charset the charset to use if non-null and the document has not
     *     been parsed yet.
     * @return a character set (never {@code null})
     */
    public Charset resolveCharset(Charset charset) {
        return CharsetUtil.firstNonNullOrUTF8(
                parseState,
                charset,
                doc.getCharset(),
                StandardCharsets.UTF_8);
    }

    /**
     * Flush and dispose any output that has been written to with
     * {@link #output()} and apply it as the input source of the underlying
     * document.
     * @throws IOException
     */
    public synchronized void flush() throws IOException {

        // PROBLEM: when Cached output stream is wrapped in a writer,
        // the writer won't call the write method on it if we are
        // writing an empty string.  That way we can't rely on
        // "isCacheEmpty" to find out if the content was intentionally
        // blanked out.

        if (out != null && !out.isCacheEmpty()) {
            doc.setInputStream(out.getInputStream());
            out.dispose();
            out = null;
        }
    }

    /**
     * Flushes any output and returns an adapter for  the document input..
     * @return document input adapter
     * @throws IOException
     */
    public synchronized ReadAdapter input() throws IOException {
        flush();
        return new ReadAdapter(
                doc::getInputStream,
                CharsetUtil.firstNonNullOrUTF8(
                        parseState, doc.getCharset()));
    }

    /**
     * Make sure to close the stream when done or explictly flush the stream.
     * @return output adapter
     */
    public synchronized WriteAdapter output() {
        out = streamFactory().newOuputStream();
        return new WriteAdapter(out);
    }

    /**
     * Executes a document handler taking care of firing proper events and
     * passing it this context as well as assigning the handler to
     * "rejectedBy" in case of rejection.
     * @param docHandler document handler to invoke
     * @return <code>true</code> to move to the next handler
     * @throws IOException
     */
    public boolean executeDocHandler(DocHandler docHandler)
            throws IOException {
        var keepGoing = true;
        fireEvent(this, ImporterEvent.IMPORTER_HANDLER_BEGIN);
        try {
            keepGoing = docHandler.handle(this);
            // be safe, and flush any written content
            flush();

            if (!keepGoing && rejectedBy == null) {
                rejectedBy(docHandler);
            }
        } catch (IOException e) {
            fireEvent(this, ImporterEvent.IMPORTER_HANDLER_ERROR, e);
            throw e;
        }
        fireEvent(this, ImporterEvent.IMPORTER_HANDLER_END);
        return keepGoing;
    }

    private void fireEvent(DocHandlerContext ctx, String eventName) {
        fireEvent(ctx, eventName, null);
    }

    private void fireEvent(
            DocHandlerContext ctx, String eventName, Exception e) {
        ctx.eventManager().fire(
                ImporterEvent.builder()
                        .name(eventName)
                        .source(this)
                        .document(ctx.doc())
                        .parseState(ctx.parseState())
                        .exception(e)
                        .build());
    }
}
