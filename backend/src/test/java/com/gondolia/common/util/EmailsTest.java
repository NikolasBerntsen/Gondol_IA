package com.gondolia.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailsTest {

    @Test
    void lowercasesAndTrims() {
        assertThat(Emails.normalize("  Admin@ElSol.COM ")).isEqualTo("admin@elsol.com");
    }

    @Test
    void returnsNullForBlank() {
        assertThat(Emails.normalize(null)).isNull();
        assertThat(Emails.normalize("   ")).isNull();
    }
}
