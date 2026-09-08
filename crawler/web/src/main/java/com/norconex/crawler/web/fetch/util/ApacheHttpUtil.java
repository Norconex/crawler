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
package com.norconex.crawler.web.fetch.util;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.firstNonBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.replaceChars;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import org.apache.commons.collections4.map.ListOrderedMap;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.net.URIBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.url.HttpURL;
import com.norconex.crawler.core.doc.CrawlerDocContext;
import com.norconex.crawler.web.fetch.HttpMethod;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpAuthConfig;
import com.norconex.crawler.web.ledger.WebCrawlerEntry;
import com.norconex.importer.doc.Doc;

import lombok.extern.slf4j.Slf4j;

/**
 * Utility methods for fetcher implementations using Apache HttpClient.
 * @since 3.0.0
 */
@Slf4j
public final class ApacheHttpUtil {

    private ApacheHttpUtil() {
    }

    /**
     * <p>
     * Applies the HTTP response content to a document if such content exists.
     * The stream is fully downloaded and associated with a document.
     * </p>
     * @param response the HTTP response
     * @param doc document to apply headers on
     * @return <code>true</code> if there was content to apply
     * @throws IOException could not read existing content
     */
    public static boolean applyResponseContent(
            ClassicHttpResponse response, Doc doc) throws IOException {

        var entity = response.getEntity();
        if (entity == null) {
            // just in case...
            EntityUtils.consumeQuietly(entity);
            return false;
        }
        try (var content =
                doc.getStreamFactory().newInputStream(entity.getContent())) {
            content.enforceFullCaching();
            doc.setInputStream(content);
        } finally {
            // just in case...
            EntityUtils.consumeQuietly(entity);
        }
        return true;
    }

    /**
     * <p>
     * Applies the HTTP response headers to a document. This method will
     * do its best to derive relevant information from the HTTP headers
     * that can be set on the document {@link WebCrawlerEntry}:
     * </p>
     * <ul>
     *   <li>Content type</li>
     *   <li>Content encoding</li>
     *   <li>ETag</li>
     * </ul>
     * <p>
     * In addition, all HTTP headers will be added to the document metadata,
     * with an optional prefix.
     * </p>
     * @param response the HTTP response
     * @param prefix optional metadata prefix for all HTTP response headers
     * @param doc document to apply headers on
     * @param crawlEntry crawl ledger entry, or <code>null</code> for fetches
     *     that have no ledger entry (robots.txt, sitemaps, and other
     *     infrastructure requests made outside the document pipeline).
     *     Caching headers are then simply not recorded.
     */
    public static void applyResponseHeaders(
            HttpResponse response, String prefix, Doc doc,
            WebCrawlerEntry crawlEntry) {
        var it = response.headerIterator();
        while (it.hasNext()) {
            var header = it.next();
            var name = header.getName();
            var value = header.getValue();

            if (StringUtils.isBlank(value)) {
                continue;
            }

            // Content-Type + Content Encoding (Charset)
            if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
                applyContentTypeAndCharset(value, doc);
            }

            // ETag
            if (crawlEntry != null
                    && HttpHeaders.ETAG.equalsIgnoreCase(name)) {
                crawlEntry.setEtag(value);
            }

            // Last Modified
            if (crawlEntry != null
                    && HttpHeaders.LAST_MODIFIED.equalsIgnoreCase(name)) {
                try {
                    crawlEntry.setLastModified(
                            ZonedDateTime.parse(
                                    value,
                                    DateTimeFormatter.RFC_1123_DATE_TIME));
                } catch (DateTimeParseException e) {
                    LOG.debug(
                            "Could not parse HTTP response Last-Modified "
                                    + "header.",
                            e);
                }
            }

            if (StringUtils.isNotBlank(prefix)) {
                name = prefix + name;
            }
            PropertySetter.OPTIONAL.apply(doc.getMetadata(), name, value);
        }
    }

    /**
     * Applies the <code>Content-Type</code> HTTP response header
     * on the supplied document info.  It does so by extracting both
     * the content type and charset from the value, and sets them by invoking
     * {@link Doc#setContentType(ContentType)} and
     * {@link Doc#setCharset(Charset)}.
     * This method is automatically invoked by
     * {@link #applyResponseHeaders(HttpResponse, String, Doc, WebCrawlerEntry)}
     * when encountering a content type header.
     * @param value value to parse and set.
     * @param doc document info
     */
    public static void applyContentTypeAndCharset(
            String value, Doc doc) {
        if (StringUtils.isBlank(value) || doc == null) {
            return;
        }
        // delegate parsing of content-type honoring various forms
        // https://tools.ietf.org/html/rfc7231#section-3.1.1
        var apacheCT = org.apache.hc.core5.http.ContentType.parse(value);

        // only overwrite object properties if not null
        var ct = ContentType.valueOf(apacheCT.getMimeType());
        if (ct != null) {
            doc.setContentType(ct);
        }
        var charset = apacheCT.getCharset();
        if (charset != null) {
            doc.setCharset(charset);
        }
    }

    /**
     * Sets the <code>If-Modified-Since</code> HTTP request header based
     * on document cached last crawled date (if any).
     * @param request HTTP request
     * @param docCtx document context
     */
    public static void setRequestIfModifiedSince(
            HttpRequest request, CrawlerDocContext docCtx) {
        if (docCtx != null && docCtx.getPreviousCrawlEntry() != null) {
            var prevEntry = (WebCrawlerEntry) docCtx.getPreviousCrawlEntry();
            // In case the server did not previously return the last modified
            // date but supports "If-Modified-Since" (odd), we try
            // with last crawl date if last modified is null.
            var zdt = ObjectUtils.firstNonNull(
                    prevEntry.getLastModified(),
                    prevEntry.getProcessedAt());
            if (zdt != null) {
                request.addHeader(
                        HttpHeaders.IF_MODIFIED_SINCE,
                        zdt.format(DateTimeFormatter.RFC_1123_DATE_TIME));
            }
        }
    }

    /**
     * Sets the ETag <code>If-None-Match</code> HTTP request header based
     * on document cached ETag value (if any).
     * @param request HTTP request
     * @param docCtx document
     */
    public static void setRequestIfNoneMatch(
            HttpRequest request, CrawlerDocContext docCtx) {
        if (docCtx != null && docCtx.getPreviousCrawlEntry() != null) {
            var docRecord = (WebCrawlerEntry) docCtx.getPreviousCrawlEntry();
            if (docRecord.getEtag() != null) {
                request.addHeader(
                        HttpHeaders.IF_NONE_MATCH, docRecord.getEtag());
            }
        }
    }

    /**
     * Creates an HTTP request.
     * @param url the request target URL
     * @param method HTTP method (defaults to GET if {@code null})
     * @return Apache HTTP request
     */
    public static HttpUriRequestBase createUriRequest(
            String url, String method) {
        var m = firstNonBlank(method, "GET");
        return createUriRequest(url, HttpMethod.valueOf(m.toUpperCase()));
    }

    /**
     * Creates an HTTP request.
     * @param url the request target URL
     * @param method HTTP method (defaults to GET if {@code null})
     * @return Apache HTTP request
     */
    public static HttpUriRequestBase createUriRequest(
            String url, HttpMethod method) {
        var uri = HttpURL.toURI(url);
        LOG.debug("Encoded URI: {}", uri);
        return switch (method) {
            case HEAD -> new HttpHead(uri);
            case POST -> new HttpPost(uri);
            default -> new HttpGet(uri);
        };
    }

    public static void authenticateUsingForm(
            HttpClient httpClient, HttpAuthConfig authConfig)
            throws IOException, URISyntaxException {
        if (authConfig == null) {
            return;
        }

        Objects.requireNonNull(
                authConfig.getUrl(),
                "Authentication URL must not be null.");

        if (StringUtils.isBlank(authConfig.getFormSelector())) {
            authFormAction(httpClient, authConfig);
        } else {
            authLoginPage(httpClient, authConfig);
        }
    }

    private static void authFormAction(
            HttpClient httpClient, HttpAuthConfig cfg)
            throws IOException {
        var post = new HttpPost(cfg.getUrl());

        List<NameValuePair> formparams = new ArrayList<>();
        formparams.add(
                new BasicNameValuePair(
                        cfg.getFormUsernameField(),
                        cfg.getCredentials().getUsername()));
        formparams.add(
                new BasicNameValuePair(
                        cfg.getFormPasswordField(),
                        EncryptionUtil.decryptPassword(cfg.getCredentials())));

        for (String name : cfg.getFormParamNames()) {
            formparams.add(
                    new BasicNameValuePair(
                            name, cfg.getFormParam(name)));
        }

        LOG.info(
                "Performing FORM authentication at \"{}\" (username={}; p"
                        + "assword=*****)",
                cfg.getUrl(),
                cfg.getCredentials().getUsername());
        var entity = new UrlEncodedFormEntity(
                formparams, cfg.getFormCharset());
        post.setEntity(entity);

        httpClient.execute(post, response -> {
            LOG.info(
                    "Authentication status: {} {}.",
                    response.getCode(),
                    response.getReasonPhrase());
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "Authentication response: {}",
                        IOUtils.toString(
                                response.getEntity().getContent(),
                                StandardCharsets.UTF_8));
            } else {
                EntityUtils.consume(response.getEntity());
            }
            return null;
        });
    }

    private static void authLoginPage(HttpClient httpClient, HttpAuthConfig cfg)
            throws IOException, URISyntaxException {

        LOG.info(
                """
                        Parsing, filing, and submitting login FORM at "{}" \
                        (username={}; p\
                        assword=*****)""", cfg.getUrl(),
                cfg.getCredentials().getUsername());
        var get = new HttpGet(cfg.getUrl());
        var authReq = httpClient.<HttpUriRequestBase>execute(
                get, response -> {
                    var entity = response.getEntity();
                    if (entity == null) {
                        LOG.error(
                                "Authentication URL returned no content. "
                                        + "Status: {} {}",
                                response.getCode(),
                                response.getReasonPhrase());
                        return null;
                    }
                    var doc = Jsoup.parse(
                            entity.getContent(),
                            entity.getContentEncoding() != null
                                    ? entity.getContentEncoding()
                                    : null,
                            cfg.getUrl());
                    try {
                        return formToRequest(doc, cfg);
                    } catch (URISyntaxException e) {
                        throw new IOException(
                                "Can't process authentication login page.", e);
                    }
                });

        if (authReq != null) {
            // execute auth request
            httpClient.execute(authReq, response -> {
                LOG.info(
                        "Authentication status: {} {}",
                        response.getCode(),
                        response.getReasonPhrase());
                if (LOG.isDebugEnabled()) {
                    LOG.debug(
                            "Authentication response: {}",
                            IOUtils.toString(
                                    response.getEntity().getContent(),
                                    StandardCharsets.UTF_8));
                }
                return null;
            });
        }
        //MAYBE share some of auth code with authFormAction(...)
    }

    static HttpUriRequestBase formToRequest(
            Document doc, HttpAuthConfig cfg) throws URISyntaxException {

        var form = doc.selectFirst(cfg.getFormSelector());
        if (form == null) {
            LOG.error("Page has no login form: {}", cfg.getUrl());
            return null;
        }

        //--- Load/populate form ---

        Map<String, String> params = new ListOrderedMap<>();
        // Loop through each input and fill/overwrite matching
        // ones, else take as is.
        for (Element el : form.select("[name]")) {
            var name = el.attr("name");
            var value = el.val();
            if (StringUtils.isBlank(value)) {
                value = el.wholeText();
            }
            params.put(name, value);
        }

        // overwrite matching form params from configuration
        if (cfg.getFormUsernameField() != null) {
            params.put(
                    cfg.getFormUsernameField(),
                    cfg.getCredentials().getUsername());
        }
        if (cfg.getFormPasswordField() != null) {
            params.put(
                    cfg.getFormPasswordField(),
                    EncryptionUtil.decryptPassword(cfg.getCredentials()));
        }
        params.putAll(cfg.getFormParams());

        return buildFormRequest(form, cfg, params);
    }

    //--- Form params to HTTP request ---
    private static HttpUriRequestBase buildFormRequest(
            Element form,
            HttpAuthConfig cfg,
            Map<String, String> params) throws URISyntaxException {

        var actionURL =
                firstNonBlank(form.attr("abs:action"), cfg.getUrl());

        // If no "method", we assume "GET".
        var httpRequest =
                createUriRequest(actionURL, form.attr("method"));

        // Only for POST. https://www.w3schools.com/tags/att_form_enctype.asp
        if (httpRequest instanceof HttpPost httpPost) {
            HttpEntity entity;
            var enctype = form.attr("enctype");
            var charset = ObjectUtils.firstNonNull(
                    cfg.getFormCharset(),
                    isNotBlank(form.attr("accept-charset"))
                            ? Charset.forName(form.attr("accept-charset"))
                            : UTF_8);
            if ("multipart/form-data".equalsIgnoreCase(enctype)) {
                var multiPartBuilder = MultipartEntityBuilder.create();
                for (Entry<String, String> en : params.entrySet()) {
                    multiPartBuilder.addTextBody(en.getKey(), en.getValue());
                }
                entity = multiPartBuilder.build();
            } else if ("text/plain".equalsIgnoreCase(enctype)) {
                var b = new StringBuilder();
                for (Entry<String, String> en : params.entrySet()) {
                    b.append(replaceChars(en.getKey(), ' ', '+'));
                    b.append('=');
                    b.append(replaceChars(en.getValue(), ' ', '+'));
                    b.append('\n');
                }
                entity = new StringEntity(b.toString(), charset);
            } else {
                // defaults to: application/x-www-form-urlencoded
                entity = new UrlEncodedFormEntity(
                        toNameValuePairs(params), charset);
            }
            httpPost.setEntity(entity);
        } else if (httpRequest instanceof HttpGet get) {
            get.setUri(
                    new URIBuilder(get.getUri())
                            .setParameters(toNameValuePairs(params))
                            .build());
        } else {
            LOG.error("Form method not spported: {}", httpRequest.getMethod());
            return null;
        }
        return httpRequest;
    }

    private static List<NameValuePair> toNameValuePairs(
            Map<String, String> map) {
        List<NameValuePair> pairs = new ArrayList<>();
        for (Entry<String, String> en : map.entrySet()) {
            pairs.add(new BasicNameValuePair(en.getKey(), en.getValue()));
        }
        return pairs;
    }
}
