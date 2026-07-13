/* Copyright 2019-2026 Norconex Inc.
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
package com.norconex.crawler.fs.fetch.impl.cmis;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.file.NoSuchFileException;

import org.apache.commons.io.IOUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.routing.RoutingSupport;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpStatus;

import com.norconex.commons.lang.xml.Xml;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
@RequiredArgsConstructor
public class CmisAtomSession {

    private final CloseableHttpClient httpClient;
    private String endpointURL;
    private String repoId;
    private String repoName;
    private String objectByPathTemplate;
    private String queryTemplate;

    public Xml getDocumentByPath(String path) throws IOException {
        return getDocument(
                objectByPathTemplate.replace(
                        "{path}",
                        URLEncoder.encode(path, UTF_8)));
    }

    public Xml getDocument(String fullURL) throws IOException {
        return new Xml(new InputStreamReader(getStream(fullURL), UTF_8));
    }

    public InputStream getStream(String fullURL) throws IOException {
        try {
            var request = new HttpGet(fullURL);
            var resp = httpClient.execute(
                    RoutingSupport.determineHost(request), request);
            var statusCode = resp.getCode();
            var reasonPhrase = resp.getReasonPhrase();
            if (statusCode == HttpStatus.SC_NOT_FOUND) {
                resp.close();
                throw new NoSuchFileException(fullURL);
            }
            if (statusCode != HttpStatus.SC_OK) {
                var consumedContent = IOUtils.toString(
                        resp.getEntity().getContent(), UTF_8);
                resp.close();
                LOG.debug(
                        "Could not consume HTTP content. Response content: "
                                + consumedContent);
                throw new IOException(
                        "Invalid HTTP response \""
                                + statusCode + " " + reasonPhrase
                                + "\" from " + fullURL);
            }
            // Deliberately not closing `resp` here: the returned stream is
            // read by the caller after this method returns; closing the
            // entity's content stream when the caller is done releases the
            // underlying connection.
            return resp.getEntity().getContent();
        } catch (UnsupportedOperationException e) {
            throw new IOException(
                    "Could not get stream from " + fullURL, e);
        } catch (HttpException e) {
            throw new IOException(
                    "Could not determine target host for " + fullURL, e);
        }
    }

    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error closing CMIS Atom HTTP client", e);
        }
    }
}
