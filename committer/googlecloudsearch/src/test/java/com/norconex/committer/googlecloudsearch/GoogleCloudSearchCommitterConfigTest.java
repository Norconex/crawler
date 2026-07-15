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

import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.text.Format;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.committer.core.batch.queue.impl.FsQueue;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.AclInheritanceMapping;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.AclInheritanceType;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.AclMapping;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.AclTarget;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.PrincipalType;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.UploadFormat;
import com.norconex.commons.lang.ResourceLoader;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.map.PropertyMatcher;
import com.norconex.commons.lang.text.TextMatcher;

@Timeout(30)
class GoogleCloudSearchCommitterConfigTest {

        @Test
        void testWriteRead() throws Exception {
                var q = new FsQueue();
                q.getConfiguration()
                                .setBatchSize(10)
                                .setMaxPerFolder(5);

                var c = new GoogleCloudSearchCommitter();
                c.getConfiguration()
                                .setSecretKeyPath("/path/to/service-account.json")
                                .setDataSourceId("dataSourceId")
                                .setUploadFormat(UploadFormat.TEXT)
                                .setApiEndpoint("https://mock.local/")
                                .setApplicationName("applicationName")
                                .setConnectorName("connectorName")
                                .setSourceIdField("sourceIdField")
                                .setKeepSourceIdField(true)
                                .setTitleField("titleField")
                                .setObjectTypeField("objectTypeField")
                                .setObjectTypeDefaultValue("webpage")
                                .setUpdateTimeField("updateTimeField")
                                .setCreateTimeField("createTimeField")
                                .setContainerNameField("containerNameField")
                                .setContentLanguageField("contentLanguageField")
                                .setContentLanguageDefaultValue("en-US")
                                .setSourceRepositoryUrlField("sourceRepositoryUrlField")
                                .setTypedStructuredData(true)
                                .setAclMappings(
                                                List.of(
                                                                new AclMapping()
                                                                                .setFromField("acl.reader.user")
                                                                                .setTarget(AclTarget.READERS)
                                                                                .setPrincipalType(PrincipalType.USER),
                                                                new AclMapping()
                                                                                .setFromField("acl.owner")
                                                                                .setTarget(AclTarget.OWNERS)
                                                                                .setPrincipalType(
                                                                                                PrincipalType.CUSTOMER)))
                                .setAclInheritance(
                                                new AclInheritanceMapping()
                                                                .setFromField("parentReference")
                                                                .setAclInheritanceType(
                                                                                AclInheritanceType.BOTH_PERMIT))
                                .setQueue(q)
                                .setFieldMapping("subject", "title")
                                .addRestriction(
                                                new PropertyMatcher(
                                                                TextMatcher.basic("document.reference"),
                                                                TextMatcher.wildcard("*.pdf")));

                assertThatNoException().isThrownBy(
                                () -> BeanMapper.DEFAULT.assertWriteRead(c));
        }

        @Test
        void testValidation() throws IOException {
                Assertions.assertDoesNotThrow(() -> {
                        try (var r = ResourceLoader.getXmlReader(this.getClass())) {
                                BeanMapper.DEFAULT.read(
                                                GoogleCloudSearchCommitter.class, r, Format.XML);
                        }
                });
        }
}
