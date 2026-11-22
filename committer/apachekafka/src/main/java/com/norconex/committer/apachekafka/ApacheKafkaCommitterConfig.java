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

package com.norconex.committer.apachekafka;

import com.norconex.committer.core.batch.BaseBatchCommitterConfig;
import com.norconex.commons.lang.security.Credentials;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Configuration for {@link ApacheKafkaCommitter}.
 * </p>
 */
@Data
@Accessors(chain = true)
public class ApacheKafkaCommitterConfig extends BaseBatchCommitterConfig {

    /**
     * The topic name to which documents will be sent
     */
    private String topicName;

    /**
     * The Apache Kafka broker list, comma-separated
     * (e.g., host1:port1,host2:port2,...).
     */
    private String bootstrapServers;

    /**
     * Whether to create the topic in Apache Kafka.
     * It will be created only if it is not already present.
     */
    private boolean createTopic;

    /**
     * The number of partitions for the new topic.
     * Required if {@link #isCreateTopic()} is <code>true</code>
     */
    private int partitions;

    /**
     * Gets the replication factor for the new topic.
     * Required if {@link #isCreateTopic()} is <code>true</code>
     */
    private short replicationFactor;

    /**
     * How the Kafka client connects to the brokers: whether the connection
     * is encrypted (TLS) and whether the client signs in (SASL). When not
     * set, the Kafka default ({@link SecurityProtocol#PLAINTEXT}) applies.
     */
    private SecurityProtocol securityProtocol;

    /**
     * How the client signs in when {@link #getSecurityProtocol()} is
     * {@link SecurityProtocol#SASL_PLAINTEXT} or
     * {@link SecurityProtocol#SASL_SSL}. Defaults to
     * {@link SaslMechanism#PLAIN} when those are used and nothing is set.
     */
    private SaslMechanism saslMechanism;

    /**
     * The user name and password to sign in with when using SASL. The
     * password can be encrypted, so it stays out of the configuration.
     */
    private final Credentials credentials = new Credentials();

    public ApacheKafkaCommitterConfig setCredentials(Credentials credentials) {
        this.credentials.copyFrom(credentials);
        return this;
    }

    /**
     * Kafka client security protocols.
     */
    public enum SecurityProtocol {
        /** No encryption, no sign-in. */
        PLAINTEXT,
        /** Encrypted with TLS, no sign-in. */
        SSL,
        /** Sign-in with SASL, not encrypted. */
        SASL_PLAINTEXT,
        /** Sign-in with SASL, encrypted with TLS. */
        SASL_SSL;

        boolean isSasl() {
            return this == SASL_PLAINTEXT || this == SASL_SSL;
        }
    }

    /**
     * Kafka SASL sign-in mechanisms using a user name and password.
     */
    public enum SaslMechanism {
        /** The user name and password are sent as they are. */
        PLAIN("PLAIN"),
        /** Salted challenge-response with SHA-256. */
        SCRAM_SHA_256("SCRAM-SHA-256"),
        /** Salted challenge-response with SHA-512. */
        SCRAM_SHA_512("SCRAM-SHA-512");

        private final String kafkaName;

        SaslMechanism(String kafkaName) {
            this.kafkaName = kafkaName;
        }

        String kafkaName() {
            return kafkaName;
        }
    }
}
