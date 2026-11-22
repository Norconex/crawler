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

package com.norconex.committer.apachekafka;

import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class KafkaAdmin {

    private Admin admin;

    public KafkaAdmin(ApacheKafkaCommitterConfig config) {
        var props = new Properties();
        props.put(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                config.getBootstrapServers());
        KafkaSecurity.apply(props, config);
        admin = Admin.create(props);
    }

    public void ensureTopicExists(
            String topicName,
            int partitions,
            short replicationFactor) {

        if (isTopicExists(topicName)) {
            LOG.info("Topic `{}` already exists.", topicName);
            return;
        }

        createTopic(topicName, partitions, replicationFactor);
    }

    public boolean isTopicExists(String topicName) {
        var topics = admin.listTopics();

        Set<String> topicNames = null;
        try {
            topicNames = topics.names().get();
        } catch (InterruptedException | ExecutionException e) {
            LOG.error("Listing topic names interrupted.", e);
            Thread.currentThread().interrupt();
        }

        if (topicNames == null) {
            return false;
        }

        return topicNames.contains(topicName);
    }

    public void close() {
        admin.close();
    }

    private void createTopic(
            String topicName,
            int partitions,
            short replicationFactor) {
        LOG.info("Creating compacted topic `{}`...", topicName);

        var result = admin.createTopics(Collections.singleton(
                new NewTopic(topicName, partitions, replicationFactor)
                        .configs(Collections.singletonMap(
                                TopicConfig.CLEANUP_POLICY_CONFIG,
                                TopicConfig.CLEANUP_POLICY_COMPACT))));

        var future = result.values().get(topicName);

        try {
            future.get();
        } catch (InterruptedException | ExecutionException e) {
            LOG.error("Creating topic `{}` interrupted.", topicName, e);
            Thread.currentThread().interrupt();
        }
        LOG.info("Done");
    }
}
