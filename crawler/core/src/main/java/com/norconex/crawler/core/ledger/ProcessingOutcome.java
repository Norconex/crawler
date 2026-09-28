/* Copyright 2014-2026 Norconex Inc.
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.EqualsAndHashCode;

/**
 * Reference processing status.
 */
@JsonAutoDetect(
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE
)
@EqualsAndHashCode
public class ProcessingOutcome implements Serializable {

    private static final Map<String, ProcessingOutcome> OUTCOMES =
            new ConcurrentHashMap<>();

    //TODO make default state UNKNOWN/UNPROCESSED, or equivalent that means
    // TBD/IN_PROGRESS????
    // Because NEW is misleading in the case where it exists in the cache but
    // no checksum defined... then it is neither new nor modified.
    private static final long serialVersionUID = 6542269270632505768L;

    public static final ProcessingOutcome NEW =
            new ProcessingOutcome("NEW");
    public static final ProcessingOutcome MODIFIED =
            new ProcessingOutcome("MODIFIED");
    public static final ProcessingOutcome UNMODIFIED =
            new ProcessingOutcome("UNMODIFIED");
    public static final ProcessingOutcome ERROR =
            new ProcessingOutcome("ERROR");
    public static final ProcessingOutcome REJECTED =
            new ProcessingOutcome("REJECTED");
    public static final ProcessingOutcome BAD_STATUS =
            new ProcessingOutcome("BAD_STATUS");
    public static final ProcessingOutcome DELETED =
            new ProcessingOutcome("DELETED");
    public static final ProcessingOutcome NOT_FOUND =
            new ProcessingOutcome("NOT_FOUND");
    /**
     * For collectors that support it, this state indicates a previously
     * crawled document is not yet ready to be re-crawled.  It may or may not
     * be re-crawled in the next crawl session (if ready).
         */
    public static final ProcessingOutcome PREMATURE =
            new ProcessingOutcome("PREMATURE");

    /**
     * The reference is a legitimate, successfully processed crawl entry that
     * does not represent a document of its own. A file system folder is the
     * archetype: it is queued, traversed, depth-tracked and yields its
     * children, but there is nothing to send to a committer for the folder
     * itself.
     * <p>
     * This is deliberately distinct from {@link #REJECTED}, which says two
     * things at once: do not commit this <em>and</em> remove it from the
     * target if it is there. The second half is right for a reference that
     * could have been a document and no longer qualifies &mdash; a
     * non-canonical URL, a page a rule now excludes &mdash; and wrong for one
     * that was never eligible. A folder marked rejected was deleted from the
     * target on every recrawl, for something that had never been sent there.
     * </p>
     * <p>
     * Note this describes a ledger entry, not a fetch. It must not be used as
     * a fetch response outcome: fetchers signal success with a good state, and
     * {@code MultiFetcher} would otherwise keep trying further fetchers for a
     * folder that was fetched perfectly well.
     * </p>
     */
    public static final ProcessingOutcome NON_DOCUMENT =
            new ProcessingOutcome("NON_DOCUMENT");

    //TODO remove if not used:
    /**
     * Typically when a reference cannot be processed since it is
     * not supported by the collector or one of its configured
     * component.
     */
    public static final ProcessingOutcome UNSUPPORTED =
            new ProcessingOutcome("UNSUPPORTED");

    public static final ProcessingOutcome TOO_DEEP =
            new ProcessingOutcome("TOO_DEEP");

    @JsonProperty
    @JsonValue
    private final String outcome;

    /**
     * Constructor.
     * @param outcome processing outcome
     */
    protected ProcessingOutcome(String outcome) {
        this.outcome = outcome;
        OUTCOMES.put(outcome, this);
    }

    /**
     * Returns whether a reference should be considered "good" (the
     * corresponding document is not in a "bad" state, such as being rejected
     * or produced an error.
     * This implementation will consider as good these reference statuses:
     * {@link #NEW}, {@link #MODIFIED}, {@link #UNMODIFIED},
     * and {@link #PREMATURE}.
     * This method can be overridden to provide different logic for a valid
     * reference.
     * @return <code>true</code> if status is valid.
     */
    public boolean isGoodState() {
        return isOneOf(NEW, MODIFIED, UNMODIFIED, PREMATURE);
    }

    /**
     * Null-safe version of {@link #isGoodState()}.  A {@code null}
     * state returns <code>false</code>.
     * @param state the state to test
     * @return <code>true</code> if status is valid.
     */
    public static boolean isGoodState(ProcessingOutcome state) {
        return state != null && state.isGoodState();
    }

    /**
     * Returns whether a state indicates new or modified.
     * @return <code>true</code> if new or modified
     */
    public boolean isNewOrModified() {
        return isOneOf(NEW, MODIFIED);
    }

    /**
     * Returns whether a state indicate the document is to be skipped
     * ({@link #UNMODIFIED} or {@link #PREMATURE}).
     * @return <code>true</code> if skipped
     */
    public boolean isSkipped() {
        return isOneOf(UNMODIFIED, PREMATURE);
    }

    public boolean isOneOf(ProcessingOutcome... states) {
        if (ArrayUtils.isEmpty(states)) {
            return false;
        }
        for (ProcessingOutcome crawlState : states) {
            if (equals(crawlState)) {
                return true;
            }
        }
        return false;
    }

    @JsonCreator
    public static ProcessingOutcome valueOf(
            @JsonProperty("state") String state) {
        if (StringUtils.isBlank(state)) {
            return null;
        }
        var refState = OUTCOMES.get(state);
        if (refState == null) {
            refState = new ProcessingOutcome(state);
        }
        return refState;
    }

    @Override
    public String toString() {
        return outcome;
    }
}
