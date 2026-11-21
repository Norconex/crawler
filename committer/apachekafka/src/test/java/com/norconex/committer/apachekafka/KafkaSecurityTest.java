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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.norconex.committer.apachekafka.ApacheKafkaCommitterConfig.SaslMechanism;
import com.norconex.committer.apachekafka.ApacheKafkaCommitterConfig.SecurityProtocol;
import com.norconex.commons.lang.security.Credentials;

class KafkaSecurityTest {

    private static ApacheKafkaCommitterConfig config(
            SecurityProtocol protocol, SaslMechanism mechanism,
            String user, String password) {
        var creds = new Credentials();
        creds.setUsername(user);
        creds.setPassword(password);
        return new ApacheKafkaCommitterConfig()
                .setSecurityProtocol(protocol)
                .setSaslMechanism(mechanism)
                .setCredentials(creds);
    }

    @Test
    void nothingIsAddedWhenNoSecurityIsAskedFor() {
        var props = new Properties();
        KafkaSecurity.apply(props, new ApacheKafkaCommitterConfig());
        assertThat(props).isEmpty();
    }

    @Test
    void tlsOnlyAddsTheProtocol() {
        var props = new Properties();
        KafkaSecurity.apply(props,
                config(SecurityProtocol.SSL, null, "u", "p"));
        assertThat(props).containsOnlyKeys("security.protocol")
                .containsEntry("security.protocol", "SSL");
    }

    @Test
    void saslDefaultsToPlainWithTheCredentialsAsTheLogin() {
        var props = new Properties();
        KafkaSecurity.apply(props,
                config(SecurityProtocol.SASL_SSL, null, "ann", "s3cret"));
        assertThat(props)
                .containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "PLAIN")
                .containsEntry("sasl.jaas.config",
                        "org.apache.kafka.common.security.plain."
                                + "PlainLoginModule required "
                                + "username=\"ann\" password=\"s3cret\";");
    }

    @Test
    void scramUsesItsOwnMechanismNameAndLoginModule() {
        var props = new Properties();
        KafkaSecurity.apply(props, config(SecurityProtocol.SASL_PLAINTEXT,
                SaslMechanism.SCRAM_SHA_256, "ann", "pw"));
        assertThat(props).containsEntry("sasl.mechanism", "SCRAM-SHA-256");
        assertThat(props.getProperty("sasl.jaas.config")).startsWith(
                "org.apache.kafka.common.security.scram.ScramLoginModule");
    }

    @Test
    void quotesAndBackslashesInTheLoginAreEscaped() {
        assertThat(KafkaSecurity.jaasConfig(
                SaslMechanism.PLAIN, "a\"b", "c\\d")).contains(
                        "username=\"a\\\"b\" password=\"c\\\\d\"");
    }

    @Test
    void saslWithoutAUserNameIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> KafkaSecurity.apply(new Properties(),
                        config(SecurityProtocol.SASL_SSL, null, "", "pw")));
    }
}
