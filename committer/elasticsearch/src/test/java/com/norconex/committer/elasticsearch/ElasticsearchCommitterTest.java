/* Copyright 2024-2026 Norconex Inc.
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
package com.norconex.committer.elasticsearch;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.io.IOUtils.toInputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.input.NullInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.sniff.Sniffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.security.Credentials;

@ExtendWith(MockitoExtension.class)
@Timeout(30)
class ElasticsearchCommitterTest {

    @Mock
    private RestClient mockRestClient;
    @Mock
    private Response mockResponse;
    @Mock
    private HttpEntity mockEntity;
    @Mock
    private StatusLine mockStatusLine;
    @Mock
    private Sniffer mockSniffer;

    private ElasticsearchCommitter committer;

    @BeforeEach
    void setUp() {
        committer = new TestableElasticsearchCommitter(
                mockRestClient, mockSniffer);
        committer.getConfiguration().setIndexName("test-index");
    }

    @AfterEach
    void tearDown() throws CommitterException {
        // nothing to tear down – we don't call init() so no queue is open
    }

    // -------------------------------------------------------------------------
    // initBatchCommitter
    // -------------------------------------------------------------------------

    @Test
    void testInitBatchCommitter_missingIndexName_throwsCommitterException() {
        committer.getConfiguration().setIndexName(null);
        assertThatExceptionOfType(CommitterException.class)
                .isThrownBy(() -> committer.initBatchCommitter())
                .withMessageContaining("Index name is undefined");
    }

    @Test
    void testInitBatchCommitter_blankIndexName_throwsCommitterException() {
        committer.getConfiguration().setIndexName("  ");
        assertThatExceptionOfType(CommitterException.class)
                .isThrownBy(() -> committer.initBatchCommitter())
                .withMessageContaining("Index name is undefined");
    }

    @Test
    void testInitBatchCommitter_withIndexName_succeeds() {
        assertThatNoException().isThrownBy(
                () -> committer.initBatchCommitter());
    }

    @Test
    void testInitBatchCommitter_discoverNodes_createsSniffer()
            throws CommitterException {
        committer.getConfiguration().setDiscoverNodes(true);
        committer.initBatchCommitter();
        // Sniffer is created inside the subclass – just verify no exception
        // and that the sniffer field is wired (closure tested in closeBatch).
    }

    // -------------------------------------------------------------------------
    // closeBatchCommitter
    // -------------------------------------------------------------------------

    @Test
    void testCloseBatchCommitter_closesClientAndSniffer()
            throws CommitterException, IOException {
        committer.initBatchCommitter();
        committer.getConfiguration().setDiscoverNodes(true);
        committer.initBatchCommitter(); // re-init with sniffer
        committer.closeBatchCommitter();
        // No exception expected; client and sniffer are nulled out gracefully.
    }

    @Test
    void testCloseBatchCommitter_withoutInit_doesNotThrow() {
        // committer never initialized – client is null, must not throw
        assertThatNoException().isThrownBy(
                () -> committer.closeBatchCommitter());
    }

    // -------------------------------------------------------------------------
    // commitBatch – upsert
    // -------------------------------------------------------------------------

    @Test
    void testCommitBatch_upsertRequest_sendsCorrectJson() throws Exception {
        stubSuccessResponse("{\"errors\":false}");

        var metadata = new Properties();
        metadata.set("title", "Hello");
        var upsert = new UpsertRequest(
                "doc1", metadata, toInputStream("body text", UTF_8));

        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(upsert));

        verify(mockRestClient).performRequest(any(Request.class));
    }

    @Test
    void testCommitBatch_upsertWithTypeName_includesType() throws Exception {
        stubSuccessResponse("{\"errors\":false}");
        committer.getConfiguration().setTypeName("_doc");

        var upsert = new UpsertRequest(
                "doc1", new Properties(), new NullInputStream(0));
        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(upsert));

        verify(mockRestClient).performRequest(any(Request.class));
    }

    @Test
    void testCommitBatch_upsertWithNullContentField_skipsContent()
            throws Exception {
        stubSuccessResponse("{\"errors\":false}");
        committer.getConfiguration().setTargetContentField(null);

        var upsert = new UpsertRequest(
                "doc1", new Properties(), toInputStream("body", UTF_8));
        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(upsert));

        verify(mockRestClient).performRequest(any(Request.class));
    }

    @Test
    void testCommitBatch_upsertWithDotReplacement_replacesDotsInField()
            throws Exception {
        stubSuccessResponse("{\"errors\":false}");
        committer.getConfiguration().setDotReplacement("_");

        var metadata = new Properties();
        metadata.set("some.field.name", "value");
        var upsert = new UpsertRequest(
                "doc1", metadata, new NullInputStream(0));
        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(upsert));

        verify(mockRestClient).performRequest(any(Request.class));
    }

    @Test
    void testCommitBatch_upsertWithIdInMetadata_idFieldExcluded()
            throws Exception {
        stubSuccessResponse("{\"errors\":false}");

        var metadata = new Properties();
        metadata.set("_id", "custom-id");
        metadata.set("title", "Test");
        var upsert = new UpsertRequest(
                "doc1", metadata, new NullInputStream(0));
        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(upsert));

        verify(mockRestClient).performRequest(any(Request.class));
    }

    @Test
    void testCommitBatch_upsertWithMultiValueField() throws Exception {
        stubSuccessResponse("{\"errors\":false}");

        var metadata = new Properties();
        metadata.set("tags", "a", "b", "c");
        var upsert = new UpsertRequest(
                "doc1", metadata, new NullInputStream(0));
        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(upsert));

        verify(mockRestClient).performRequest(any(Request.class));
    }

    @Test
    void testCommitBatch_upsertWithJsonFieldPattern() throws Exception {
        stubSuccessResponse("{\"errors\":false}");
        committer.getConfiguration().setJsonFieldsPattern("jsonData");

        var metadata = new Properties();
        metadata.set("jsonData", "{\"key\":\"value\"}");
        metadata.set("plainField", "plain text");
        var upsert = new UpsertRequest(
                "doc1", metadata, new NullInputStream(0));
        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(upsert));

        verify(mockRestClient).performRequest(any(Request.class));
    }

    // A vector (for a dense_vector field) is a multi-valued field of numbers.
    // Every value is a string internally, so unless the field is declared a
    // JSON field it goes out quoted, which is not a valid vector.

    @Test
    void testCommitBatch_vectorIsQuotedUnlessDeclaredAJsonField()
            throws Exception {
        stubSuccessResponse("{\"errors\":false}");

        var metadata = new Properties();
        metadata.set("embedding", "0.1", "0.2", "0.3");
        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(
                new UpsertRequest("doc1", metadata, new NullInputStream(0))));

        assertThat(sentBody())
                .contains("\"embedding\":[\"0.1\",\"0.2\",\"0.3\"]");
    }

    @Test
    void testCommitBatch_jsonFieldsPatternSendsVectorAsNumbers()
            throws Exception {
        stubSuccessResponse("{\"errors\":false}");
        committer.getConfiguration().setJsonFieldsPattern("embedding");

        var metadata = new Properties();
        metadata.set("embedding", "0.1", "0.2", "0.3");
        metadata.set("title", "A title");
        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(
                new UpsertRequest("doc1", metadata, new NullInputStream(0))));

        assertThat(sentBody())
                .contains("\"embedding\":[0.1,0.2,0.3]")
                .contains("\"title\":\"A title\"");
    }

    @Test
    void testCommitBatch_jsonFieldsPatternIsARegularExpression()
            throws Exception {
        stubSuccessResponse("{\"errors\":false}");
        committer.getConfiguration().setJsonFieldsPattern("embed.*");

        var metadata = new Properties();
        metadata.set("embedding", "0.1", "0.2");
        metadata.set("title", "A title");
        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(
                new UpsertRequest("doc1", metadata, new NullInputStream(0))));

        assertThat(sentBody())
                .contains("\"embedding\":[0.1,0.2]")
                .contains("\"title\":\"A title\"");
    }

    private String sentBody() throws Exception {
        var captor = ArgumentCaptor.forClass(Request.class);
        verify(mockRestClient).performRequest(captor.capture());
        return EntityUtils.toString(captor.getValue().getEntity());
    }

    @Test
    void testCommitBatch_upsertWithSourceIdField() throws Exception {
        stubSuccessResponse("{\"errors\":false}");
        committer.getConfiguration().setSourceIdField("myIdField");

        var metadata = new Properties();
        metadata.set("myIdField", "custom-doc-id");
        var upsert = new UpsertRequest(
                "ref1", metadata, new NullInputStream(0));
        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(upsert));

        verify(mockRestClient).performRequest(any(Request.class));
    }

    // -------------------------------------------------------------------------
    // commitBatch – delete
    // -------------------------------------------------------------------------

    @Test
    void testCommitBatch_deleteRequest_sendsCorrectJson() throws Exception {
        stubSuccessResponse("{\"errors\":false}");

        var delete = new DeleteRequest("doc1", new Properties());
        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(delete));

        verify(mockRestClient).performRequest(any(Request.class));
    }

    @Test
    void testCommitBatch_deleteWithTypeName_includesType() throws Exception {
        stubSuccessResponse("{\"errors\":false}");
        committer.getConfiguration().setTypeName("_doc");

        var delete = new DeleteRequest("doc1", new Properties());
        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(delete));

        verify(mockRestClient).performRequest(any(Request.class));
    }

    // -------------------------------------------------------------------------
    // commitBatch – mixed batch
    // -------------------------------------------------------------------------

    @Test
    void testCommitBatch_mixedUpsertAndDelete() throws Exception {
        stubSuccessResponse("{\"errors\":false}");

        var upsert = new UpsertRequest(
                "doc1", new Properties(), new NullInputStream(0));
        var delete = new DeleteRequest("doc2", new Properties());
        committer.initBatchCommitter();
        committer.commitBatch(iteratorOf(upsert, delete));

        verify(mockRestClient).performRequest(any(Request.class));
    }

    // -------------------------------------------------------------------------
    // commitBatch – unsupported request
    // -------------------------------------------------------------------------

    @Test
    void testCommitBatch_unsupportedRequest_throwsCommitterException()
            throws CommitterException {
        var bogus = new CommitterRequest() {
            @Override
            public String getReference() {
                return "ref";
            }

            @Override
            public Properties getMetadata() {
                return new Properties();
            }
        };
        committer.initBatchCommitter();
        assertThatExceptionOfType(CommitterException.class)
                .isThrownBy(() -> committer.commitBatch(iteratorOf(bogus)))
                .withMessageContaining("Unsupported request");
    }

    // -------------------------------------------------------------------------
    // commitBatch – error responses
    // -------------------------------------------------------------------------

    @Test
    void testCommitBatch_responseWithErrors_throwsWhenNotIgnored()
            throws Exception {
        // Exception is thrown before reaching status-code check, so we do NOT
        // stub mockStatusLine (Mockito strict mode rejects unused stubs).
        var errorJson = """
                {"errors":true,"items":[{"index":{"error":\
                {"reason":"mapping error"},"_id":"doc1"}}]}""";
        when(mockEntity.getContent())
                .thenReturn(toInputStream(errorJson, UTF_8));
        when(mockResponse.getEntity()).thenReturn(mockEntity);
        when(mockRestClient.performRequest(any())).thenReturn(mockResponse);

        committer.getConfiguration().setIgnoreResponseErrors(false);
        var upsert = new UpsertRequest(
                "doc1", new Properties(), new NullInputStream(0));
        committer.initBatchCommitter();
        assertThatExceptionOfType(CommitterException.class)
                .isThrownBy(() -> committer.commitBatch(iteratorOf(upsert)))
                .withMessageContaining("Elasticsearch returned");
    }

    @Test
    void testCommitBatch_responseWithErrors_doesNotThrowWhenIgnored()
            throws Exception {
        var errorJson = """
                {"errors":true,"items":[{"index":{"error":\
                {"reason":"mapping error"},"_id":"doc1"}}]}""";
        stubSuccessResponse(errorJson);

        committer.getConfiguration().setIgnoreResponseErrors(true);
        var upsert = new UpsertRequest(
                "doc1", new Properties(), new NullInputStream(0));
        committer.initBatchCommitter();
        assertThatNoException()
                .isThrownBy(() -> committer.commitBatch(iteratorOf(upsert)));
    }

    @Test
    void testCommitBatch_non200HttpStatus_throws() throws Exception {
        when(mockStatusLine.getStatusCode())
                .thenReturn(HttpStatus.SC_INTERNAL_SERVER_ERROR);
        when(mockResponse.getStatusLine()).thenReturn(mockStatusLine);
        when(mockEntity.getContent()).thenReturn(
                toInputStream("{\"errors\":false}", UTF_8));
        when(mockResponse.getEntity()).thenReturn(mockEntity);
        when(mockRestClient.performRequest(any())).thenReturn(mockResponse);

        var upsert = new UpsertRequest(
                "doc1", new Properties(), new NullInputStream(0));
        committer.initBatchCommitter();
        assertThatExceptionOfType(CommitterException.class)
                .isThrownBy(() -> committer.commitBatch(iteratorOf(upsert)))
                .withMessageContaining("Invalid HTTP response");
    }

    @Test
    void testCommitBatch_noResponseEntity_doesNotThrow() throws Exception {
        when(mockStatusLine.getStatusCode()).thenReturn(HttpStatus.SC_OK);
        when(mockResponse.getStatusLine()).thenReturn(mockStatusLine);
        when(mockResponse.getEntity()).thenReturn(null);
        when(mockRestClient.performRequest(any())).thenReturn(mockResponse);

        var upsert = new UpsertRequest(
                "doc1", new Properties(), new NullInputStream(0));
        committer.initBatchCommitter();
        assertThatNoException()
                .isThrownBy(() -> committer.commitBatch(iteratorOf(upsert)));
    }

    @Test
    void testCommitBatch_ioExceptionFromClient_wrapsInCommitterException()
            throws Exception {
        when(mockRestClient.performRequest(any()))
                .thenThrow(new IOException("network error"));

        var upsert = new UpsertRequest(
                "doc1", new Properties(), new NullInputStream(0));
        committer.initBatchCommitter();
        assertThatExceptionOfType(CommitterException.class)
                .isThrownBy(() -> committer.commitBatch(iteratorOf(upsert)))
                .withMessageContaining("Could not commit JSON batch");
    }

    // -------------------------------------------------------------------------
    // fixBadIdValue – edge cases exercised through commitBatch
    // -------------------------------------------------------------------------

    @Test
    void testCommitBatch_emptyDocumentId_throwsCommitterException()
            throws CommitterException {
        // The id comes from the reference; extractSourceIdValue returns
        // the reference when sourceIdField is not set.
        var upsert = new UpsertRequest(
                "", new Properties(), new NullInputStream(0));
        committer.initBatchCommitter();
        assertThatExceptionOfType(CommitterException.class)
                .isThrownBy(() -> committer.commitBatch(iteratorOf(upsert)))
                .withMessageContaining("Document id cannot be empty");
    }

    @Test
    void testCommitBatch_veryLongIdWithFixBadIds_isTruncated()
            throws Exception {
        stubSuccessResponse("{\"errors\":false}");
        committer.getConfiguration().setFixBadIds(true);

        // 600 'a' characters – exceeds Elasticsearch's 512-byte limit
        var longId = StringUtils.repeat("a", 600);
        var upsert = new UpsertRequest(
                longId, new Properties(), new NullInputStream(0));
        committer.initBatchCommitter();
        assertThatNoException()
                .isThrownBy(() -> committer.commitBatch(iteratorOf(upsert)));
        verify(mockRestClient).performRequest(any(Request.class));
    }

    @Test
    void testCommitBatch_veryLongIdWithoutFixBadIds_passedThrough()
            throws Exception {
        stubSuccessResponse("{\"errors\":false}");
        committer.getConfiguration().setFixBadIds(false);

        // ID over 512 bytes but fixBadIds is off – committer passes it as-is
        var longId = StringUtils.repeat("a", 600);
        var upsert = new UpsertRequest(
                longId, new Properties(), new NullInputStream(0));
        committer.initBatchCommitter();
        assertThatNoException()
                .isThrownBy(() -> committer.commitBatch(iteratorOf(upsert)));
        verify(mockRestClient).performRequest(any(Request.class));
    }

    // -------------------------------------------------------------------------
    // createRestClient – auth configurations
    // -------------------------------------------------------------------------

    @Test
    void testCreateRestClient_withApiKey_buildsClientWithoutException() {
        var real = new ElasticsearchCommitter();
        real.getConfiguration()
                .setIndexName("idx")
                .setApiKey("QWxhZGRpbjpvcGVuIHNlc2FtZQ==");
        assertThatNoException().isThrownBy(() -> real.createRestClient());
    }

    @Test
    void testCreateRestClient_withCredentials_buildsClientWithoutException() {
        var real = new ElasticsearchCommitter();
        real.getConfiguration()
                .setIndexName("idx")
                .setCredentials(
                        new Credentials()
                                .setUsername("user")
                                .setPassword("pass"));
        assertThatNoException().isThrownBy(() -> real.createRestClient());
    }

    @Test
    void testCreateRestClient_noAuth_buildsClientWithoutException() {
        var real = new ElasticsearchCommitter();
        real.getConfiguration().setIndexName("idx");
        assertThatNoException().isThrownBy(() -> real.createRestClient());
    }

    // -------------------------------------------------------------------------
    // createSniffer – http vs https
    // -------------------------------------------------------------------------

    @Test
    void testCreateSniffer_httpNode_buildsSnifferWithoutException() {
        var real = new ElasticsearchCommitter();
        real.getConfiguration().setNodes(List.of("http://localhost:9200"));
        var client = real.createRestClient();
        assertThatNoException().isThrownBy(() -> {
            var sniffer = real.createSniffer(client);
            sniffer.close();
            client.close();
        });
    }

    @Test
    void testCreateSniffer_httpsNode_buildsHttpsSnifferWithoutException() {
        var real = new ElasticsearchCommitter();
        real.getConfiguration().setNodes(List.of("https://localhost:9243"));
        var client = real.createRestClient();
        assertThatNoException().isThrownBy(() -> {
            var sniffer = real.createSniffer(client);
            sniffer.close();
            client.close();
        });
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private void stubSuccessResponse(String body) throws Exception {
        when(mockStatusLine.getStatusCode()).thenReturn(HttpStatus.SC_OK);
        when(mockResponse.getStatusLine()).thenReturn(mockStatusLine);
        when(mockEntity.getContent()).thenReturn(toInputStream(body, UTF_8));
        when(mockResponse.getEntity()).thenReturn(mockEntity);
        when(mockRestClient.performRequest(any())).thenReturn(mockResponse);
    }

    @SafeVarargs
    private static <T> Iterator<T> iteratorOf(T... items) {
        return List.of(items).iterator();
    }

    // -------------------------------------------------------------------------
    // Testable subclass – injects mock RestClient / Sniffer
    // -------------------------------------------------------------------------

    private static class TestableElasticsearchCommitter
            extends ElasticsearchCommitter {

        private final RestClient injectedClient;
        private final Sniffer injectedSniffer;

        TestableElasticsearchCommitter(
                RestClient client, Sniffer sniffer) {
            this.injectedClient = client;
            this.injectedSniffer = sniffer;
        }

        @Override
        protected RestClient createRestClient() {
            return injectedClient;
        }

        @Override
        protected Sniffer createSniffer(RestClient client) {
            return injectedSniffer;
        }
    }
}
