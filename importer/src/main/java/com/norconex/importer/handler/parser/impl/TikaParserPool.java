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
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Bounds concurrent use of independent Tika parser graphs. Recursive parsing
 * on the same thread reuses the parent's parser, including with a size of one.
 */
final class TikaParserPool extends AbstractParser implements AutoCloseable {
    private static final long serialVersionUID = 1L;

    @FunctionalInterface
    interface ParserFactory {
        Parser create() throws IOException;
    }

    private final Deque<Parser> available = new ArrayDeque<>();
    private final ThreadLocal<Parser> current = new ThreadLocal<>();
    private boolean closed;

    static Parser create(int size, ParserFactory factory) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException(
                    "poolSize must be zero or greater.");
        }
        if (size == 0) {
            return factory.create();
        }
        return new TikaParserPool(size, factory);
    }

    private TikaParserPool(int size, ParserFactory factory) throws IOException {
        for (int i = 0; i < size; i++) {
            available.addLast(factory.create());
        }
    }

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        var nested = current.get();
        if (nested != null) {
            return nested.getSupportedTypes(context);
        }
        Parser parser;
        try {
            parser = borrow();
        } catch (IOException e) {
            throw new IllegalStateException("Could not acquire Tika parser.",
                    e);
        }
        try {
            return parser.getSupportedTypes(context);
        } finally {
            release(parser);
        }
    }

    @Override
    public void parse(InputStream stream, ContentHandler handler,
            Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {
        var nested = current.get();
        if (nested != null) {
            nested.parse(stream, handler, metadata, context);
            return;
        }
        var parser = borrow();
        current.set(parser);
        try {
            parser.parse(stream, handler, metadata, context);
        } finally {
            current.remove();
            release(parser);
        }
    }

    private synchronized Parser borrow() throws IOException {
        while (!closed && available.isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                var failure = new InterruptedIOException(
                        "Interrupted waiting for a Tika parser.");
                failure.initCause(e);
                throw failure;
            }
        }
        if (closed) {
            throw new IOException("Tika parser pool is closed.");
        }
        return available.removeFirst();
    }

    private synchronized void release(Parser parser) {
        if (!closed) {
            available.addLast(parser);
        }
        notifyAll();
    }

    @Override
    public synchronized void close() {
        closed = true;
        available.clear();
        notifyAll();
    }
}
