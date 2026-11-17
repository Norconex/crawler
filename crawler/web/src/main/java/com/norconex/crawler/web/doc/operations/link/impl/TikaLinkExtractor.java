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
package com.norconex.crawler.web.doc.operations.link.impl;

import static org.apache.commons.lang3.StringUtils.firstNonBlank;
import static org.apache.commons.lang3.StringUtils.trimToNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.DefaultHtmlMapper;
import org.apache.tika.parser.html.HtmlMapper;
import org.apache.tika.parser.html.JSoupParser;
import org.apache.tika.sax.Link;
import org.apache.tika.sax.LinkContentHandler;
import org.xml.sax.SAXException;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.url.HttpURL;
import com.norconex.crawler.web.doc.operations.link.LinkExtractor;
import com.norconex.importer.doc.Doc;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * <p>
 * Implementation of {@link LinkExtractor} using
 * <a href="http://tika.apache.org/">Apache Tika</a> to perform URL
 * extractions from HTML documents.
 * This is an alternative to the {@link HtmlLinkExtractor} or even
 * {@link DomLinkExtractor}.
 * </p>
 * <p>
 * The configuration of content-types, storing the referrer data, and ignoring
 * "nofollow" and ignoring link data are the same as in
 * {@link HtmlLinkExtractor}. For link data, this parser only keeps a
 * pre-defined set of link attributes, when available (title, type,
 * uri, text, rel).
 * </p>
 * @see HtmlLinkExtractor
 */
@EqualsAndHashCode
@ToString
public class TikaLinkExtractor
        implements LinkExtractor, Configurable<TikaLinkExtractorConfig> {

    private static final HtmlMapper fixedHtmlMapper =
            new FixedHtmlParserMapper();

    @Getter
    private final TikaLinkExtractorConfig configuration =
            new TikaLinkExtractorConfig();

    @Override
    public Set<com.norconex.crawler.web.doc.operations.link.Link>
            extractLinks(
                    Doc doc)
                    throws IOException {

        // only proceed if we are dealing with a supported content type
        if (!configuration.getContentTypeMatcher().matches(
                doc.getContentType().toString())) {
            return Set.of();
        }

        if (!getConfiguration().getRestrictions().isEmpty()
                && !getConfiguration().getRestrictions().matches(
                        doc.getMetadata())) {
            return Collections.emptySet();
        }

        var refererUrl = doc.getReference();
        Set<com.norconex.crawler.web.doc.operations.link.Link> nxLinks =
                new HashSet<>();
        if (configuration.getFieldMatcher().isSet()) {
            // Fields
            var values = doc.getMetadata()
                    .matchKeys(configuration.getFieldMatcher())
                    .valueList();
            for (String val : values) {
                extractTikaLinks(
                        nxLinks,
                        new ByteArrayInputStream(val.getBytes()), refererUrl);
            }
        } else {
            // Body
            extractTikaLinks(nxLinks, doc.getInputStream(), doc.getReference());
        }
        return nxLinks;
    }

    private void extractTikaLinks(
            Set<com.norconex.crawler.web.doc.operations.link.Link> nxLinks,
            InputStream is,
            String referrerUrl) throws IOException {
        var linkHandler = new LinkContentHandler();
        var metadata = new Metadata();
        var parseContext = new ParseContext();
        parseContext.set(HtmlMapper.class, fixedHtmlMapper);

        var parser = new JSoupParser();
        try (is) {
            parser.parse(org.apache.tika.io.TikaInputStream.get(is),
                    linkHandler, metadata, parseContext);
            var tikaLinks = linkHandler.getLinks();
            tikaLinks.forEach(
                    tikaLink -> toNxLink(referrerUrl, tikaLink)
                            .ifPresent(nxLinks::add));

            //grab refresh URL from metadata (if present)
            Optional.ofNullable(
                    trimToNull(firstNonBlank(
                            getCaseInsensitive(metadata, "html:refresh"),
                            getCaseInsensitive(metadata, "refresh"))))
                    .map(LinkUtil::extractHttpEquivRefreshContentUrl)
                    .map(
                            url -> trimToNull(
                                    HttpURL.toAbsolute(referrerUrl, url)))
                    .map(url -> {
                        var nxLink =
                                new com.norconex.crawler.web.doc.operations.link.Link(
                                        url);
                        nxLink.setReferrer(referrerUrl);
                        return nxLink;
                    })
                    .ifPresent(nxLinks::add);
        } catch (TikaException | SAXException e) {
            throw new IOException(
                    "Could not parse to extract URLs: " + referrerUrl, e);
        }
    }

    private Optional<
            com.norconex.crawler.web.doc.operations.link.Link> toNxLink(
                    String referrerUrl, Link tikaLink) {
        if (!configuration.isIgnoreNofollow()
                && hasActiveDoNotFollow(tikaLink)) {
            return Optional.empty();
        }
        var extractedURL = tikaLink.getUri();
        if (StringUtils.isBlank(extractedURL)) {
            return Optional.empty();
        }
        if (extractedURL.startsWith("?") || extractedURL.startsWith("#")) {
            extractedURL = referrerUrl + extractedURL;
        } else {
            extractedURL = HttpURL.toAbsolute(referrerUrl, extractedURL);
        }
        if (StringUtils.isNotBlank(extractedURL)) {
            var nxLink =
                    new com.norconex.crawler.web.doc.operations.link.Link(
                            extractedURL);
            nxLink.setReferrer(referrerUrl);

            if (!configuration.isIgnoreLinkData()) {
                tikaMetaToNxMeta(nxLink, tikaLink);
            }
            //System.err.println("EXTRACTED LINK: " + nxLink);
            return Optional.of(nxLink);
        }
        return Optional.empty();
    }

    private boolean hasActiveDoNotFollow(Link tikaLink) {
        var rel = StringUtils.trimToNull(tikaLink.getRel());
        if (rel == null) {
            return false;
        }
        var values = StringUtils.split(rel, " ,\t\n\r");
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if ("nofollow".equalsIgnoreCase(StringUtils.trim(value))) {
                return true;
            }
        }
        return false;
    }

    private void tikaMetaToNxMeta(
            com.norconex.crawler.web.doc.operations.link.Link nxLink,
            Link tikaLink) {
        var linkMeta = nxLink.getMetadata();
        if (StringUtils.isNotBlank(tikaLink.getText())) {
            linkMeta.set("text", tikaLink.getText());
        }
        if (StringUtils.isNotBlank(tikaLink.getType())) {
            linkMeta.set("tag", tikaLink.getType());
            if (tikaLink.isAnchor()
                    || tikaLink.isLink()) {
                linkMeta.set("attr", "href");
            } else if (tikaLink.isIframe()
                    || tikaLink.isImage()
                    || tikaLink.isScript()) {
                linkMeta.set("attr", "src");
            }
        }
        if (StringUtils.isNotBlank(tikaLink.getTitle())) {
            linkMeta.set("attr.title", tikaLink.getTitle());
        }
        if (StringUtils.isNotBlank(tikaLink.getRel())) {
            linkMeta.set("attr.rel", tikaLink.getRel());
        }
    }

    private String getCaseInsensitive(Metadata metadata, String key) {
        for (String name : metadata.names()) {
            if (Strings.CI.equals(name, key)) {
                return metadata.get(name);
            }
        }
        return null;
    }

    // Custom HTML Mapper that adds "title" to the supported anchor
    // attributes, in order to be able to extract the title out of
    // anchors when keepReferrerData is true.
    private static class FixedHtmlParserMapper extends DefaultHtmlMapper {
        @Override
        public String mapSafeAttribute(
                String elementName, String attributeName) {
            if ("a".equals(elementName) && "title".equals(attributeName)) {
                return "title";
            }
            return super.mapSafeAttribute(elementName, attributeName);
        }
    }
}
