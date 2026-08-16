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
package com.norconex.crawler.core;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.norconex.commons.lang.event.Event;
import com.norconex.commons.lang.event.EventListener;
import com.norconex.crawler.core.cmd.Command;
import com.norconex.crawler.core.cmd.crawl.CrawlerCommand;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.importer.doc.Doc;

import lombok.Builder;
import lombok.Getter;

/**
 * <p>
 * Optional callbacks invoked at specific times in the crawler life-cycle.
 * The flow of execution for a crawl is as follow (callbacks are bold):
 * </p>
 * <ul>
 *   <li>Launch crawler.</li>
 *   <li>Invoke <b>beforeSession</b>.</li>
 *   <li>Initialize crawl session.</li>
 *   <li>For each command:
 *     <ul>
 *       <li>Invoke <b>beforeCommand</b>.</li>
 *       <li>Execute command.</li>
 *       <li>For each document:
 *         <ul>
 *           <li>Invoke <b>beforeDocumentProcessing</b>.</li>
 *           <li>Process document (fetch, parse, transform, etc.)</li>
 *           <li>Invoke <b>afterDocumentProcessing</b>.</li>
 *           <li>Invoke <b>beforeDocumentFinalizing</b>.</li>
 *           <li>Finalize document (upsert/delete, close resources, etc.)</li>
 *           <li>Invoke <b>afterDocumentFinalizing</b>.</li>
 *         </ul>
 *       </li>
 *       <li>Invoke <b>afterCommand</b></li>
 *     </ul>
 *   </li>
 *   <li>Invoke <b>afterSession</b></li>
 * </ul>
 */
@Builder
@Getter
public class CrawlerCallbacks implements EventListener<Event> {

    /**
     * Convenience interface providing a default implementation of
     * execute that ignores all commands but {@link CrawlerCommand} and
     * offers an overloaded {@code accept} method with a single argument.
     */
    public interface CrawlerCommandCallback
            extends BiConsumer<CrawlerSession, Class<? extends Command>> {
        @Override
        default void accept(
                CrawlerSession session, Class<? extends Command> commandClass) {
            if (CrawlerCommand.class.isAssignableFrom(commandClass)) {
                accept(session);
            }
        }

        void accept(CrawlerSession t);
    }

    /**
     * Invoked before the crawl session gets initialized (which is also
     * before any command gets executed). Modifying the
     * CrawlerConfig is generally safe here.
     */
    Consumer<CrawlerConfig> beforeSession;
    /**
     * Invoked after a crawl session has been destroyed and resources closed.
     */
    Consumer<CrawlerConfig> afterSession;

    /**
     * Invoked before a command is executed, after the session and crawl context
     * have been initialized.
     * Rely on the supplied command class to know which command.
     * @see CrawlerCommandCallback
     */
    BiConsumer<CrawlerSession, Class<? extends Command>> beforeCommand;
    /**
     * Invoked after a command has been executed, but before resources are
     * closed.
     * Rely on the supplied command class to know which command.
     * @see CrawlerCommandCallback
     */
    BiConsumer<CrawlerSession, Class<? extends Command>> afterCommand;

    //MAYBE: are those used? Should they be?
    // Add those that are missing to ReferencesProcessor
    BiConsumer<CrawlerSession, Doc> beforeDocumentProcessing;
    BiConsumer<CrawlerSession, Doc> afterDocumentProcessing;

    //MAYBE: need those, or we can replace beforeDocumentFinalizing
    // (the only one used) with after processing?
    BiConsumer<CrawlerSession, Doc> beforeDocumentFinalizing;
    BiConsumer<CrawlerSession, Doc> afterDocumentFinalizing;

    /**
     * Dispatches crawler lifecycle events to the appropriate hook. This method
     * is called automatically when this instance is registered as an
     * {@link EventListener} on the crawler's {@code EventManager}.
     */
    @Override
    public void accept(Event event) {
        if (!(event instanceof CrawlerEvent ce)) {
            return;
        }
        switch (ce.getName()) {
            case CrawlerEvent.CRAWLER_SESSION_BEGIN ->
                    ifPresent(beforeSession,
                            c -> c.accept((CrawlerConfig) ce.getSource()));
            case CrawlerEvent.CRAWLER_SESSION_END ->
                    ifPresent(afterSession,
                            c -> c.accept((CrawlerConfig) ce.getSource()));
            case CrawlerEvent.CRAWLER_COMMAND_BEGIN ->
                    ifPresent(beforeCommand, c -> c.accept(
                            ce.getCrawlSession(), ce.getCommandClass()));
            case CrawlerEvent.CRAWLER_COMMAND_END ->
                    ifPresent(afterCommand, c -> c.accept(
                            ce.getCrawlSession(), ce.getCommandClass()));
            case CrawlerEvent.DOCUMENT_PROCESSING_BEGIN ->
                    ifPresent(beforeDocumentProcessing, c -> c.accept(
                            ce.getCrawlSession(), (Doc) ce.getSource()));
            case CrawlerEvent.DOCUMENT_PROCESSING_END ->
                    ifPresent(afterDocumentProcessing, c -> c.accept(
                            ce.getCrawlSession(), (Doc) ce.getSource()));
            case CrawlerEvent.DOCUMENT_FINALIZING_BEGIN ->
                    ifPresent(beforeDocumentFinalizing, c -> c.accept(
                            ce.getCrawlSession(), (Doc) ce.getSource()));
            case CrawlerEvent.DOCUMENT_FINALIZING_END ->
                    ifPresent(afterDocumentFinalizing, c -> c.accept(
                            ce.getCrawlSession(), (Doc) ce.getSource()));
            default -> {
                /* not a callback-relevant event */ }
        }
    }

    private static <T> void ifPresent(T obj, Consumer<T> action) {
        if (obj != null) {
            action.accept(obj);
        }
    }
}
