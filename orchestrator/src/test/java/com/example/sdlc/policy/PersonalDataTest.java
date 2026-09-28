package com.example.sdlc.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PersonalDataTest {

    @ParameterizedTest
    @ValueSource(strings = {"ip", "IP", "client_ip", "\"CLIENT_IP\"", "`remote_addr`", "[ipv6]", "ip_address", "ipaddress",
            "source_ipv4", "email", "e_mail", "contact_email", "phone", "phone_number", "msisdn", "user_agent", "useragent",
            "clientIp", "userAgent", "IPAddress", "remote_address", "last_ip_seen"})
    void rawPersonalDataColumnsAreRecognised(String column) {
        assertThat(PersonalData.isRawPersonalDataColumn(column)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"description", "zip", "recipient", "shipping", "shipping_address", "equipment", "tip_amount",
            "phonetic_name", "emailed_at_count", "visitor_hash", "ip_hash", "email_hmac", "user_agent_fingerprint",
            "phone_digest", "id", ""})
    void otherAndDerivedColumnsAreNot(String column) {
        assertThat(PersonalData.isRawPersonalDataColumn(column)).isFalse();
    }
}
