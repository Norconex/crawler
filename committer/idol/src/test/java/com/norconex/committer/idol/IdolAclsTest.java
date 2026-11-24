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
package com.norconex.committer.idol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.norconex.committer.core.acl.Acl;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.committer.core.acl.AclLevel;

class IdolAclsTest {

    private static Acl acl(AclEntry... entries) {
        return Acl.complete(
                List.of(new AclLevel("file", List.of(entries))), List.of(),
                null);
    }

    @Test
    void ntSyntaxHasFourSectionsAndTheEveryoneFlag() {
        var acl = acl(
                AclEntry.allow(AclKind.USER, null, "pat"),
                AclEntry.allow(AclKind.GROUP, null, "sales"),
                AclEntry.allow(AclKind.GROUP, null, "ops"),
                AclEntry.deny(AclKind.USER, null, "lee"));

        assertThat(IdolAcls.problem(acl, IdolAclSyntax.NT)).isNull();
        assertThat(IdolAcls.write(acl, IdolAclSyntax.NT))
                .isEqualTo("0:U:pat:G:ops,sales:NU:lee:NG:");
        assertThat(IdolAcls.write(
                acl(AclEntry.allowSpecial(AclEntry.SPECIAL_EVERYONE)),
                IdolAclSyntax.NT)).isEqualTo("1:U::G::NU::NG:");
    }

    @Test
    void theSimpleSyntaxIsUsersAndGroupsOnly() {
        var acl = acl(AclEntry.allow(AclKind.USER, null, "pat"),
                AclEntry.allow(AclKind.GROUP, null, "sales"));
        assertThat(IdolAcls.write(acl, IdolAclSyntax.SIMPLE))
                .isEqualTo(":U:pat:G:sales");
        assertThat(IdolAcls.problem(
                acl(AclEntry.deny(AclKind.USER, null, "lee")),
                IdolAclSyntax.SIMPLE)).contains("denial");
    }

    @Test
    void whatIdolCannotExpressIsReportedNotApproximated() {
        assertThat(IdolAcls.problem(
                acl(AclEntry.allow(AclKind.ROLE, null, "admin")),
                IdolAclSyntax.NT)).contains("role");
        assertThat(IdolAcls.problem(
                acl(AclEntry.allow(AclKind.USER, null, "a,b")),
                IdolAclSyntax.NT)).contains("comma");
        var two = Acl.complete(List.of(
                new AclLevel("share", List.of()),
                new AclLevel("file", List.of())), List.of(), null);
        assertThat(IdolAcls.problem(two, IdolAclSyntax.NT))
                .contains("single level");
    }
}
