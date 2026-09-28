/* Copyright 2023-2024 Norconex Inc.
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

package com.norconex.committer.apachekafka;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.CommitterUtil;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.batch.AbstractBatchCommitter;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Commits documents to Kafka via it's Producer API
 * </p>
 *
 * <h2>Topic Creation</h2>
 * <p>
 * By default topics are assumed to already exist. You can optionally
 * have them created when not already present with
 * {@link ApacheKafkaCommitterConfig#setCreateTopic(boolean)}.
 * </p>
 */
@Slf4j
@EqualsAndHashCode
@ToString
public class ApacheKafkaCommitter
        extends AbstractBatchCommitter<ApacheKafkaCommitterConfig> {

    private static final String EXCEPTION_MSG_INVALID_CONFIG =
            "Invalid configuration. Both `topicName` and `bootstrapServers` "
                    + "are required.";
    private static final String CREATE_TOPIC_CONFIG = "createTopic";
    private static final String NUM_OF_PARTITIONS_CONFIG = "numOfPartitions";
    private static final String REPLICATION_FACTOR_CONFIG = "replicationFactor";

    @Getter
    private final ApacheKafkaCommitterConfig configuration =
            new ApacheKafkaCommitterConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private KafkaProducer<String, String> producer;
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private KafkaAdmin kafkaAdmin;

    @Override
    protected void initBatchCommitter() throws CommitterException {
        if (StringUtils.isBlank(configuration.getTopicName()) ||
                StringUtils.isBlank(configuration.getBootstrapServers())) {
            throw new CommitterException(EXCEPTION_MSG_INVALID_CONFIG);
        }

        kafkaAdmin = new KafkaAdmin(configuration.getBootstrapServers());

        if (configuration.isCreateTopic()) {
            if (configuration.getPartitions() == 0
                    || configuration.getReplicationFactor() == 0) {
                var msg = String.format(
                        "%s=true requires these settings be also set: %s, %s",
                        CREATE_TOPIC_CONFIG,
                        NUM_OF_PARTITIONS_CONFIG,
                        REPLICATION_FACTOR_CONFIG);
                throw new CommitterException(msg);
            }

            LOG.info(
                    "Ensuring topic `{}` exists in Kafka",
                    configuration.getTopicName());
            kafkaAdmin.ensureTopicExists(
                    configuration.getTopicName(),
                    configuration.getPartitions(),
                    configuration.getReplicationFactor());

        } else if (!kafkaAdmin.isTopicExists(configuration.getTopicName())) {
            throw new CommitterException(String.format("""
                    Topic `%s` does not exist in Kafka. \
                    Either create the topic manually or set \
                    `%s` to true.""",
                    configuration.getTopicName(), CREATE_TOPIC_CONFIG));
        }
    }

    @Override
    protected void commitBatch(Iterator<CommitterRequest> it)
            throws CommitterException {
        if (producer == null) {
            createProducer();
        }

        LOG.info("Committing batch to Apache Kafka");

        var docCountUpserts = 0;
        var docCountDeletes = 0;
        try {
            while (it.hasNext()) {
                var req = it.next();
                if (req instanceof UpsertRequest upsert) {
                    var json = new StringBuilder();
                    appendUpsertRequest(json, upsert);

                    var rec = new ProducerRecord<>(
                            configuration.getTopicName(),
                            upsert.getReference(),
                            json.toString());

                    producer.send(rec);

                    docCountUpserts++;
                    json.setLength(0);

                } else if (req instanceof DeleteRequest delete) {
                    var json = new StringBuilder();

                    var rec = new ProducerRecord<String, String>(
                            configuration.getTopicName(), delete.getReference(),
                            null);

                    producer.send(rec);

                    docCountDeletes++;
                    json.setLength(0);
                } else {
                    throw new CommitterException("Unsupported request: " + req);
                }
            }

            if (docCountUpserts > 0) {
                LOG.info("Sent {} upsert commit operation(s) to Apache Kafka.",
                        docCountUpserts);
            }

            if (docCountDeletes > 0) {
                LOG.info("Sent {} delete commit operation(s) to Apache Kafka.",
                        docCountDeletes);
            }

        } catch (CommitterException e) {
            throw e;
        } catch (Exception e) {
            throw new CommitterException(
                    "Could not commit JSON batch to Elasticsearch.", e);
        }
    }

    @Override
    protected void closeBatchCommitter() throws CommitterException {
        LOG.info("Flushing and closing Kafka Producer client...");
        producer.flush();
        producer.close();
        LOG.info("Done");

        LOG.info("Closing Kafka Admin client");
        kafkaAdmin.close();
    }

    private synchronized KafkaProducer<String, String> createProducer() {
        var props = new Properties();
        props.put("bootstrap.servers", configuration.getBootstrapServers());
        props.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        props.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        props.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        producer = new KafkaProducer<>(props);

        LOG.info("Created Apache Kafka producer client");
        return producer;
    }

    private void appendUpsertRequest(StringBuilder json, UpsertRequest upsert)
            throws CommitterException {
        CommitterUtil.applyTargetContent(upsert, "content");

        json.append("{");
        appendFieldAndValue(json, "id", upsert.getReference());
        json.append(",");
        for (Map.Entry<String, List<String>> entry : upsert.getMetadata()
                .entrySet()) {
            var field = entry.getKey();
            append(json, field, entry.getValue());
            json.append(",");
        }
        json.deleteCharAt(json.length() - 1);
        json.append("}\n");
    }

    private void append(StringBuilder json, String field, List<String> values) {
        if (values.size() == 1) {
            appendFieldAndValue(json, field, values.get(0));
            return;
        }

        json.append("\"")
                .append(StringEscapeUtils.escapeJson(field))
                .append("\":[");

        for (String value : values) {
            appendValue(json, value);
            json.append(',');
        }
        json.deleteCharAt(json.length() - 1);
        json.append(']');
    }

    private void appendFieldAndValue(
            StringBuilder json, String field, String value) {
        json.append("\"")
                .append(StringEscapeUtils.escapeJson(field))
                .append("\":");
        appendValue(json, value);
    }

    private void appendValue(StringBuilder json, String value) {
        json.append("\"")
                .append(StringEscapeUtils.escapeJson(value))
                .append("\"");
    }
}
