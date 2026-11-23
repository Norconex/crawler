/* Copyright 2018-2026 Norconex Inc.
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

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.event.Event;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Default committer events.
 */
@Data
@Setter(value = AccessLevel.NONE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class CommitterEvent extends Event {

    private static final long serialVersionUID = 1L;

    /** The Committer began its initialization. */
    public static final String COMMITTER_INIT_BEGIN = "COMMITTER_INIT_BEGIN";
    /** The Committer has been initialized. */
    public static final String COMMITTER_INIT_END = "COMMITTER_INIT_END";
    /** The Committer encountered an error when initializing. */
    public static final String COMMITTER_INIT_ERROR = "COMMITTER_INIT_ERROR";

    /** The Committer has accepted a request and it will commit it. */
    public static final String COMMITTER_ACCEPT_YES = "COMMITTER_ACCEPT_YES";
    /** The Committer has rejected a request and it will not commit it. */
    public static final String COMMITTER_ACCEPT_NO = "COMMITTER_ACCEPT_NO";
    /** The Committer acceptance check produced an error. */
    public static final String COMMITTER_ACCEPT_ERROR =
            "COMMITTER_ACCEPT_ERROR";

    /** The Committer is receiving a document to be updated or inserted. */
    public static final String COMMITTER_UPSERT_BEGIN =
            "COMMITTER_UPSERT_BEGIN";
    /** The Committer has received a document to be updated or inserted.*/
    public static final String COMMITTER_UPSERT_END = "COMMITTER_UPSERT_END";
    /**
     * The Committer withheld a document rather than send it, and removed
     * any version sent before; the event message says why (for example its
     * access control list could not be read).
     */
    public static final String COMMITTER_UPSERT_WITHHELD =
            "COMMITTER_UPSERT_WITHHELD";
    /** The Committer entity update/upsert produced an error. */
    public static final String COMMITTER_UPSERT_ERROR =
            "COMMITTER_UPSERT_ERROR";

    /** The Committer is receiving document to be removed. */
    public static final String COMMITTER_DELETE_BEGIN =
            "COMMITTER_DELETE_BEGIN";
    /** The Committer has received a document to be removed. */
    public static final String COMMITTER_DELETE_END = "COMMITTER_DELETE_END";
    /** The Committer entity removal produced an error. */
    public static final String COMMITTER_DELETE_ERROR =
            "COMMITTER_DELETE_ERROR";

    /**
     * The Committer is about to commit a request batch.
     * Triggered by supporting Committers only.
     */
    public static final String COMMITTER_BATCH_BEGIN = "COMMITTER_BATCH_BEGIN";
    /**
     * The Committer is done committing a request batch
     * Triggered by supporting Committers only.
     */
    public static final String COMMITTER_BATCH_END = "COMMITTER_BATCH_END";
    /**
     * The Committer encountered an error when committing a request batch.
     * Triggered by supporting Committers only.
     */
    public static final String COMMITTER_BATCH_ERROR = "COMMITTER_BATCH_ERROR";

    /** The Committer is closing. */
    public static final String COMMITTER_CLOSE_BEGIN = "COMMITTER_CLOSE_BEGIN";
    /** The Committer is closed. */
    public static final String COMMITTER_CLOSE_END = "COMMITTER_CLOSE_END";
    /** The Committer encountered an error when closing. */
    public static final String COMMITTER_CLOSE_ERROR = "COMMITTER_CLOSE_ERROR";

    /** The Committer is being cleaned. */
    public static final String COMMITTER_CLEAN_BEGIN = "COMMITTER_CLEAN_BEGIN";
    /** The Committer has been cleaned. */
    public static final String COMMITTER_CLEAN_END = "COMMITTER_CLEAN_END";
    /** The Committer encountered an error when cleaning. */
    public static final String COMMITTER_CLEAN_ERROR = "COMMITTER_CLEAN_ERROR";

    private final transient CommitterRequest request;

    @Override
    public String toString() {
        var str = "";
        if (getSource() instanceof Committer c) {
            str += c.getClass().getSimpleName();
        }
        if (request != null) {
            str += " - " + request.getReference();
        }
        return StringUtils.isNotBlank(str) ? str : super.toString();
    }
}
