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
package com.norconex.committer.core.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.map.Properties;

class AclTest {

    static Acl twoLevels() {
        return Acl.complete(List.of(
                new AclLevel("share", List.of(
                        AclEntry.allow(AclKind.GROUP, "ad:CORP", "Sales"))),
                new AclLevel("file", List.of(
                        AclEntry.deny(AclKind.GROUP, "ad:CORP", "Interns"),
                        AclEntry.allowSpecial(AclEntry.SPECIAL_EVERYONE),
                        AclEntry.allow(AclKind.GROUP, "ad:CORP", "Sales")))),
                List.of(AclEntry.owner(AclKind.USER, "ad:CORP", "pascal")),
                Map.of("zeta", "last", "alpha", List.of("first")));
    }

    @Test
    void theSamePermissionsAlwaysGiveTheSameTextAndReadBack() {
        var acl = twoLevels();
        var json = AclCodec.toJson(acl);

        // Entries are sorted (allow before deny), map keys too, so the text
        // does not depend on the order a source gave them in.
        assertThat(json).isEqualTo("{\"v\":1,\"status\":\"complete\","
                + "\"scope\":\"full\",\"levels\":["
                + "{\"name\":\"share\",\"entries\":[{\"kind\":\"group\","
                + "\"dir\":\"ad:CORP\",\"id\":\"Sales\",\"access\":\"allow\"}]},"
                + "{\"name\":\"file\",\"entries\":["
                + "{\"kind\":\"group\",\"dir\":\"ad:CORP\",\"id\":\"Sales\","
                + "\"access\":\"allow\"},"
                + "{\"kind\":\"special\",\"id\":\"everyone\","
                + "\"access\":\"allow\"},"
                + "{\"kind\":\"group\",\"dir\":\"ad:CORP\",\"id\":\"Interns\","
                + "\"access\":\"deny\"}]}],"
                + "\"owners\":[{\"kind\":\"user\",\"dir\":\"ad:CORP\","
                + "\"id\":\"pascal\"}],"
                + "\"native\":{\"alpha\":[\"first\"],\"zeta\":\"last\"}}");
        assertThat(AclCodec.fromJson(json)).isEqualTo(acl);
        // Duplicates are dropped.
        var level = new AclLevel("file", List.of(
                AclEntry.allow(AclKind.USER, "ad:CORP", "jane"),
                AclEntry.allow(AclKind.USER, "ad:CORP", "jane")));
        assertThat(level.entries()).hasSize(1);
    }

    @Test
    void anAclWithoutLevelsOrOwnersReadsBack() {
        var none = Acl.none(AclScope.BASIC, null);
        var json = AclCodec.toJson(none);
        assertThat(json).isEqualTo(
                "{\"v\":1,\"status\":\"none\",\"scope\":\"basic\"}");
        assertThat(AclCodec.fromJson(json)).isEqualTo(none);
    }

    @Test
    void anUnknownKindOrAccessIsNotReadAsSomethingElse() {
        assertThatThrownBy(() -> AclCodec.fromJson("{\"v\":1,"
                + "\"status\":\"complete\",\"scope\":\"full\",\"levels\":["
                + "{\"name\":\"file\",\"entries\":[{\"kind\":\"robot\","
                + "\"id\":\"r2\",\"access\":\"allow\"}]}]}"))
                        .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AclCodec.fromJson("{\"v\":1,"
                + "\"status\":\"complete\",\"scope\":\"full\",\"levels\":["
                + "{\"name\":\"file\",\"entries\":[{\"kind\":\"user\","
                + "\"id\":\"jane\",\"access\":\"maybe\"}]}]}"))
                        .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AclCodec.fromJson("not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDocumentHasAtMostOneAcl() {
        var meta = new Properties();
        assertThat(AclCodec.read(meta)).isEmpty();
        AclCodec.write(meta, twoLevels());
        assertThat(AclCodec.read(meta)).contains(twoLevels());
        meta.add(Acl.FIELD, "{}");
        assertThatThrownBy(() -> AclCodec.read(meta))
                .hasMessageContaining("2 values");
    }

    @Test
    void flatFieldsHoldOneAllowAndOneDenyFieldPerLevel() {
        var fields = AclFlattener.flatten(twoLevels(), "acl");

        assertThat(fields.getStrings("acl.levels"))
                .containsExactly("share", "file");
        assertThat(fields.getStrings("acl.share.allow"))
                .containsExactly("group:ad:CORP:Sales");
        assertThat(fields.getStrings("acl.file.allow"))
                .containsExactly("group:ad:CORP:Sales", "special:everyone");
        assertThat(fields.getStrings("acl.file.deny"))
                .containsExactly("group:ad:CORP:Interns");
        assertThat(fields.getStrings("acl.owners"))
                .containsExactly("user:ad:CORP:pascal");
        assertThat(fields.containsKey("acl.share.deny")).isFalse();
    }

    @Test
    void levelsOfTheSameNameStayApartWhenFlat() {
        var acl = Acl.complete(List.of(
                new AclLevel("folder", List.of(
                        AclEntry.allow(AclKind.USER, "unix:h", "1000"))),
                new AclLevel("folder", List.of(
                        AclEntry.allow(AclKind.UNDEFINED, "cmis:r", "x")))),
                List.of(), null);
        var fields = AclFlattener.flatten(acl, "p");
        assertThat(fields.getStrings("p.levels"))
                .containsExactly("folder", "folder2");
        assertThat(fields.getStrings("p.folder2.allow"))
                .containsExactly("undefined:cmis:r:x");
    }
}
