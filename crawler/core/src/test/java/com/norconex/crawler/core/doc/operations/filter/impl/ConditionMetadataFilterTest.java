/* Copyright 2026 Norconex Inc.
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
package com.norconex.crawler.core.doc.operations.filter.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.norconex.commons.lang.Operator;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.crawler.core.doc.operations.filter.OnMatch;
import com.norconex.crawler.core.doc.operations.filter.impl.condition.MetadataAllOf;
import com.norconex.crawler.core.doc.operations.filter.impl.condition.MetadataAnyOf;
import com.norconex.crawler.core.doc.operations.filter.impl.condition.MetadataBlankCondition;
import com.norconex.crawler.core.doc.operations.filter.impl.condition.MetadataDateCondition;
import com.norconex.crawler.core.doc.operations.filter.impl.condition.MetadataNoneOf;
import com.norconex.crawler.core.doc.operations.filter.impl.condition.MetadataNumberCondition;
import com.norconex.crawler.core.doc.operations.filter.impl.condition.MetadataTextCondition;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.handler.condition.impl.DateValueMatcher;
import com.norconex.importer.handler.condition.impl.NumericValueMatcher;

@Timeout(30)
class ConditionMetadataFilterTest {

    private static final String REF = "http://example.com/a.pdf";

    private static Properties meta(String... keyValues) {
        var p = new Properties();
        for (var i = 0; i < keyValues.length; i += 2) {
            p.add(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }

    private static MetadataNumberCondition biggerThan(
            String field, double bytes) {
        return new MetadataNumberCondition()
                .setFieldMatcher(TextMatcher.basic(field))
                .setValueMatcher(
                        new NumericValueMatcher(Operator.GREATER_THAN, bytes));
    }

    private static ConditionMetadataFilter excluding(
            com.norconex.crawler.core.doc.operations.filter.impl.condition.MetadataCondition cond) {
        var filter = new ConditionMetadataFilter();
        filter.getConfiguration().setOnMatch(OnMatch.EXCLUDE)
                .setCondition(cond);
        return filter;
    }

    @Test
    void sizeFilter_rejectsOnlyBiggerThanLimit() {
        var filter = excluding(biggerThan("Content-Length", 1000));
        assertThat(filter.acceptMetadata(REF,
                meta("Content-Length", "999"))).isTrue();
        assertThat(filter.acceptMetadata(REF,
                meta("Content-Length", "1001"))).isFalse();
    }

    @Test
    void missingOrUnparsableValue_isNotAMatch_soItIsKept() {
        var filter = excluding(biggerThan("Content-Length", 1000));
        assertThat(filter.acceptMetadata(REF, meta())).isTrue();
        assertThat(filter.acceptMetadata(REF,
                meta("Content-Length", "n/a"))).isTrue();
        assertThat(filter.acceptMetadata(REF, null)).isTrue();
    }

    @Test
    void include_keepsOnlyMatching() {
        var filter = new ConditionMetadataFilter();
        filter.getConfiguration().setCondition(biggerThan("size", 10));
        assertThat(filter.getOnMatch()).isEqualTo(OnMatch.INCLUDE);
        assertThat(filter.acceptMetadata(REF, meta("size", "11"))).isTrue();
        assertThat(filter.acceptMetadata(REF, meta("size", "9"))).isFalse();
    }

    @Test
    void dateFilter_relativeToToday() {
        var cond = new MetadataDateCondition()
                .setFieldMatcher(TextMatcher.basic("Last-Modified"))
                .setFormat("yyyy-MM-dd")
                .setValueMatcher(new DateValueMatcher(
                        Operator.LOWER_THAN,
                        "TODAY-1Y", null));
        var filter = excluding(cond);
        assertThat(filter.acceptMetadata(REF,
                meta("Last-Modified", "2001-01-31"))).isFalse();
        assertThat(filter.acceptMetadata(REF,
                meta("Last-Modified", "2999-01-31"))).isTrue();
    }

    @Test
    void dateFilter_httpLastModifiedFormat() {
        // the format suggested in the documentation for HTTP headers
        var cond = new MetadataDateCondition()
                .setFieldMatcher(TextMatcher.basic("Last-Modified"))
                .setFormat("EEE, dd MMM yyyy HH:mm:ss zzz")
                .setValueMatcher(new DateValueMatcher(
                        Operator.LOWER_THAN, "2020-01-01", null));
        var filter = excluding(cond);
        assertThat(filter.acceptMetadata(REF,
                meta("Last-Modified", "Tue, 15 Nov 1994 08:12:31 GMT")))
                        .isFalse();
        assertThat(filter.acceptMetadata(REF,
                meta("Last-Modified", "Fri, 15 Nov 2024 08:12:31 GMT")))
                        .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
                // abbreviation, symbol, then constant name in a few spellings
                "gt", ">", "GREATER_THAN", "GreaterThan", "greater-than" }
    )
    void readFromYaml_operatorSpellings(String operator) {
        var yaml = """
                onMatch: EXCLUDE
                condition:
                  class: MetadataNumberCondition
                  fieldMatcher:
                    pattern: size
                  valueMatcher:
                    operator: "%s"
                    number: 100
                """.formatted(operator);
        var filter = new ConditionMetadataFilter();
        BeanMapper.DEFAULT.read(filter, new StringReader(yaml), Format.YAML);
        assertThat(filter.acceptMetadata(REF, meta("size", "101"))).isFalse();
        assertThat(filter.acceptMetadata(REF, meta("size", "99"))).isTrue();
        // "equals" (what an unrecognized operator falls back to) would
        // have rejected exactly 100, and "greater than" does not
        assertThat(filter.acceptMetadata(REF, meta("size", "100"))).isTrue();
    }

    @Test
    void textCondition_matchesFieldValue() {
        var filter = excluding(new MetadataTextCondition()
                .setFieldMatcher(TextMatcher.basic("Content-Type"))
                .setValueMatcher(TextMatcher.wildcard("video/*")));
        assertThat(filter.acceptMetadata(REF,
                meta("Content-Type", "video/mp4"))).isFalse();
        assertThat(filter.acceptMetadata(REF,
                meta("Content-Type", "text/html"))).isTrue();
    }

    @Test
    void blankCondition_missingFieldIsBlank() {
        var filter = excluding(new MetadataBlankCondition()
                .setFieldMatcher(TextMatcher.basic("Content-Type")));
        assertThat(filter.acceptMetadata(REF, meta())).isFalse();
        assertThat(filter.acceptMetadata(REF,
                meta("Content-Type", "text/html"))).isTrue();
    }

    @Test
    void groups_combineAndNest() {
        // reject when: big AND (pdf OR zip)
        var types = new MetadataAnyOf().setConditions(List.of(
                new MetadataTextCondition()
                        .setFieldMatcher(TextMatcher.basic("type"))
                        .setValueMatcher(TextMatcher.basic("pdf")),
                new MetadataTextCondition()
                        .setFieldMatcher(TextMatcher.basic("type"))
                        .setValueMatcher(TextMatcher.basic("zip"))));
        var filter = excluding(new MetadataAllOf().setConditions(
                List.of(biggerThan("size", 100), (types))));

        assertThat(filter.acceptMetadata(REF,
                meta("size", "500", "type", "zip"))).isFalse();
        assertThat(filter.acceptMetadata(REF,
                meta("size", "50", "type", "zip"))).isTrue();
        assertThat(filter.acceptMetadata(REF,
                meta("size", "500", "type", "png"))).isTrue();

        var none = excluding(new MetadataNoneOf().setConditions(
                List.of(biggerThan("size", 100))));
        // none-of matches (and so excludes) when NOT big
        assertThat(none.acceptMetadata(REF, meta("size", "5"))).isFalse();
        assertThat(none.acceptMetadata(REF, meta("size", "500"))).isTrue();
    }

    @Test
    void noCondition_acceptsEverything() {
        var filter = new ConditionMetadataFilter();
        filter.getConfiguration().setOnMatch(OnMatch.EXCLUDE);
        assertThat(filter.acceptMetadata(REF, meta("a", "b"))).isTrue();
        assertThat(filter.acceptDocument(null)).isTrue();
    }

    @Test
    void conditionWithoutField_isRefused_neverReadsContent() {
        var filter = excluding(new MetadataTextCondition()
                .setValueMatcher(TextMatcher.basic("secret")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> filter.acceptMetadata(REF, meta()))
                .withMessageContaining("fieldMatcher");

        var blank = excluding(new MetadataBlankCondition());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> blank.acceptMetadata(REF, meta()))
                .withMessageContaining("fieldMatcher");
    }

    @Test
    void acceptDocument_usesMetadataOnly() {
        var filter = excluding(biggerThan("size", 100));
        var doc = new Doc(REF);
        doc.getMetadata().add("size", "500");
        assertThat(filter.acceptDocument(doc)).isFalse();
    }

    @Test
    void writeRead_roundTrip() {
        var filter = excluding(new MetadataAnyOf().setConditions(List.of(
                biggerThan("Content-Length", 10_000_000),
                new MetadataDateCondition()
                        .setFieldMatcher(TextMatcher.basic("Last-Modified"))
                        .setValueMatcher(new DateValueMatcher(
                                Operator.LOWER_THAN, "TODAY-2Y", null)),
                new MetadataNoneOf().setConditions(List.of(
                        new MetadataBlankCondition().setFieldMatcher(
                                TextMatcher.basic("Content-Type")))))));
        BeanMapper.DEFAULT.assertWriteRead(filter);
    }

    @Test
    void readFromYaml() {
        var yaml = """
                onMatch: EXCLUDE
                condition:
                  class: MetadataAnyOf
                  conditions:
                    - class: MetadataNumberCondition
                      fieldMatcher:
                        pattern: Content-Length
                      valueMatcher:
                        operator: gt
                        number: 1000
                """;
        var filter = new ConditionMetadataFilter();
        BeanMapper.DEFAULT.read(filter, new StringReader(yaml), Format.YAML);
        assertThat(filter.acceptMetadata(REF,
                meta("Content-Length", "2000"))).isFalse();
        assertThat(filter.acceptMetadata(REF,
                meta("Content-Length", "20"))).isTrue();
    }
}
