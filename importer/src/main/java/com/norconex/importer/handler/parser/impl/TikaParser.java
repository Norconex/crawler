/* Copyright 2010-2024 Norconex Inc.
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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.util.List;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

import com.norconex.commons.lang.EqualsUtil;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.parser.ParseState;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * Raw Tika parser that needs to be configured by passing a tika
 * configuration file.
 */
@EqualsAndHashCode
@ToString
public class TikaParser
        implements ConfigurableDocHandler<TikaParserConfig> {

    @Getter
    private final TikaParserConfig configuration = new TikaParserConfig();

    private Parser parser;

    @Override
    public void init() throws IOException {
        try {
            parser = new AutoDetectParser(
                    new TikaConfig(configuration.getTikaConfigFile()));
        } catch (TikaException | IOException | SAXException e) {
            throw new IOException("Could not initialize TikaParser.", e);
        }
    }

    @Override
    public boolean handle(DocHandlerContext ctx) throws IOException {
        try (var input = ctx.input().asInputStream();
                var output = ctx.output().asWriter(UTF_8)) {
            var tikaMetadata = new Metadata();
            tikaMetadata.set(
                    TikaCoreProperties.RESOURCE_NAME_KEY,
                    ctx.reference());
            var context = new ParseContext();
            context.set(Parser.class, parser);
            try {
                parser.parse(
                        input,
                        new BodyContentHandler(output),
                        tikaMetadata,
                        context);
            } catch (IOException | SAXException | TikaException e) {
                throw new IOException(
                        "Could not parse file: " + ctx.reference(), e);
            }
            addTikaToImporterMetadata(tikaMetadata, ctx.metadata());
        }
        ctx.parseState(ParseState.POST);
        return true;
    }

    private void addTikaToImporterMetadata(
            Metadata tikaMeta, Properties metadata) {
        var names = tikaMeta.names();
        for (String name : names) {
            if (TikaCoreProperties.RESOURCE_NAME_KEY.equals(name)) {
                continue;
            }
            var nxValues = metadata.getStrings(name);
            var tikaValues = tikaMeta.getValues(name);
            for (String tikaValue : tikaValues) {
                if (!containsSameValue(name, nxValues, tikaValue)) {
                    metadata.add(name, tikaValue);
                } else {
                    metadata.set(name, tikaValue);
                }
            }
        }
    }

    private boolean containsSameValue(
            String name, List<String> nxValues, String tikaValue) {
        if (EqualsUtil.equalsAnyIgnoreCase(
                name,
                HttpHeaders.CONTENT_TYPE,
                HttpHeaders.CONTENT_ENCODING)) {
            var tk = tikaValue.replaceAll("[\\s]", "");
            for (String nxValue : nxValues) {
                if (nxValue.replaceAll("[\\s]", "").equalsIgnoreCase(tk)) {
                    return true;
                }
            }
            return false;
        }
        return nxValues.contains(tikaValue);
    }
}
