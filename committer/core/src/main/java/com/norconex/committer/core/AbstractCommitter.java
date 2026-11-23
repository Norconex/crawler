/* Copyright 2020-2024 Norconex Inc.
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
package com.norconex.committer.core;

import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.event.Level;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.norconex.committer.core.acl.Acl;
import com.norconex.committer.core.acl.AclCodec;
import com.norconex.committer.core.acl.AclCommitterConfig;
import com.norconex.committer.core.acl.AclFlattener;
import com.norconex.committer.core.acl.AclFormat;
import com.norconex.committer.core.acl.AclPartialPolicy;
import com.norconex.committer.core.acl.AclStatus;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.map.Properties;

import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;

/**
 * <p>
 * A base implementation taking care of basic plumbing, such as
 * firing main Committer events (including exceptions),
 * storing the Committer context (available via {@link #getCommitterContext()}),
 * and adding support for filtering unwanted requests.
 * </p>
 *
 * <h2>Restricting committer to specific documents</h2>
 * <p>
 * Optionally apply a committer only to certain type of documents.
 * Documents are restricted based on their
 * metadata field names and values. This option can be used to
 * perform document routing when you have multiple committers defined.
 * </p>
 *
 * <h2>Field mappings</h2>
 * <p>
 * By default, this abstract class applies field mappings for metadata fields,
 * but leaves the document reference and content (input stream) for concrete
 * implementations to handle. In other words, they only apply to
 * a committer request metadata.
 * Field mappings are performed on committer requests before upserts and
 * deletes are actually performed.
 * </p>
 * @param <T> The type of the committer configuration class
 */
@EqualsAndHashCode
@ToString
public abstract class AbstractCommitter<T extends BaseCommitterConfig>
        implements Committer, Configurable<T> {

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @JsonIgnore
    private CommitterContext committerContext;

    @Override
    public final void init(@NonNull CommitterContext committerContext)
            throws CommitterException {
        this.committerContext = committerContext;
        fireInfo(CommitterEvent.COMMITTER_INIT_BEGIN);
        try {
            if (aclFormat() == AclFormat.NATIVE && !supportsNativeAcl()) {
                throw new CommitterException(getClass().getSimpleName()
                        + " cannot send access control lists in the "
                        + "NATIVE format: its destination has no access "
                        + "control of its own. Use JSON, FLAT or NONE.");
            }
            doInit();
        } catch (CommitterException | RuntimeException e) {
            fireError(CommitterEvent.COMMITTER_INIT_ERROR, e);
            throw e;
        }
        fireInfo(CommitterEvent.COMMITTER_INIT_END);
    }

    @Override
    public boolean accept(CommitterRequest request) throws CommitterException {
        try {
            if (getConfiguration().getRestrictions().isEmpty()
                    || getConfiguration().getRestrictions().matches(
                            request.getMetadata())) {
                fireInfo(CommitterEvent.COMMITTER_ACCEPT_YES);
                return true;
            }
            fireInfo(CommitterEvent.COMMITTER_ACCEPT_NO);
        } catch (RuntimeException e) {
            fireError(CommitterEvent.COMMITTER_ACCEPT_ERROR, e);
            throw e;
        }
        return false;
    }

    @Override
    public final void upsert(
            UpsertRequest upsertRequest) throws CommitterException {
        fireInfo(CommitterEvent.COMMITTER_UPSERT_BEGIN, upsertRequest);
        try {
            var withheld = applyAcl(upsertRequest);
            if (withheld != null) {
                withhold(upsertRequest, withheld);
                return;
            }
            applyFieldMappings(upsertRequest);
            doUpsert(upsertRequest);
        } catch (CommitterException | RuntimeException e) {
            fireError(CommitterEvent.COMMITTER_UPSERT_ERROR, upsertRequest, e);
            throw e;
        }
        fireInfo(CommitterEvent.COMMITTER_UPSERT_END, upsertRequest);
    }

    @Override
    public final void delete(
            DeleteRequest deleteRequest) throws CommitterException {
        fireInfo(CommitterEvent.COMMITTER_DELETE_BEGIN, deleteRequest);
        try {
            // A deletion is never held back by permissions; only the JSON
            // format keeps the ACL field on what is sent.
            if (aclFormat() != AclFormat.JSON) {
                deleteRequest.getMetadata().remove(Acl.FIELD);
            }
            applyFieldMappings(deleteRequest);
            doDelete(deleteRequest);
        } catch (CommitterException | RuntimeException e) {
            fireError(CommitterEvent.COMMITTER_DELETE_ERROR, deleteRequest, e);
            throw e;
        }
        fireInfo(CommitterEvent.COMMITTER_DELETE_END, deleteRequest);
    }

    /**
     * The format documents' ACLs are sent in: the configured one, or this
     * committer's default when none is.
     * @return the ACL format
     */
    protected final AclFormat aclFormat() {
        var configured = getConfiguration().getAcl().getFormat();
        if (configured != null) {
            return configured;
        }
        return supportsNativeAcl() ? AclFormat.NATIVE : defaultAclFormat();
    }

    /**
     * Whether this committer's destination has access control of its own,
     * that this committer writes from a document's ACL (the
     * {@link AclFormat#NATIVE} format). A committer that returns
     * {@code true} reads the ACL with {@link AclCodec#read(Properties)},
     * writes it its own way, and removes the {@value Acl#FIELD} field from
     * what it sends. Default is {@code false}.
     * @return {@code true} if the destination has its own access control
     */
    protected boolean supportsNativeAcl() {
        return false;
    }

    /**
     * The ACL format used when none is configured, for a committer whose
     * destination has no access control of its own. Default is
     * {@link AclFormat#JSON}; a committer whose destination is searched by
     * field values (rather than holding documents as JSON) overrides it with
     * {@link AclFormat#FLAT}.
     * @return the default ACL format
     */
    protected AclFormat defaultAclFormat() {
        return AclFormat.JSON;
    }

    /**
     * In the {@link AclFormat#NATIVE} format, why the destination cannot
     * express a document's ACL (for example several levels, or a principal
     * it has no equivalent for), so the document is withheld rather than
     * sent with access it did not have. Default is {@code null}.
     * @param acl the document's ACL
     * @return the reason, or {@code null} if the destination can express it
     */
    protected String nativeAclProblem(Acl acl) {
        return null;
    }

    /**
     * Prepares a document's ACL to be sent in the format configured.
     * @param req the document
     * @return why the document must be withheld, or {@code null} to send it
     */
    private String applyAcl(UpsertRequest req) {
        var meta = req.getMetadata();
        if (!meta.containsKey(Acl.FIELD)) {
            return null;
        }
        var format = aclFormat();
        if (format == AclFormat.NONE) {
            meta.remove(Acl.FIELD);
            return null;
        }
        Acl acl;
        try {
            acl = AclCodec.read(meta).orElseThrow();
        } catch (IllegalArgumentException e) {
            return "its access control list is not readable ("
                    + e.getMessage() + ")";
        }
        if (acl.version() != Acl.VERSION) {
            return "its access control list is of version "
                    + acl.version() + ", this committer reads version "
                    + Acl.VERSION;
        }
        if (acl.status() == AclStatus.NONE) {
            return "its permissions were asked for but could not be read";
        }
        if (acl.status() == AclStatus.PARTIAL
                && getConfiguration().getAcl()
                        .getPartial() != AclPartialPolicy.SEND) {
            return "its permissions could only be read in part (the "
                    + "committer's ACL \"partial\" setting can be SEND, to "
                    + "send what was read)";
        }
        if (format == AclFormat.FLAT) {
            meta.remove(Acl.FIELD);
            var prefix = StringUtils.defaultIfBlank(
                    getConfiguration().getAcl().getFlatPrefix(),
                    AclCommitterConfig.DEFAULT_FLAT_PREFIX);
            AclFlattener.flatten(acl, prefix).forEach(meta::addList);
        } else if (format == AclFormat.NATIVE) {
            return nativeAclProblem(acl);
        }
        return null;
    }

    /**
     * Holds a document back: it is not sent, any version sent before is
     * removed from the destination (so it does not stay there with
     * permissions that can no longer be vouched for), and the request says
     * why, for the caller to try the document again later.
     */
    private void withhold(UpsertRequest req, String reason)
            throws CommitterException {
        req.setWithheldReason(reason);
        fire(CommitterEvent.builder()
                .name(CommitterEvent.COMMITTER_UPSERT_WITHHELD)
                .source(this)
                .request(req)
                .message("Withheld \"" + req.getReference() + "\": "
                        + reason + ".")
                .build(),
                Level.WARN);
        var delete = new DeleteRequest(req.getReference(), req.getMetadata());
        delete.getMetadata().remove(Acl.FIELD);
        applyFieldMappings(delete);
        doDelete(delete);
    }

    protected void applyFieldMappings(CommitterRequest req) {
        var props = new Properties();
        for (Entry<String, List<String>> en : req.getMetadata().entrySet()) {
            var fromField = en.getKey();
            if (getConfiguration().getFieldMappings().containsKey(fromField)) {
                var toField = getConfiguration().getFieldMappings().get(
                        fromField);
                // if target undefined, do not set
                if (StringUtils.isNotBlank(toField)) {
                    props.addList(toField, en.getValue());
                }
            } else {
                props.addList(fromField, en.getValue());
            }
        }
        req.getMetadata().clear();
        req.getMetadata().putAll(props);
    }

    @Override
    public final void close() throws CommitterException {
        fireInfo(CommitterEvent.COMMITTER_CLOSE_BEGIN);
        try {
            doClose();
        } catch (CommitterException | RuntimeException e) {
            fireError(CommitterEvent.COMMITTER_CLOSE_ERROR, e);
            throw e;
        }
        fireInfo(CommitterEvent.COMMITTER_CLOSE_END);
    }

    @Override
    public final void clean() throws CommitterException {
        fireInfo(CommitterEvent.COMMITTER_CLEAN_BEGIN);
        try {
            doClean();
        } catch (CommitterException | RuntimeException e) {
            fireError(CommitterEvent.COMMITTER_CLEAN_ERROR, e);
            throw e;
        }
        fireInfo(CommitterEvent.COMMITTER_CLEAN_END);
    }

    public CommitterContext getCommitterContext() {
        return committerContext;
    }

    /**
     * Subclasses can perform additional initialization by overriding this
     * method. Default implementation does nothing. The
     * {@link CommitterContext} will be initialized when invoking
     * {@link #getCommitterContext()}
     * @throws CommitterException error initializing
     */
    protected abstract void doInit()
            throws CommitterException;

    protected abstract void doUpsert(UpsertRequest upsertRequest)
            throws CommitterException;

    protected abstract void doDelete(DeleteRequest deleteRequest)
            throws CommitterException;

    /**
     * Subclasses can perform additional closing logic by overriding this
     * method. Default implementation does nothing.
     * @throws CommitterException error closing committer
     */
    protected abstract void doClose() throws CommitterException;

    protected abstract void doClean() throws CommitterException;

    protected final void fireDebug(String name) {
        fireInfo(name, null);
    }

    protected final void fireDebug(String name, CommitterRequest req) {
        fire(CommitterEvent.builder()
                .name(name)
                .source(this)
                .request(req)
                .build(),
                Level.DEBUG);
    }

    protected final void fireInfo(String name) {
        fireInfo(name, null);
    }

    protected final void fireInfo(String name, CommitterRequest req) {
        fire(CommitterEvent.builder()
                .name(name)
                .source(this)
                .request(req)
                .build(),
                Level.INFO);
    }

    protected final void fireError(String name, Exception e) {
        fireError(name, null, e);
    }

    protected final void fireError(
            String name, CommitterRequest req, Exception e) {
        fire(CommitterEvent.builder()
                .name(name)
                .source(this)
                .request(req)
                .exception(e)
                .build(),
                Level.ERROR);
    }

    private void fire(CommitterEvent e, Level level) {
        Optional.ofNullable(committerContext)
                .map(CommitterContext::getEventManager)
                .ifPresent(em -> em.fire(e, level));
    }
}
