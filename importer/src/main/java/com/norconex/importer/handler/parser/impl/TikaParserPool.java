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
import java.io.InterruptedIOException;
import java.util.Set;

import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Bounds how many top-level documents one Tika parser parses at once.
 * <p>
 * The parser itself is shared: Tika parsers are thread-safe, and a parser
 * graph (with its OCR, PDF and other settings) is costly to build and to
 * keep in memory, so the bound is a number of parsing slots, not a number of
 * parser copies. A thread asking for a slot when all are taken waits for one
 * to free up. Embedded documents parsed while their container is being
 * parsed, on the same thread, use the container's slot, so even a single
 * slot cannot deadlock on recursion.
 * </p>
 */
final class TikaParserPool extends AbstractParser implements AutoCloseable {
    private static final long serialVersionUID = 1L;

    @FunctionalInterface
    interface ParserFactory {
        Parser create() throws IOException;
    }

    private final transient Parser parser;
    private final transient ThreadLocal<Boolean> parsing = new ThreadLocal<>();
    private int freeSlots;
    private boolean closed;

    static Parser create(int size, ParserFactory factory) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException(
                    "poolSize must be zero or greater.");
        }
        if (size == 0) {
            return factory.create();
        }
        return new TikaParserPool(size, factory.create());
    }

    private TikaParserPool(int size, Parser parser) {
        this.parser = parser;
        freeSlots = size;
    }

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return parser.getSupportedTypes(context);
    }

    @Override
    public void parse(TikaInputStream stream, ContentHandler handler,
            Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {
        if (Boolean.TRUE.equals(parsing.get())) {
            parser.parse(stream, handler, metadata, context);
            return;
        }
        takeSlot();
        parsing.set(Boolean.TRUE);
        try {
            parser.parse(stream, handler, metadata, context);
        } finally {
            parsing.remove();
            freeSlot();
        }
    }

    private synchronized void takeSlot() throws IOException {
        while (!closed && freeSlots == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                var failure = new InterruptedIOException(
                        "Interrupted waiting for a Tika parsing slot.");
                failure.initCause(e);
                throw failure;
            }
        }
        if (closed) {
            throw new IOException("Tika parser pool is closed.");
        }
        freeSlots--;
    }

    private synchronized void freeSlot() {
        freeSlots++;
        notifyAll();
    }

    @Override
    public synchronized void close() {
        closed = true;
        notifyAll();
    }
}
