package com.sentinel.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingServiceTest {

    private final EmbeddingService embeddingService = new EmbeddingService();

    @Test
    void sameTextProducesIdenticalVector() {
        float[] a = embeddingService.embed("card testing rapid small authorizations");
        float[] b = embeddingService.embed("card testing rapid small authorizations");

        assertThat(a).containsExactly(b);
    }

    @Test
    void differentTextProducesDifferentVectors() {
        float[] a = embeddingService.embed("card testing rapid small authorizations");
        float[] b = embeddingService.embed("legitimate grocery and gas station spending");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void vectorHasExpectedDimensionsAndIsNormalized() {
        float[] vector = embeddingService.embed("account takeover password reset new device");

        assertThat(vector).hasSize(EmbeddingService.DIMENSIONS);

        double norm = 0.0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void blankTextReturnsZeroVectorWithoutError() {
        float[] vector = embeddingService.embed("   ");

        assertThat(vector).hasSize(EmbeddingService.DIMENSIONS);
        for (float v : vector) {
            assertThat(v).isZero();
        }
    }
}
