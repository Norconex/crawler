/* Copyright 2026 Norconex Inc.
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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.xml.Xml;

@Timeout(30)
class CmisFetcherTest {

    @Test
    void testExtractPropertyValuesHandlesMultiValueAndUndefinedIds()
            throws Exception {
        var xml = new Xml("""
                <entry>
                  <object>
                    <properties>
                      <propertyString propertyDefinitionId="cmis:name">
                        <value>Doc A</value>
                      </propertyString>
                      <propertyString propertyDefinitionId="cmis:secondaryObjectTypeIds">
                        <value>P:cm:titled</value>
                        <value>P:cm:auditable</value>
                      </propertyString>
                      <propertyString>
                        <value>fallback</value>
                      </propertyString>
                      <propertyString propertyDefinitionId="cmis:empty">
                        <value>  </value>
                      </propertyString>
                    </properties>
                  </object>
                </entry>
                """);

        var values = CmisFetcher.extractPropertyValues(xml);

        assertThat(values)
                .containsKey("cmis:name")
                .containsKey("cmis:secondaryObjectTypeIds")
                .containsKey("undefined_property");
        assertThat(values.get("cmis:name")).containsExactly("Doc A");
        assertThat(values.get("cmis:secondaryObjectTypeIds"))
                .containsExactly("P:cm:titled", "P:cm:auditable");
        assertThat(values.get("undefined_property"))
                .containsExactly("fallback");
        assertThat(values).doesNotContainKey("cmis:empty");
    }

    @Test
    void testExtractAclEntriesHandlesPrincipalFallbackAndDedupes()
            throws Exception {
        var xml = new Xml("""
                <entry>
                  <object>
                    <acl>
                      <permission>
                        <principal><principalId>user-a</principalId></principal>
                        <permission>cmis:read</permission>
                        <permission>cmis:read</permission>
                        <permission>cmis:write</permission>
                      </permission>
                      <permission>
                        <principal><id>group-x</id></principal>
                        <permission>cmis:read</permission>
                      </permission>
                      <permission>
                        <principal>legacy-principal</principal>
                        <permission>cmis:all</permission>
                      </permission>
                      <permission>
                        <permission>cmis:ignored</permission>
                      </permission>
                    </acl>
                  </object>
                </entry>
                """);

        var acl = CmisFetcher.extractAclEntries(xml);

        assertThat(acl.get("acl.cmis:read"))
                .containsExactlyInAnyOrder("user-a", "group-x");
        assertThat(acl.get("acl.cmis:write"))
                .containsExactly("user-a");
        assertThat(acl.get("acl.cmis:all"))
                .containsExactly("legacy-principal");
        assertThat(acl).doesNotContainKey("acl.cmis:ignored");
    }
}
