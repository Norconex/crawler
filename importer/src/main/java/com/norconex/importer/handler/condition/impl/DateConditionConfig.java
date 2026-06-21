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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.norconex.commons.lang.text.TextMatcher;

import lombok.Data;
import lombok.experimental.Accessors;

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
@Accessors(chain = true)
public class DateConditionConfig {

    /**
     * Field name(s) text matcher.
     * @param fieldMatcher field matcher
     * @return field matcher
     */
    private final TextMatcher fieldMatcher = new TextMatcher();

    /**
     * Value matcher for a date, or the begining of a date range
     * (if an end date value matcher is also supplied).
     * @param valueMatcher date matcher
     * @return date matcher
     */
    private DateValueMatcher valueMatcher;

    /**
     * Value matcher for then end of a date range. Only set when dealing
     * with date ranges.
     * @param valueMatcherRangeEnd end of range date matcher
     * @return end of range date matcher
     */
    private DateValueMatcher valueMatcherRangeEnd;

    /**
     * The format of a document date field value (see class documentation).
     * @param format date format
     * @return date format
     */
    private String format;

    /**
     * Time zone id to use for dates associated with a document when
     * evaluating date conditions.
     * @param docZoneId document zone id
     * @return document zone id
     */
    private ZoneId docZoneId;

    /**
     * Default time zone id applied to condition dates that do not specify
     * their own (i.e., value matchers without an explicit {@code zoneId} and
     * whose date string carries no zone). A zone set directly on a value
     * matcher takes precedence. When neither is set, condition dates default
     * to UTC.
     * @param conditionZoneId condition zone id
     * @return condition zone id
     */
    private ZoneId conditionZoneId;

    /**
     * Sets the text matcher of field names. Copies it.
     * @param fieldMatcher text matcher
     */
    public DateConditionConfig setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }

    public DateConditionConfig setConditionZoneId(ZoneId conditionZoneId) {
        this.conditionZoneId = conditionZoneId;
        valueMatcher = applyConditionZoneId(valueMatcher);
        valueMatcherRangeEnd = applyConditionZoneId(valueMatcherRangeEnd);
        return this;
    }

    @JsonAlias("valueMatcherStart")
    public DateConditionConfig setValueMatcher(DateValueMatcher valueMatcher) {
        this.valueMatcher = applyConditionZoneId(valueMatcher);
        return this;
    }

    @JsonAlias("valueMatcherEnd")
    public DateConditionConfig setValueMatcherRangeEnd(
            DateValueMatcher valueMatcherRangeEnd) {
        this.valueMatcherRangeEnd = applyConditionZoneId(valueMatcherRangeEnd);
        return this;
    }

    /**
     * Re-resolves a value matcher's date using {@link #conditionZoneId} when
     * the matcher did not specify its own zone. Returns the matcher unchanged
     * when there is nothing to apply.
     */
    private DateValueMatcher applyConditionZoneId(DateValueMatcher matcher) {
        if (matcher == null
                || conditionZoneId == null
                || matcher.getExplicitZoneId() != null) {
            return matcher;
        }
        return new DateValueMatcher(
                matcher.getOperator(),
                matcher.getDateExpression(),
                conditionZoneId);
    }
}
