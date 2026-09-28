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
package com.norconex.importer;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.norconex.commons.lang.bean.BeanUtil;
import com.norconex.commons.lang.event.EventManager;
import com.norconex.commons.lang.file.ContentFamily;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.io.CachedInputStream;
import com.norconex.commons.lang.io.CachedStreamFactory;
import com.norconex.importer.charset.CharsetDetector;
import com.norconex.importer.doc.ContentTypeDetector;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.doc.DocMetaConstants;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerCache;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.DocHandlerException;
import com.norconex.importer.response.ImporterResponse;
import com.norconex.importer.response.ImporterResponse.Status;
import com.norconex.importer.response.ImporterResponseProcessor;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * Principal class responsible for importing documents.
 * Refer to {@link ImporterConfig} for configuration options.
 * Thread-safe, and reusing the same instance is highly recommended.
 * @see ImporterConfig
 */
@Slf4j
@ToString
@EqualsAndHashCode
public class Importer implements Closeable {

    @Getter
    private final ImporterConfig configuration;

    // Only used when using command-line or invoking
    // importDocument(ImporterRequest). The "doc" version has its own.
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private CachedStreamFactory requestStreamFactory;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private final EventManager eventManager;

    // Not known until whatever runs the importer is ready to supply one
    // (e.g., a crawler only after its cluster has joined), so it is set
    // after construction rather than passed in like the event manager.
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private DocHandlerCache cache = DocHandlerCache.NOOP;

    private static final InheritableThreadLocal<Importer> INSTANCE =
            new InheritableThreadLocal<>();

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private boolean initialized;
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private boolean closed;

    /**
     * Creates a new importer with default configuration.
     */
    public Importer() {
        this(null);
    }

    /**
     * Creates a new importer with the given configuration.
     * @param importerConfig Importer configuration
     */
    public Importer(ImporterConfig importerConfig) {
        this(importerConfig, null);
    }

    /**
     * Creates a new importer with the given configuration.
     * @param importerConfig Importer configuration
     * @param eventManager event manager
     */
    public Importer(ImporterConfig importerConfig, EventManager eventManager) {
        if (importerConfig != null) {
            configuration = importerConfig; //TODO make defensive copy?
        } else {
            configuration = new ImporterConfig();
        }
        this.eventManager = new EventManager(eventManager);
        INSTANCE.set(this);
    }

    @JsonIgnore
    public static Importer get() {
        return INSTANCE.get();
    }

    /**
     * Invokes the importer from the command line.
     * @param args Invoke it once without any arguments to get a
     *    list of command-line options.
     */
    public static void main(String[] args) {
        var val = ImporterLauncher.launch(args);
        if (val != 0) {
            System.err.println("Command did not terminate properly.");//NOSONAR
        }
    }

    /**
     * Gets the event manager.
     * @return event manager
     */
    public EventManager getEventManager() {
        return eventManager;
    }

    /**
     * Sets the cache document handlers use for results expensive to obtain
     * more than once for the same input (e.g., a call to a paid external
     * API). Passing {@code null} restores {@link DocHandlerCache#NOOP}.
     * @param cache the cache to supply to handlers
     */
    public synchronized void setCache(DocHandlerCache cache) {
        this.cache = cache != null ? cache : DocHandlerCache.NOOP;
    }

    /**
     * Imports a document according to the importer configuration.
     * @param req request instructions for importing
     * @return importer response
         */
    public ImporterResponse importDocument(ImporterRequest req) {
        try {
            return importDocument(toDocument(req));
        } catch (ImporterException e) {
            LOG.warn("Importer request failed: {}", req, e);
            return new ImporterResponse(
                    req.getReference(),
                    ImporterResponse.Status.ERROR)
                            .setException(new ImporterException(
                                    "Failed to import document for request: "
                                            + req,
                                    e))
                            .setDescription(e.getLocalizedMessage());
        }
    }

    /**
     * Imports a document according to the importer configuration.
     * @param document the document to import
     * @return importer response
     */
    public ImporterResponse importDocument(Doc document) {

        if (!initialized) {
            LOG.info("Performing implicit Importer initialization.");
            init();
        }

        // Note: Doc reference, InputStream and metadata are all null-safe.

        //--- Document Handling ---
        try {

            prepareDocumentForImporting(document);

            List<Doc> nestedDocs = new ArrayList<>();

            var response = executeHandlers(document, nestedDocs);

            List<ImporterResponse> nestedResponses = new ArrayList<>();
            for (Doc childDoc : nestedDocs) {
                var nestedResponse = importDocument(childDoc);
                if (nestedResponse != null) {
                    nestedResponses.add(nestedResponse);
                }
            }
            response.setNestedResponses(nestedResponses);

            //--- Response Processor ---

            if (response.getParentResponse() == null
                    && !configuration.getResponseProcessors().isEmpty()) {
                processResponse(response);
            }
            return response;
        } catch (IOException | ImporterRuntimeException e) {
            LOG.warn("Could not import document: {}", document, e);
            return new ImporterResponse(document.getReference(), Status.ERROR)
                    .setDoc(document)
                    .setException(new ImporterException(
                            "Could not import document: " + document, e));
        }
    }

    private void prepareDocumentForImporting(Doc doc) {

        //--- Ensure non-null content Type on Doc ---
        var ct = doc.getContentType();
        if (ct == null || StringUtils.isBlank(ct.toString())) {
            try {
                ct = ContentTypeDetector.detect(
                        doc.getInputStream(), doc.getReference());
            } catch (IOException e) {
                LOG.warn("Could not detect content type. Defaulting to "
                        + "\"application/octet-stream\".", e);
                ct = ContentType.valueOf("application/octet-stream");
            }
            doc.setContentType(ct);
        }

        //--- Try to detect content encoding if not already set ---

        try {
            var encoding = CharsetDetector.builder()
                    .priorityCharset(doc::getCharset)
                    .fallbackCharset((Charset) null)
                    .build()
                    .detect(doc);
            doc.setCharset(encoding);
        } catch (IOException e) {
            LOG.debug("Problem detecting encoding for: {}",
                    doc.getReference(), e);
        }

        //--- Add basic metadata for what we know so far ---
        var meta = doc.getMetadata();
        meta.set(DocMetaConstants.REFERENCE, doc.getReference());
        meta.set(DocMetaConstants.CONTENT_TYPE, ct.toString());
        var contentFamily = ContentFamily.forContentType(ct);
        if (contentFamily != null) {
            meta.set(DocMetaConstants.CONTENT_FAMILY, contentFamily.toString());
        }
        if (doc.getCharset() != null) {
            meta.set(DocMetaConstants.CONTENT_ENCODING,
                    doc.getCharset().toString());
        }
    }

    // We deal with stream, but since only one of stream or file can be set,
    // convert file to stream only if set.
    @SuppressWarnings("resource")
    private Doc toDocument(ImporterRequest req) throws ImporterException {
        ensureRequestStreamFactory();

        CachedInputStream is;
        var ref = StringUtils.trimToEmpty(req.getReference());
        if (req.getInputStream() != null) {
            // From input stream
            is = CachedInputStream.cache(
                    req.getInputStream(), requestStreamFactory);
        } else if (req.getFile() != null) {
            // From file
            if (!req.getFile().toFile().isFile()) {
                throw new ImporterException(
                        "File does not exists or is not a file: "
                                + req.getFile().toAbsolutePath());
            }
            try {
                is = requestStreamFactory.newInputStream(
                        new FileInputStream(req.getFile().toFile()));
            } catch (IOException e) {
                throw new ImporterException(
                        "Could not import file: "
                                + req.getFile().toAbsolutePath(),
                        e);
            }
            if (StringUtils.isBlank(ref)) {
                ref = req.getFile().toFile().getAbsolutePath();
            }
        } else {
            is = requestStreamFactory.newInputStream();
        }

        return new Doc(ref)
                .setCharset(req.getCharset())
                .setContentType(req.getContentType())
                .setInputStream(is)
                .setMetadata(req.getMetadata());
    }

    private synchronized void ensureRequestStreamFactory() {
        if (requestStreamFactory != null) {
            return;
        }

        var tempDir = configuration.getTempDir();
        if (tempDir == null) {
            tempDir = Paths.get(ImporterConfig.DEFAULT_TEMP_DIR_PATH);
        }
        if (!tempDir.toFile().exists()) {
            try {
                Files.createDirectories(tempDir);
            } catch (IOException e) {
                throw new ImporterRuntimeException(
                        "Cannot create importer temporary directory: "
                                + tempDir,
                        e);
            }
        }
        requestStreamFactory = new CachedStreamFactory(
                (int) configuration.getMaxMemoryPool(),
                (int) configuration.getMaxMemoryInstance(),
                configuration.getTempDir());
    }

    private void processResponse(ImporterResponse response) {
        for (ImporterResponseProcessor proc : configuration
                .getResponseProcessors()) {
            //MAYBE: do something with return response?
            proc.processImporterResponse(response);
        }
    }

    private ImporterResponse executeHandlers(
            Doc doc, List<Doc> childDocsHolder) throws ImporterException {
        var resp = new ImporterResponse(doc);

        if (configuration.getHandlers() == null) {
            return resp.setStatus(Status.SUCCESS);
        }
        var ctx = DocHandlerContext.builder()
                .doc(doc)
                .eventManager(eventManager)
                .cache(cache)
                .build();
        try {
            for (DocHandler handler : configuration.getHandlers()) {
                if (!ctx.executeDocHandler(handler)) {
                    break;
                }
            }
        } catch (Exception e) {
            throw new DocHandlerException(e.getCause());
        } finally {
            try {
                ctx.flush();
            } catch (IOException e) {
                LOG.error("Could not flush document stream for {}",
                        ctx.reference(), e);
            }
        }
        childDocsHolder.addAll(ctx.childDocs());

        if (ctx.isRejected()) {
            return resp
                    .setStatus(Status.REJECTED)
                    .setRejectCause(ctx.rejectedBy())
                    .setDescription(Objects.toString(ctx.rejectedBy(), null));
        }
        return resp.setStatus(Status.SUCCESS);
    }

    /**
     * Initializes the importer. Invokes <code>init</code> on all handlers.
     * If not explicitly called first, it is called implicitly by one of the
     * import method, only once per importer instance.
     */
    public synchronized void init() {
        if (closed) {
            throw new IllegalStateException(
                    "Cannot initialize a closed Importer.");
        }
        if (initialized) {
            LOG.debug("Importer already initialized.");
            return;
        }
        initialized = true;
        //TODO check if visitAll is the best for this
        BeanUtil.visitAll(
                configuration.getHandlers(),
                t -> {
                    try {
                        t.init();
                    } catch (IOException e) {
                        throw new ImporterRuntimeException(
                                "Coult not initialize handler: " + t, e);
                    }
                },
                DocHandler.class);
    }

    /**
     * Closes the importer and its resources. Has to be invoked explicitly
     * when the importer is no longer in use.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            LOG.warn("Importer already closed.");
            return;
        }
        closed = true;
        //TODO check if visitAll is the best for this
        BeanUtil.visitAll(
                configuration.getHandlers(),
                t -> {
                    try {
                        t.close();
                    } catch (IOException e) {
                        throw new ImporterRuntimeException(
                                "Coult not initialize handler: " + t, e);
                    }
                },
                DocHandler.class);
    }
}
