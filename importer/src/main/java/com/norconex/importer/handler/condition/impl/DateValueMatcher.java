/* Copyright 2023-2026 Norconex Inc.
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
package com.norconex.importer.handler.condition.impl;

import static com.norconex.commons.lang.Operator.EQUALS;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Predicate;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.norconex.commons.lang.Operator;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;

/**
 * Immutable date-value matcher, supporting both date expressions and
 * standard operators.
 */
@Data
public class DateValueMatcher implements Predicate<ZonedDateTime> {

    private final Operator operator;
    @JsonIgnore
    private final DateProvider dateProvider;

    /**
     * Original date expression and explicitly-supplied zone id (if any),
     * retained so a condition-level default zone can be applied later to
     * matchers that did not specify their own. Excluded from equality and
     * serialization (the {@code date}/{@code zoneId} JSON properties handle
     * that).
     */
    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final String dateExpression;
    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final ZoneId explicitZoneId;

    public DateValueMatcher(
            Operator operator,
            @NonNull DateProvider dateProvider) {
        this.operator = operator;
        this.dateProvider = dateProvider;
        dateExpression = dateProvider.toString();
        explicitZoneId = dateProvider.getZoneId();
    }

    @JsonCreator
    public DateValueMatcher(
            @JsonProperty("operator") Operator operator,
            @JsonProperty("date") String dateTimeExpression,
            @JsonProperty("zoneId") ZoneId zoneId) {
        this.operator = operator;
        dateExpression = dateTimeExpression;
        explicitZoneId = zoneId;
        dateProvider =
                DateProviderFactory.create(dateTimeExpression, zoneId);
    }

    @Override
    public boolean test(ZonedDateTime zdt) {
        if (zdt == null) {
            return false;
        }

        // if the date obtained by the supplier (the date value or logic
        // configured) starts with TODAY, we truncate that date to
        // ensure we are comparing apples to apples. Else, one must ensure
        // the date format matches for proper comparisons.
        var resolvedZdt = zdt;
        if (Strings.CI.startsWith(dateProvider.toString(), "today")) {
            resolvedZdt = resolvedZdt.truncatedTo(ChronoUnit.DAYS);
        }
        var op = ObjectUtils.getIfNull(operator, EQUALS);
        return op.evaluate(
                resolvedZdt.toInstant(),
                dateProvider.getDateTime().toInstant());
    }

    @JsonProperty("date")
    String dateProviderAsString() {
        return dateProvider.toString();
    }

    @JsonProperty("zoneId")
    String zoneIdAsString() {
        return Optional.ofNullable(dateProvider.getZoneId())
                .map(ZoneId::toString)
                .orElse(null);
    }
}
