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
package com.norconex.committer.googlecloudsearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.norconex.committer.core.acl.Acl;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.committer.core.acl.AclLevel;

class GoogleCloudSearchAclsTest {

    private static Acl acl(List<AclEntry> entries, List<AclEntry> owners) {
        return Acl.complete(List.of(new AclLevel("file", entries)), owners,
                null);
    }

    @Test
    void usersAndGroupsByAddressBecomeReadersDeniedReadersAndOwners() {
        var acl = acl(
                List.of(
                        AclEntry.allow(AclKind.GROUP, null, "sales@acme.com"),
                        AclEntry.allow(AclKind.USER, null, "pat@acme.com"),
                        AclEntry.deny(AclKind.USER, null, "lee@acme.com")),
                List.of(AclEntry.owner(AclKind.USER, null, "boss@acme.com")));

        assertThat(GoogleCloudSearchAcls.problem(acl)).isNull();
        var item = GoogleCloudSearchAcls.toItemAcl(acl);
        assertThat(item.getReaders()).extracting(
                r -> r.getGsuitePrincipal().getGsuiteGroupEmail(),
                r -> r.getGsuitePrincipal().getGsuiteUserEmail())
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple(
                                "sales@acme.com", null),
                        org.assertj.core.api.Assertions.tuple(
                                null, "pat@acme.com"));
        assertThat(item.getDeniedReaders().get(0).getGsuitePrincipal()
                .getGsuiteUserEmail()).isEqualTo("lee@acme.com");
        assertThat(item.getOwners().get(0).getGsuitePrincipal()
                .getGsuiteUserEmail()).isEqualTo("boss@acme.com");
    }

    @Test
    void everyoneIsTheWholeDomain() {
        var acl = acl(List.of(AclEntry.allowSpecial(
                AclEntry.SPECIAL_EVERYONE)), List.of());

        assertThat(GoogleCloudSearchAcls.problem(acl)).isNull();
        assertThat(GoogleCloudSearchAcls.toItemAcl(acl).getReaders().get(0)
                .getGsuitePrincipal().getGsuiteDomain()).isTrue();
    }

    @Test
    void whatCloudSearchCannotExpressIsReportedNotApproximated() {
        // a principal with no Google account address
        assertThat(GoogleCloudSearchAcls.problem(acl(List.of(
                AclEntry.allow(AclKind.GROUP, "ad:CORP", "Sales")),
                List.of()))).contains("group:ad:CORP:Sales");
        // nobody may read it
        assertThat(GoogleCloudSearchAcls.problem(acl(List.of(
                AclEntry.deny(AclKind.USER, null, "lee@acme.com")),
                List.of()))).contains("no one");
        // two levels (share and file)
        var two = Acl.complete(List.of(
                new AclLevel("share", List.of(AclEntry.allow(
                        AclKind.USER, null, "pat@acme.com"))),
                new AclLevel("file", List.of(AclEntry.allow(
                        AclKind.USER, null, "pat@acme.com")))),
                List.of(), null);
        assertThat(GoogleCloudSearchAcls.problem(two)).contains("single level");
        // an owner without an address
        assertThat(GoogleCloudSearchAcls.problem(acl(List.of(
                AclEntry.allow(AclKind.USER, null, "pat@acme.com")),
                List.of(AclEntry.owner(AclKind.USER, "ad:CORP", "boss")))))
                        .contains("owner");
    }
}
