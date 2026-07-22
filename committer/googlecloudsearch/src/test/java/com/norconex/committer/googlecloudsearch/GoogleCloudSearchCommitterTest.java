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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.cloudsearch.v1.CloudSearch;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.MetadataMapping;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.StructuredDataMapping;

class GoogleCloudSearchCommitterTest {

    @Test
    void initBatchCommitterRejectsMissingSecretKeyPath() {
        var committer = new GoogleCloudSearchCommitter();
        committer.getConfiguration().setDataSourceId("ds");

        assertThatThrownBy(committer::initBatchCommitter)
                .isInstanceOf(CommitterException.class)
                .hasMessageContaining("secretKeyPath");
    }

    @Test
    void initBatchCommitterRejectsMissingDataSourceId() {
        var committer = new GoogleCloudSearchCommitter();
        committer.getConfiguration().setSecretKeyPath("missing.json");

        assertThatThrownBy(committer::initBatchCommitter)
                .isInstanceOf(CommitterException.class)
                .hasMessageContaining("dataSourceId");
    }

    @Test
    void initBatchCommitterRejectsMetadataMappingWithoutTargetField() {
        var committer = new GoogleCloudSearchCommitter();
        committer.getConfiguration()
                .setSecretKeyPath("missing.json")
                .setDataSourceId("ds")
                .setMetadataMappings(List.of(new MetadataMapping()));

        assertThatThrownBy(committer::initBatchCommitter)
                .isInstanceOf(CommitterException.class)
                .hasMessageContaining("metadata mapping");
    }

    @Test
    void initBatchCommitterRejectsStructuredDataMappingWithoutField() {
        var committer = new GoogleCloudSearchCommitter();
        committer.getConfiguration()
                .setSecretKeyPath("missing.json")
                .setDataSourceId("ds")
                .setStructuredDataMappings(
                        List.of(new StructuredDataMapping()));

        assertThatThrownBy(committer::initBatchCommitter)
                .isInstanceOf(CommitterException.class)
                .hasMessageContaining("structured data mapping");
    }

    @Test
    void initBatchCommitterDefaultsConnectorNameWhenBlank() {
        var committer = new GoogleCloudSearchCommitter();
        committer.getConfiguration()
                .setSecretKeyPath("missing.json")
                .setDataSourceId("ds")
                .setApplicationName("My App")
                .setConnectorName(" ");

        assertThatThrownBy(committer::initBatchCommitter)
                .isInstanceOf(CommitterException.class)
                .hasMessageContaining(
                        "Could not initialize Google Cloud Search client");
        assertThat(committer.getConfiguration().getConnectorName())
                .isEqualTo("My App");
    }

    @Test
    void commitBatchDelegatesToClient() throws Exception {
        var committer = new GoogleCloudSearchCommitter();
        var client = new SpyClient();
        setClient(committer, client);

        Iterator<CommitterRequest> iterator = Collections.emptyIterator();
        committer.commitBatch(iterator);

        assertThat(client.postCalled).isTrue();
        assertThat(client.postIterator).isSameAs(iterator);
    }

    @Test
    void closeBatchCommitterClosesClientAndClearsReference() throws Exception {
        var committer = new GoogleCloudSearchCommitter();
        var client = new SpyClient();
        setClient(committer, client);

        committer.closeBatchCommitter();

        assertThat(client.closeCalled).isTrue();
        assertThat(getClient(committer)).isNull();

        assertThatCode(committer::closeBatchCommitter)
                .doesNotThrowAnyException();
    }

    private static void setClient(
            GoogleCloudSearchCommitter committer,
            GoogleCloudSearchClient client) throws Exception {
        var field = clientField();
        field.set(committer, client);
    }

    private static Object getClient(
            GoogleCloudSearchCommitter committer) throws Exception {
        return clientField().get(committer);
    }

    private static Field clientField() throws Exception {
        Field field = GoogleCloudSearchCommitter.class
                .getDeclaredField("client");
        field.setAccessible(true);
        return field;
    }

    private static final class SpyClient extends GoogleCloudSearchClient {
        private boolean postCalled;
        private boolean closeCalled;
        private Iterator<CommitterRequest> postIterator;

        private SpyClient() {
            super(
                    new GoogleCloudSearchCommitterConfig()
                            .setSecretKeyPath("unused.json")
                            .setDataSourceId("ds")
                            .setApplicationName("app")
                            .setConnectorName("app"),
                    new CloudSearch.Builder(
                            new MockHttpTransport(),
                            GsonFactory.getDefaultInstance(),
                            noOpInitializer())
                                    .setApplicationName("app")
                                    .build(),
                    () -> 0L);
        }

        @Override
        void post(Iterator<CommitterRequest> it) {
            postCalled = true;
            postIterator = it;
        }

        @Override
        void close() {
            closeCalled = true;
        }

        private static HttpRequestInitializer noOpInitializer() {
            return request -> {
                // NOOP
            };
        }
    }
}
