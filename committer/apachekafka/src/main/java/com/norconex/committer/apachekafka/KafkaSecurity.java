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
package com.norconex.committer.apachekafka;

import java.util.Properties;

import org.apache.commons.lang3.StringUtils;

import com.norconex.committer.apachekafka.ApacheKafkaCommitterConfig.SaslMechanism;
import com.norconex.commons.lang.encrypt.EncryptionUtil;

/**
 * Turns the security settings of a Kafka committer into the properties both
 * its producer and its admin client are given.
 */
final class KafkaSecurity {

    private KafkaSecurity() {
    }

    /**
     * Adds the security properties the configuration asks for. Nothing is
     * added when it asks for none, leaving the Kafka defaults.
     * @param props the client properties to complete
     * @param cfg the committer configuration
     */
    static void apply(Properties props, ApacheKafkaCommitterConfig cfg) {
        var protocol = cfg.getSecurityProtocol();
        if (protocol == null) {
            return;
        }
        props.put("security.protocol", protocol.name());
        if (!protocol.isSasl()) {
            return;
        }
        var mechanism = cfg.getSaslMechanism() == null
                ? SaslMechanism.PLAIN
                : cfg.getSaslMechanism();
        var creds = cfg.getCredentials();
        if (StringUtils.isBlank(creds.getUsername())) {
            throw new IllegalArgumentException(
                    "Signing in to Kafka with SASL requires credentials "
                            + "with a user name and a password.");
        }
        props.put("sasl.mechanism", mechanism.kafkaName());
        props.put("sasl.jaas.config", jaasConfig(mechanism,
                creds.getUsername(),
                EncryptionUtil.decrypt(
                        creds.getPassword(), creds.getPasswordKey())));
    }

    static String jaasConfig(
            SaslMechanism mechanism, String username, String password) {
        var module = mechanism == SaslMechanism.PLAIN
                ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                : "org.apache.kafka.common.security.scram.ScramLoginModule";
        return module + " required username=\"" + quote(username)
                + "\" password=\"" + quote(password) + "\";";
    }

    private static String quote(String value) {
        return StringUtils.defaultString(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
