/* Copyright 2020-2025 Norconex Inc.
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
package com.norconex.committer.core.service;

import static com.norconex.committer.core.service.CommitterServiceEvent.COMMITTER_SERVICE_CLEAN_BEGIN;
import static com.norconex.committer.core.service.CommitterServiceEvent.COMMITTER_SERVICE_CLEAN_END;
import static com.norconex.committer.core.service.CommitterServiceEvent.COMMITTER_SERVICE_CLOSE_BEGIN;
import static com.norconex.committer.core.service.CommitterServiceEvent.COMMITTER_SERVICE_CLOSE_END;
import static com.norconex.committer.core.service.CommitterServiceEvent.COMMITTER_SERVICE_DELETE_BEGIN;
import static com.norconex.committer.core.service.CommitterServiceEvent.COMMITTER_SERVICE_DELETE_END;
import static com.norconex.committer.core.service.CommitterServiceEvent.COMMITTER_SERVICE_INIT_BEGIN;
import static com.norconex.committer.core.service.CommitterServiceEvent.COMMITTER_SERVICE_INIT_END;
import static com.norconex.committer.core.service.CommitterServiceEvent.COMMITTER_SERVICE_UPSERT_BEGIN;
import static com.norconex.committer.core.service.CommitterServiceEvent.COMMITTER_SERVICE_UPSERT_END;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;

import com.norconex.committer.core.Committer;
import com.norconex.committer.core.CommitterContext;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.commons.lang.event.EventManager;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Wrapper around multiple Committers so they can all be handled as one
 * and facilitating integration with various clients.
 * @param <T> type of committed objects
 */
@Slf4j
@EqualsAndHashCode
@Builder
@Data
@Setter(AccessLevel.NONE)
public class CommitterService<T> implements Closeable {

    //NOTE: Takes a generic type given it may sometimes be used
    // for other types than CrawlDoc from the crawler-core project

    @Default
    private List<Committer> committers = Collections.emptyList();
    @Default
    private EventManager eventManager = new EventManager();
    @NonNull
    private final Function<T, UpsertRequest> upsertRequestBuilder;
    @NonNull
    private final Function<T, DeleteRequest> deleteRequestBuilder;

    public boolean isOperative() {
        return !committers.isEmpty();
    }

    public void init(CommitterContext baseContext)
            throws CommitterServiceException {
        fire(COMMITTER_SERVICE_INIT_BEGIN, committers, null);

        Set<String> uniqueDirNames = new HashSet<>();
        executeAll("init", c -> {
            var dirName = ClassUtils.getShortClassName(c.getClass());
            if (StringUtils.isBlank(dirName)) {
                dirName = "UnnamedCommitter";
            }
            var cnt = 1;
            while (uniqueDirNames.contains(dirName)) {
                cnt++;
                dirName = dirName.replaceFirst("^(.*)_\\d+$", "$1") + "_" + cnt;
            }
            uniqueDirNames.add(dirName);
            var ctx = baseContext.withWorkdir(
                    baseContext.getWorkDir().resolve(dirName));
            c.init(ctx);
        });

        fire(COMMITTER_SERVICE_INIT_END, committers, null);
    }

    /**
     * Updates or inserts an object using all accepting committers.
     * @param object the object to upsert
     * @return committers having accepted and upserted the object
     * @throws CommitterServiceException wrapper around operation failure
     *     one or more of the registered committers (wraps last exception
     *     captured)
     */
    public List<Committer> upsert(T object) throws CommitterServiceException {
        return upsert(object, (committer, reason) -> {});
    }

    /**
     * Updates or inserts an object using all accepting committers, telling
     * the caller about each committer that withheld it (see
     * the request's {@code withheldReason}), so the caller can try it
     * again later.
     * @param object the object to upsert
     * @param onWithheld called with the committer and its reason, for each
     *     committer that accepted the object but withheld it
     * @return committers having accepted the object, whether they upserted
     *     it or withheld it
     * @throws CommitterServiceException wrapper around operation failure
     *     one or more of the registered committers (wraps last exception
     *     captured)
     */
    public List<Committer> upsert(
            T object, @NonNull BiConsumer<Committer, String> onWithheld)
            throws CommitterServiceException {
        fire(COMMITTER_SERVICE_UPSERT_BEGIN, committers, object);

        List<Committer> actuals = new ArrayList<>();
        if (!committers.isEmpty()) {
            executeAll("upsert", c -> {
                var req = upsertRequestBuilder.apply(object);
                if (c.accept(req)) {
                    actuals.add(c);
                    c.upsert(req);
                    if (req.getWithheldReason() != null) {
                        onWithheld.accept(c, req.getWithheldReason());
                    }
                }
            });
        }

        fire(COMMITTER_SERVICE_UPSERT_END, actuals, object);
        return actuals;
    }

    /**
     * Deletes an object using all accepting committers.
     * @param object the object to delete
     * @return committers having accepted and deleted the object
     * @throws CommitterServiceException wrapper around operation failure
     *     one or more of the registered committers (wraps last exception
     *     captured)
     */
    public List<Committer> delete(T object) throws CommitterServiceException {
        fire(COMMITTER_SERVICE_DELETE_BEGIN, committers, object);

        List<Committer> actuals = new ArrayList<>();
        if (!committers.isEmpty()) {
            executeAll("delete", c -> {
                var req = deleteRequestBuilder.apply(object);
                if (c.accept(req)) {
                    actuals.add(c);
                    c.delete(req);
                    // no doc content rewind necessary
                }
            });
        }
        fire(COMMITTER_SERVICE_DELETE_END, actuals, object);

        return actuals;
    }

    /**
     * Closes all registered committers.
     * @throws CommitterServiceException wrapper around operation failure
     *     one or more of the registered committers (wraps last exception
     *     captured)
     */
    @Override
    public void close() throws CommitterServiceException {
        fire(COMMITTER_SERVICE_CLOSE_BEGIN, committers, null);
        executeAll("close", Committer::close);
        fire(COMMITTER_SERVICE_CLOSE_END, committers, null);
    }

    /**
     * Cleans all registered committers.
     * @throws CommitterServiceException wrapper around operation failure
     *     one or more of the registered committers (wraps last exception
     *     captured)
     */
    public void clean() throws CommitterServiceException {
        fire(COMMITTER_SERVICE_CLEAN_BEGIN, committers, null);
        executeAll("clean", Committer::clean);
        fire(COMMITTER_SERVICE_CLEAN_END, committers, null);
    }

    private void executeAll(String operation, CommitterConsumer consumer)
            throws CommitterServiceException {
        List<String> failures = new ArrayList<>();
        CommitterException exception = null;
        for (Committer committer : committers) {
            try {
                consumer.accept(committer);
            } catch (CommitterException e) {
                LOG.error(
                        "Could not execute \"{}\" on committer: {}",
                        operation, committer, e);
                failures.add(committer.getClass().getSimpleName());
                exception = e;
            }
        }
        if (!failures.isEmpty()) {
            throw new CommitterServiceException(
                    "Could not execute \"" + operation + "\" on "
                            + failures.size() + " committer(s): \""
                            + StringUtils.join(failures, ", ")
                            + "\". Cause is the last exception captured. "
                            + "Check the logs for more details.",
                    exception);
        }
    }

    private void fire(String eventName, List<Committer> targets, T object) {
        var msg = "Committers: " + (targets.isEmpty()
                ? "none"
                : targets.stream().map(c -> c.getClass().getSimpleName())
                        .collect(Collectors.joining(",")));
        getEventManager().fire(
                CommitterServiceEvent.builder()
                        .name(eventName)
                        .source(this)
                        .subject(object)
                        .committers(targets)
                        .message(msg)
                        .build());
    }

    @Override
    public String toString() {
        return CommitterService.class.getSimpleName() + '[' +
                committers.stream().map(c -> c.getClass().getSimpleName())
                        .collect(Collectors.joining(","))
                + ']';
    }

    @FunctionalInterface
    private interface CommitterConsumer {
        void accept(Committer c) throws CommitterException;
    }
}
