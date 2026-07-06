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
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;

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
            var resp = httpClient.execute(new HttpGet(fullURL));
            var statusCode = resp.getStatusLine().getStatusCode();
            if (statusCode == HttpStatus.SC_NOT_FOUND) {
                throw new NoSuchFileException(fullURL);
            }
            if (statusCode != HttpStatus.SC_OK) {
                var consumedContent = IOUtils.toString(
                        resp.getEntity().getContent(), UTF_8);
                LOG.debug(
                        "Could not consume HTTP content. Response content: "
                                + consumedContent);
                throw new IOException(
                        "Invalid HTTP response \""
                                + resp.getStatusLine() + "\" from " + fullURL);
            }
            return resp.getEntity().getContent();
        } catch (UnsupportedOperationException e) {
            throw new IOException(
                    "Could not get stream from " + fullURL, e);
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
