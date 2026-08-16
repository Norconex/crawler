/* Copyright 2015-2026 Norconex Inc.
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
package com.norconex.crawler.core.doc.operations.spoil.impl;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.crawler.core.doc.operations.spoil.SpoiledReferenceStrategizer;
import com.norconex.crawler.core.doc.operations.spoil.SpoiledReferenceStrategy;
import com.norconex.crawler.core.ledger.ProcessingOutcome;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * <p>
 * Generic implementation of {@link SpoiledReferenceStrategizer} that
 * offers a simple mapping between the crawl state of references that have
 * turned "bad" and the strategy to adopt for each.
 * Whenever a crawl state does not have a strategy associated, the fall-back
 * strategy is used (default being <code>DELETE</code>).
 * </p>
 * <p>
 * The mappings defined by default are as follow:
 * </p>
 *
 * <table border="1" style="width:300px;">
 *   <caption>Default mappings</caption>
 *   <tr><td><b>Crawl state</b></td><td><b>Strategy</b></td></tr>
 *   <tr><td>NOT_FOUND</td><td>DELETE</td></tr>
 *   <tr><td>BAD_STATUS</td><td>GRACE_ONCE</td></tr>
 *   <tr><td>ERROR</td><td>GRACE_ONCE</td></tr>
 * </table>
 */
@EqualsAndHashCode
@ToString
public class GenericSpoiledReferenceStrategizer implements
        SpoiledReferenceStrategizer,
        Configurable<GenericSpoiledReferenceStrategizerConfig> {

    @Getter
    private final GenericSpoiledReferenceStrategizerConfig configuration =
            new GenericSpoiledReferenceStrategizerConfig();

    @Override
    public SpoiledReferenceStrategy resolveSpoiledReferenceStrategy(
            String reference, ProcessingOutcome state) {

        var strategy = configuration.getMappings().get(state);
        if (strategy == null) {
            strategy = configuration.getFallbackStrategy();
        }
        if (strategy == null) {
            strategy =
                    GenericSpoiledReferenceStrategizerConfig.DEFAULT_FALLBACK_STRATEGY;
        }
        return strategy;
    }
}
