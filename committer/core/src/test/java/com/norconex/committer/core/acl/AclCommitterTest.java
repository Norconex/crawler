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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.norconex.committer.core.Committer;
import com.norconex.committer.core.CommitterContext;
import com.norconex.committer.core.CommitterEvent;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.impl.MemoryCommitter;
import com.norconex.committer.core.service.CommitterService;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.commons.lang.event.Event;
import com.norconex.commons.lang.event.EventListener;
import com.norconex.commons.lang.event.EventManager;
import com.norconex.commons.lang.map.Properties;

/**
 * How a committer sends, or withholds, a document carrying an ACL.
 */
class AclCommitterTest {

    private final List<Event> events = new ArrayList<>();

    private MemoryCommitter committer(AclFormat format,
            AclPartialPolicy partial) throws CommitterException {
        var c = new MemoryCommitter();
        c.getConfiguration().getAcl().setFormat(format).setPartial(partial);
        var em = new EventManager();
        em.addListener((EventListener<Event>) events::add);
        c.init(CommitterContext.builder().setEventManager(em).build());
        return c;
    }

    private static UpsertRequest doc(Acl acl) {
        var meta = new Properties();
        meta.add("title", "Q3 forecast");
        if (acl != null) {
            AclCodec.write(meta, acl);
        }
        return new UpsertRequest("file:///sales/q3.xlsx", meta, null);
    }

    @Test
    void aDocumentWithoutAclIsSentAsItIs() throws CommitterException {
        var c = committer(AclFormat.FLAT, null);
        c.upsert(doc(null));
        assertThat(c.getUpsertRequests()).hasSize(1);
        assertThat(c.getUpsertRequests().get(0).getMetadata().keySet())
                .containsExactly("title");
    }

    @Test
    void jsonIsTheDefaultAndSendsTheFieldUntouched()
            throws CommitterException {
        var c = committer(null, null);
        c.upsert(doc(AclTest.twoLevels()));
        var sent = c.getUpsertRequests().get(0).getMetadata();
        assertThat(sent.getString(Acl.FIELD))
                .isEqualTo(AclCodec.toJson(AclTest.twoLevels()));
    }

    @Test
    void flatReplacesTheJsonWithPlainFields() throws CommitterException {
        var c = committer(AclFormat.FLAT, null);
        c.getConfiguration().getAcl().setFlatPrefix("perm");
        c.upsert(doc(AclTest.twoLevels()));
        var sent = c.getUpsertRequests().get(0).getMetadata();
        assertThat(sent.containsKey(Acl.FIELD)).isFalse();
        assertThat(sent.getStrings("perm.file.deny"))
                .containsExactly("group:ad:CORP:Interns");
        assertThat(sent.getString("title")).isEqualTo("Q3 forecast");
    }

    @Test
    void noneRemovesTheAcl() throws CommitterException {
        var c = committer(AclFormat.NONE, null);
        c.upsert(doc(AclTest.twoLevels()));
        assertThat(c.getUpsertRequests().get(0).getMetadata().keySet())
                .containsExactly("title");
    }

    @Test
    void aPartialAclWithholdsTheDocumentAndRemovesItFromTheDestination()
            throws CommitterException {
        var c = committer(AclFormat.FLAT, null);
        var req = doc(AclTest.twoLevels().withStatus(AclStatus.PARTIAL));

        c.upsert(req);

        assertThat(c.getUpsertRequests()).isEmpty();
        // Whatever was sent before must not stay there with permissions that
        // can no longer be vouched for.
        assertThat(c.getDeleteRequests())
                .extracting(DeleteRequest::getReference)
                .containsExactly("file:///sales/q3.xlsx");
        assertThat(c.getDeleteRequests().get(0).getMetadata()
                .containsKey(Acl.FIELD)).isFalse();
        assertThat(req.getWithheldReason()).contains("only be read in part");
        assertThat(events)
                .filteredOn(e -> CommitterEvent.COMMITTER_UPSERT_WITHHELD
                        .equals(e.getName()))
                .singleElement()
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("file:///sales/q3.xlsx")
                        .contains("only be read in part"));
    }

    @Test
    void aPartialAclIsSentWhenTheUserSaysSo() throws CommitterException {
        var c = committer(AclFormat.FLAT, AclPartialPolicy.SEND);
        var req = doc(AclTest.twoLevels().withStatus(AclStatus.PARTIAL));
        c.upsert(req);
        assertThat(c.getUpsertRequests()).hasSize(1);
        assertThat(req.getWithheldReason()).isNull();
    }

    @Test
    void permissionsThatCouldNotBeReadAreNeverSentAsOpen()
            throws CommitterException {
        // Even with SEND: "none" is not "partial", it is nothing at all.
        var c = committer(AclFormat.FLAT, AclPartialPolicy.SEND);
        var req = doc(Acl.none(AclScope.FULL, "file permissions: denied"));
        c.upsert(req);
        assertThat(c.getUpsertRequests()).isEmpty();
        assertThat(req.getWithheldReason())
                .contains("could not be read")
                .contains("file permissions: denied");
    }

    @Test
    void anUnreadableOrNewerAclIsWithheld() throws CommitterException {
        var c = committer(AclFormat.JSON, null);
        var unreadable = doc(null);
        unreadable.getMetadata().set(Acl.FIELD, "{\"v\":1,\"levels\":[{"
                + "\"name\":\"f\",\"entries\":[{\"kind\":\"robot\","
                + "\"id\":\"r\",\"access\":\"allow\"}]}]}");
        c.upsert(unreadable);
        assertThat(unreadable.getWithheldReason()).contains("not readable");

        var newer = doc(null);
        newer.getMetadata().set(Acl.FIELD, AclCodec.toJson(new Acl(2,
                AclStatus.COMPLETE, AclScope.FULL, null, List.of(), List.of(),
                null)));
        c.upsert(newer);
        assertThat(newer.getWithheldReason()).contains("version 2");
        assertThat(c.getUpsertRequests()).isEmpty();
    }

    @Test
    void aDeletionIsNeverHeldBackByPermissions() throws CommitterException {
        var c = committer(AclFormat.FLAT, null);
        var meta = new Properties();
        AclCodec.write(meta,
                AclTest.twoLevels().withStatus(AclStatus.PARTIAL));
        c.delete(new DeleteRequest("file:///sales/q3.xlsx", meta));
        assertThat(c.getDeleteRequests()).hasSize(1);
        assertThat(c.getDeleteRequests().get(0).getMetadata()
                .containsKey(Acl.FIELD)).isFalse();
    }

    @Test
    void aCommitterWithoutAccessControlOfItsOwnRefusesNative() {
        assertThatThrownBy(() -> committer(AclFormat.NATIVE, null))
                .isInstanceOf(CommitterException.class)
                .hasMessageContaining("NATIVE");
    }

    @Test
    void theServiceTellsItsCallerWhichCommittersWithheldADocument()
            throws CommitterException {
        var flat = committer(AclFormat.FLAT, null);
        var send = committer(AclFormat.FLAT, AclPartialPolicy.SEND);
        var service = CommitterService.<Properties>builder()
                .committers(List.of(flat, send))
                .upsertRequestBuilder(
                        m -> new UpsertRequest("doc-1", m, null))
                .deleteRequestBuilder(m -> new DeleteRequest("doc-1", m))
                .build();
        var meta = new Properties();
        AclCodec.write(meta,
                AclTest.twoLevels().withStatus(AclStatus.PARTIAL));
        List<Committer> withheldBy = new ArrayList<>();

        var accepted = service.upsert(meta, (cmt, why) -> withheldBy.add(cmt));

        assertThat(accepted).containsExactly(flat, send);
        assertThat(withheldBy).containsExactly(flat);
        assertThat(send.getUpsertRequests()).hasSize(1);
    }

    @Test
    void theAclSettingsAreWrittenAndReadBackWithTheConfiguration() {
        var c = new MemoryCommitter();
        c.getConfiguration().getAcl()
                .setFormat(AclFormat.FLAT)
                .setPartial(AclPartialPolicy.SEND)
                .setFlatPrefix("perm");
        for (Format format : Format.values()) {
            BeanMapper.DEFAULT.assertWriteRead(c, format);
        }
    }
}
