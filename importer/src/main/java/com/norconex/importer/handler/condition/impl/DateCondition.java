/* Copyright 2021-2024 Norconex Inc.
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

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.time.ZonedDateTimeParser;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.condition.ConfigurableCondition;

import lombok.Data;
import lombok.Getter;
import lombok.experimental.FieldNameConstants;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * A condition based on the date value(s) of matching
 * metadata fields given the supplied date format. If multiple values are
 * found for a field, only one of them needs to match for this condition to
 * be true.
 * If the value is not a valid date, it is considered not to be matching
 * (i.e., <code>false</code>).
 * The default operator is "eq" (equals).
 * </p>
 *
 * <h2>Single date vs range of dates:</h2>
 * <p>
 * This condition accepts zero, one, or two value matchers:
 * </p>
 * <ul>
 *   <li>
 *     <b>0:</b> Use no value matcher to simply evaluate
 *     whether the value is a date.
 *   </li>
 *   <li>
 *     <b>1:</b> Use one value matcher to evaluate if the value is
 *     lower/greater and/or the same as the specified date.
 *   </li>
 *   <li>
 *     <b>2:</b> Use two value matchers to define a date range to evaluate
 *     (both matches have to evaluate to <code>true</code>).
 *   </li>
 * </ul>
 *
 * <h2>Metadata date field format:</h2>
 * <p>To successfully parse a date, you can specify a date format,
 * as per the formatting options found on {@link DateTimeFormatter}.
 * The default format when not specified is EPOCH (the difference, measured in
 * milliseconds, between the date and midnight, January 1, 1970).</p>
 *
 * <h2>Absolute date conditions:</h2>
 * <p>When defining a date value matcher, you can specify an absolute
 * date (i.e. a constant date value) to be used for comparison.
 * Supported formats for configuring an absolute date are:
 * </p>
 * <pre>
 *   yyyy-MM-dd                -&gt; date (e.g. 2015-05-31)
 *   yyyy-MM-ddThh:mm:ss[.SSS] -&gt; date and time with optional
 *                                milliseconds (e.g. 2015-05-31T22:44:15)
 * </pre>
 *
 * <h2>Relative date conditions:</h2>
 * <P>Date value matchers can also specify a moment in time relative to the
 * current date using the <code>TODAY</code> or <code>NOW</code> keyword,
 * optionally followed by a number of time units to add/remove.
 * <code>TODAY</code> is the current day without the hours, minutes, and
 * seconds, where as <code>NOW</code> is the current day with the hours,
 * minutes, and seconds. You can also decide whether you want the
 * current date to be fixed for the lifetime of this condition (does not change
 * after being set for the first time), or whether
 * it should be refreshed on every invocation to reflect the passing of time.
 * </p>
 *
 * <h2>Time zones:</h2>
 * <p>
 * When comparing dates at a more granular level (e.g., hours, minutes,
 * seconds), it may be important to take time zones into account.
 * If the time zone (id or offset) is part of a document field date value
 * and this filter configured format supports time zones, it will respect
 * the time zone in the encountered time zone.
 * </p>
 * <p>
 * In cases where you want to specify the time zone for values
 * without one, you can do so with
 * the {@link #setDocZoneId(ZoneId)} method.
 * Explicitly setting a document time zone that way has no effect
 * if the date already defines its own zone.
 * The default time zone when none is specified is UTC.
 * </p>
 * <p>
 * When using XML configuration to define the condition dates, you can
 * specify the time zone using the <code>conditionZoneId</code> option.
 * </p>
 *
 * {@nx.include com.norconex.commons.lang.Operator#operators}
 *
 * {@nx.xml.usage
 * <condition class="com.norconex.importer.handler.condition.impl.DateCondition"
 *     format="(document field date format)"
 *     docZoneId="(force a time zone on evaluated fields)"
 *     conditionZoneId="(time zone of condition dates when not specified)">
 *
 *
 *     <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (expression matching date fields to evaluate)
 *     </fieldMatcher>
 *
 *     <!-- Use one or two (for ranges) conditions where:
 *
 *       Possible operators are:
 *
 *         gt -> greater than
 *         ge -> greater equal
 *         lt -> lower than
 *         le -> lower equal
 *         eq -> equals
 *
 *       Condition date value format are either one of:
 *
 *         yyyy-MM-dd                -> date (e.g. 2015-05-31)
 *         yyyy-MM-ddThh:mm:ss[.SSS] -> date and time with optional
 *                                      milliseconds (e.g. 2015-05-31T22:44:15)
 *         TODAY[-+]9[YMDhms][*]     -> the string "TODAY" (at 0:00:00) minus
 *                                      or plus a number of years, months, days,
 *                                      hours, minutes, or seconds
 *                                      (e.g. 1 week ago: TODAY-7d).
 *                                      * means TODAY can change from one
 *                                      invocation to another to adjust to a
 *                                      change of current day
 *         NOW[-+]9[YMDhms][*]       -> the string "NOW" (at current time) minus
 *                                      or plus a number of years, months, days,
 *                                      hours, minutes, or seconds
 *                                      (e.g. 1 week ago: NOW-7d).
 *                                      * means NOW changes from one invocation
 *                                      to another to adjust to the current time.
 *    -->
 *
 *     <valueMatcher operator="[gt|ge|lt|le|eq]" date="(a date)" />
 *
 * </condition>
 * }
 *
 * {@nx.xml.example
 * <condition class="DateCondition"
 *     format="yyyy-MM-dd'T'HH:mm:ssZ"
 *     conditionZoneId="America/New_York">
 *   <fieldMatcher>publish_date</fieldMatcher>
 *   <valueMatcher operator="ge" date="TODAY-7" />
 *   <valueMatcher operator="lt" date="TODAY" />
 * </condition>
 * }
 * <p>
 * The above example will only keep documents from the last
 * seven days, not including today.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@FieldNameConstants
@Slf4j
public class DateCondition
        implements ConfigurableCondition<DateConditionConfig> {

    public enum TimeUnit {
        YEAR(ChronoUnit.YEARS, "Y"),
        MONTH(ChronoUnit.MONTHS, "M"),
        DAY(ChronoUnit.DAYS, "D"),
        HOUR(ChronoUnit.HOURS, "h"),
        MINUTE(ChronoUnit.MINUTES, "m"),
        SECOND(ChronoUnit.SECONDS, "s");

        private final TemporalUnit temporalUnit;
        private final String abbr;

        TimeUnit(TemporalUnit temporalUnit, String abbr) {
            this.temporalUnit = temporalUnit;
            this.abbr = abbr;
        }

        public TemporalUnit toTemporal() {
            return temporalUnit;
        }

        @Override
        public String toString() {
            return abbr;
        }

        public static TimeUnit getTimeUnit(String unit) {
            if (StringUtils.isBlank(unit)) {
                return null;
            }
            for (TimeUnit tu : TimeUnit.values()) {
                if (tu.abbr.equalsIgnoreCase(unit)) {
                    return tu;
                }
            }
            return null;
        }
    }

    @Getter
    private final DateConditionConfig configuration =
            new DateConditionConfig();

    @Override
    public boolean test(DocHandlerContext docCtx) throws IOException {
        if (configuration.getFieldMatcher().getPattern() == null) {
            throw new IllegalArgumentException(
                    "\"fieldMatcher\" pattern cannot be empty.");
        }
        for (Entry<String, List<String>> en : docCtx.metadata().matchKeys(
                configuration.getFieldMatcher()).entrySet()) {
            for (String value : en.getValue()) {
                if (matches(configuration.getValueMatcher(), en.getKey(), value)
                        && matches(
                                configuration.getValueMatcherRangeEnd(),
                                en.getKey(), value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matches(
            DateValueMatcher matcher, String fieldName, String fieldValue) {
        if (matcher == null) {
            return true;
        }

        var dt = ZonedDateTimeParser.builder()
                .format(configuration.getFormat())
                .zoneId(configuration.getDocZoneId())
                .build()
                .parse(fieldValue);
        if (dt == null) {
            return false;
        }

        var evalResult = matcher.test(dt);
        LOG.debug(
                "{}: {} [{}] {} = {}",
                fieldName,
                fieldValue,
                matcher.getOperator(),
                matcher.getDateProvider().getDateTime(),
                evalResult);
        return evalResult;
    }
}
