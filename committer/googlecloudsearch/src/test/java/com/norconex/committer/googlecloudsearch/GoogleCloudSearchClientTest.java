/* Copyright 2009-2026 Norconex Inc.
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
package com.norconex.committer.googlecloudsearch;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.cloudsearch.v1.CloudSearch;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.AclInheritanceMapping;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.AclInheritanceType;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.AclMapping;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.AclTarget;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.PrincipalType;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.UploadFormat;
import com.norconex.commons.lang.map.Properties;

class GoogleCloudSearchClientTest {

    private static final String REFERENCE = "https://example.com/path?q=1";

    // --- Pure helper method tests -------------------------------------

    @Test
    void encodeItemIdUsesUrlSafeBase64ForShortIds() {
        var client = newClient(newConfig());
        assertThat(client.encodeItemId("hello world"))
                .isEqualTo("aGVsbG8gd29ybGQ");
    }

    @Test
    void encodeItemIdFallsBackToSha256ForLongIds() {
        var client = newClient(newConfig());
        var longId = "x".repeat(1200);
        var encoded = client.encodeItemId(longId);
        assertThat(encoded).startsWith("sha256-");
        assertThat(encoded).isEqualTo("sha256-" + client.sha256Hex(longId));
    }

    @Test
    void buildItemNameCombinesDataSourceAndEncodedId() throws Exception {
        var client = newClient(newConfig().setDataSourceId("my-datasource"));
        assertThat(client.buildItemName("abc"))
                .isEqualTo("datasources/my-datasource/items/" +
                        client.encodeItemId("abc"));
    }

    @Test
    void buildItemNameRejectsBlankSourceId() {
        var client = newClient(newConfig());
        assertThatThrownBy(() -> client.buildItemName(" "))
                .isInstanceOf(CommitterException.class);
    }

    @Test
    void resolveContentTypeDefaultsBasedOnUploadFormat() {
        var raw = newClient(newConfig().setUploadFormat(UploadFormat.RAW));
        assertThat(raw.resolveContentType(new Properties()))
                .isEqualTo("application/octet-stream");

        var text = newClient(newConfig().setUploadFormat(UploadFormat.TEXT));
        assertThat(text.resolveContentType(new Properties()))
                .isEqualTo("text/plain");

        var metadata = new Properties();
        metadata.set(
                GoogleCloudSearchClient.FIELD_CONTENT_TYPE, "application/pdf");
        assertThat(text.resolveContentType(metadata))
                .isEqualTo("application/pdf");
    }

    @Test
    void toRfc3339ParsesSupportedDateFormats() {
        var client = newClient(newConfig());
        assertThat(client.toRfc3339("2024-01-15T10:30:00Z"))
                .isEqualTo("2024-01-15T10:30:00Z");
        assertThat(client.toRfc3339("2024-01-15T10:30:00+02:00"))
                .isEqualTo("2024-01-15T08:30:00Z");
        assertThat(client.toRfc3339("Mon, 15 Jan 2024 10:30:00 GMT"))
                .isEqualTo("2024-01-15T10:30:00Z");
        assertThat(client.toRfc3339("2024-01-15T10:30:00"))
                .isEqualTo("2024-01-15T10:30:00Z");
        assertThat(client.toRfc3339("not a date")).isNull();
    }

    @Test
    void nextVersionUsesFixedClockAndIncrements() {
        var client = newClient(newConfig(), 1000L);
        assertThat(client.nextVersion())
                .isEqualTo("0000000000000001000-000001");
        assertThat(client.nextVersion())
                .isEqualTo("0000000000000001000-000002");
    }

    @Test
    void toPrincipalMapsEachPrincipalType() {
        var client = newClient(newConfig());

        var user = client.toPrincipal(PrincipalType.USER, "user@example.com");
        assertThat(user.getGsuitePrincipal().getGsuiteUserEmail())
                .isEqualTo("user@example.com");

        var group =
                client.toPrincipal(PrincipalType.GROUP, "group@example.com");
        assertThat(group.getGsuitePrincipal().getGsuiteGroupEmail())
                .isEqualTo("group@example.com");

        var customer = client.toPrincipal(PrincipalType.CUSTOMER, null);
        assertThat(customer.getGsuitePrincipal().getGsuiteDomain()).isTrue();

        assertThat(client.toPrincipal(PrincipalType.USER, "")).isNull();
    }

    @Test
    void buildAclDefaultsToDomainWideReadWhenNothingResolved()
            throws Exception {
        // Cloud Search rejects items with no ACL at all ("Missing Acl in
        // request"), so when no mapping/inheritance resolves to anything,
        // the item must default to being readable by the entire domain
        // instead of an omitted (null) ACL.
        var client = newClient(newConfig());
        var acl = client.buildAcl(new Properties());
        assertThat(acl).isNotNull();
        assertThat(acl.getReaders()).hasSize(1);
        assertThat(acl.getReaders().get(0).getGsuitePrincipal()
                .getGsuiteDomain()).isTrue();
    }

    @Test
    void buildAclMapsFieldsToReadersAndInheritance() throws Exception {
        var config = newConfig()
                .setAclMappings(
                        List.of(
                                new AclMapping()
                                        .setFromField("acl.reader")
                                        .setTarget(AclTarget.READERS)
                                        .setPrincipalType(PrincipalType.USER)))
                .setAclInheritance(
                        new AclInheritanceMapping()
                                .setFromField("parentRef")
                                .setAclInheritanceType(
                                        AclInheritanceType.BOTH_PERMIT))
                .setDataSourceId("ds");
        var client = newClient(config);

        var metadata = new Properties();
        metadata.set("acl.reader", "reader@example.com");
        metadata.set("parentRef", "parent-id");

        var acl = client.buildAcl(metadata);

        assertThat(acl.getReaders()).hasSize(1);
        assertThat(
                acl.getReaders().get(0).getGsuitePrincipal()
                        .getGsuiteUserEmail())
                                .isEqualTo("reader@example.com");
        assertThat(acl.getAclInheritanceType()).isEqualTo("BOTH_PERMIT");
        assertThat(acl.getInheritAclFrom())
                .isEqualTo(client.buildItemName("parent-id"));
    }

    // --- Batch commit test, against a mocked HttpTransport -------------

    @Test
    void postSendsUpsertsAndDeletesThroughBatchRequest() throws Exception {
        var transport = new RecordingTransport();
        transport.enqueue(
                jsonResponse(
                        successfulBatchResponseHeader(),
                        successfulBatchResponseBody("operations/index-1")));

        var config = newConfig()
                .setUploadFormat(UploadFormat.TEXT)
                .setAclMappings(
                        List.of(
                                new AclMapping()
                                        .setFromField("acl.reader")
                                        .setTarget(AclTarget.READERS)
                                        .setPrincipalType(
                                                PrincipalType.USER)));

        var cloudSearch = new CloudSearch.Builder(
                transport, GsonFactory.getDefaultInstance(),
                noOpInitializer())
                        .setApplicationName(config.getApplicationName())
                        .setRootUrl("https://mock.local/")
                        .build();

        var client = new GoogleCloudSearchClient(
                config, cloudSearch, () -> 1000L);

        var metadata = new Properties();
        metadata.set("title", "Example title");
        metadata.set("objectType", "webpage");
        metadata.set("acl.reader", "reader@example.com");
        metadata.set(GoogleCloudSearchClient.FIELD_CONTENT_TYPE, "text/plain");

        List<CommitterRequest> requests = new ArrayList<>();
        requests.add(
                new UpsertRequest(
                        REFERENCE, metadata,
                        new ByteArrayInputStream(
                                "test body".getBytes(UTF_8))));
        requests.add(
                new DeleteRequest(REFERENCE + "/delete", new Properties()));

        client.post(requests.iterator());

        assertThat(transport.getUrls()).contains("https://mock.local/batch");
        var batchBody = transport.getRequests().get(0).getContentAsString();
        assertThat(batchBody).contains(":index");
        assertThat(batchBody).contains("DELETE");
        assertThat(batchBody).contains("Example title");
        assertThat(batchBody).contains("reader@example.com");

        // Item.version is a base64-encoded bytes field on the wire.
        // Regression test for a bug where the raw, unencoded version
        // string was sent as-is, which the real Cloud Search API
        // rejected with "Base64 decoding failed".
        var versionMatcher = java.util.regex.Pattern
                .compile("\"version\":\"([^\"]+)\"")
                .matcher(batchBody);
        assertThat(versionMatcher.find()).isTrue();
        var decodedVersion = new String(
                java.util.Base64.getUrlDecoder()
                        .decode(versionMatcher.group(1)),
                UTF_8);
        assertThat(decodedVersion).matches("\\d{19}-\\d{6}");
    }

    @Test
    void rawUploadToleratesEmptyMediaUploadResponse() throws Exception {
        var transport = new RecordingTransport();
        // 1. indexing.datasources.items.upload (start upload session).
        transport.enqueue(
                jsonResponse(
                        "application/json",
                        "{\"name\":\"uploadItems/test-upload\"}"));
        // 2. media.upload: the real Cloud Search API returns an empty
        //    body on success here, unlike the Media-typed response the
        //    generated client stub declares. Regression test for a bug
        //    where that empty body failed to parse as JSON.
        transport.enqueue(new MockLowLevelHttpResponse().setStatusCode(200));
        // 3. batch execute.
        transport.enqueue(
                jsonResponse(
                        successfulBatchResponseHeader(),
                        successfulBatchResponseBody("operations/index-raw")));

        var config = newConfig().setUploadFormat(UploadFormat.RAW);

        var cloudSearch = new CloudSearch.Builder(
                transport, GsonFactory.getDefaultInstance(),
                noOpInitializer())
                        .setApplicationName(config.getApplicationName())
                        .setRootUrl("https://mock.local/")
                        .build();

        var client = new GoogleCloudSearchClient(
                config, cloudSearch, () -> 1000L);

        var metadata = new Properties();
        metadata.set(
                GoogleCloudSearchClient.FIELD_BINARY_CONTENT,
                java.util.Base64.getEncoder().encodeToString(
                        "test body".getBytes(UTF_8)));
        metadata.set(GoogleCloudSearchClient.FIELD_CONTENT_TYPE, "text/plain");
        metadata.set("title", "Raw title");

        List<CommitterRequest> requests = new ArrayList<>();
        requests.add(
                new UpsertRequest(
                        REFERENCE, metadata,
                        new ByteArrayInputStream(
                                "ignored".getBytes(UTF_8))));

        client.post(requests.iterator());

        assertThat(transport.getUrls())
                .anyMatch(url -> url.contains("/upload/v1/media/"));
        assertThat(transport.getUrls()).contains("https://mock.local/batch");
    }

    // --- Helpers ---------------------------------------------------------

    private GoogleCloudSearchCommitterConfig newConfig() {
        return new GoogleCloudSearchCommitterConfig()
                .setSecretKeyPath("/unused/service-account.json")
                .setDataSourceId("datasource-id")
                .setApplicationName("Test Application")
                .setConnectorName("Test Application");
    }

    private GoogleCloudSearchClient newClient(
            GoogleCloudSearchCommitterConfig config) {
        return newClient(config, 0L);
    }

    private GoogleCloudSearchClient newClient(
            GoogleCloudSearchCommitterConfig config, long fixedTimeMillis) {
        var cloudSearch = new CloudSearch.Builder(
                new MockHttpTransport(), GsonFactory.getDefaultInstance(),
                noOpInitializer())
                        .setApplicationName(config.getApplicationName())
                        .build();
        return new GoogleCloudSearchClient(
                config, cloudSearch, () -> fixedTimeMillis);
    }

    private static HttpRequestInitializer noOpInitializer() {
        return request -> {
            // NOOP
        };
    }

    private MockLowLevelHttpResponse jsonResponse(
            String contentType, String body) {
        return new MockLowLevelHttpResponse()
                .setStatusCode(200)
                .setContentType(contentType)
                .setContent(body);
    }

    private String successfulBatchResponseHeader() {
        return "multipart/mixed; boundary=batch_test";
    }

    private String successfulBatchResponseBody(String operationName) {
        return "--batch_test\r\n"
                + "Content-Type: application/http\r\n"
                + "Content-ID: response-1\r\n\r\n"
                + "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                + "{\"name\":\""
                + operationName
                + "\",\"done\":true}\r\n"
                + "--batch_test--\r\n";
    }

    private static final class RecordingTransport extends MockHttpTransport {
        private final List<String> urls = new ArrayList<>();
        private final List<MockLowLevelHttpRequest> requests =
                new ArrayList<>();
        private final Deque<MockLowLevelHttpResponse> responses =
                new ArrayDeque<>();

        void enqueue(MockLowLevelHttpResponse response) {
            responses.add(response);
        }

        List<String> getUrls() {
            return urls;
        }

        List<MockLowLevelHttpRequest> getRequests() {
            return requests;
        }

        @Override
        public LowLevelHttpRequest buildRequest(String method, String url)
                throws IOException {
            urls.add(url);
            var request = new MockLowLevelHttpRequest(url) {
                @Override
                public LowLevelHttpResponse execute() throws IOException {
                    return responses.removeFirst();
                }
            };
            requests.add(request);
            return request;
        }
    }
}
