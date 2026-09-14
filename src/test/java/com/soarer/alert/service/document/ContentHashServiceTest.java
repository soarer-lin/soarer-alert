package com.soarer.alert.service.document;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ContentHashServiceTest {

    private final ContentHashService service = new ContentHashService();

    @Test
    void computesSha256Hash() {
        String hash = service.sha256("abc".getBytes(StandardCharsets.UTF_8));

        assertThat(hash)
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
