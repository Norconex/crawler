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
package com.norconex.crawler.core.ledger;

import java.io.Serializable;

import org.apache.commons.lang3.StringUtils;

/**
 * The different stages of a crawl entry processing life-cycle:
 * <code>UNTRACKED -&gt; QUEUED -&gt; PROCESSING -&gt; PROCESSED</code>
 */
public enum ProcessingStatus implements Serializable {
    /**
     * Nothing was done with this entry yet. Could be rejected
     * before being queued, which would leave no trace in the ledger.
     * This is the default stage when discovering new references not yet queued
     * or otherwise asking for a status of a reference not in the ledger.
     */
    UNTRACKED,

    /**
     * Queued in ledger for processing. Queuing a reference
     * automatically set the state to queued.
     */
    QUEUED,

    /**
     * Processing started but did not yet finish or failed.
     * Documents identified as processing when a crawl session is over
     * are considered to be the result of an unrecoverable error and
     * should be dealt with on next run..
     */
    PROCESSING,

    /**
     * Processing completed a crawl entry.
     */
    PROCESSED;

    public boolean is(ProcessingStatus processingStage) {
        return processingStage != null && processingStage == this;
    }

    public static ProcessingStatus of(String processingStatus) {
        var status =
                StringUtils.upperCase(StringUtils.trimToNull(processingStatus));
        if (status == null) {
            return null;
        }
        for (var st : values()) {
            if (st.name().equals(status)) {
                return st;
            }
        }
        return null;
    }

}
