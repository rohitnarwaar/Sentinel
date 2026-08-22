package com.sentinel.service;

import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Turns text into a fixed-dimension vector for pgvector similarity search.
 *
 * Design tradeoff: this is a local, deterministic feature-hashing embedding
 * (the "hashing trick" — bag-of-words tokens hashed into fixed buckets,
 * signed, then L2-normalized) rather than a call to a hosted embeddings API
 * (OpenAI, Voyage AI). It costs nothing and needs no extra API key, which
 * keeps this a single-key (Anthropic-only) project. The honest cost: it
 * captures shared vocabulary, not real semantic meaning — two case notes
 * using different words for the same fraud pattern won't necessarily land
 * close together. For this project's synthetic case notes (written with
 * consistent, category-specific vocabulary) that's an acceptable tradeoff.
 * A production system would swap this for a real embedding model without
 * touching anything else — CaseNoteRepository only cares that it gets back
 * a float[] of DIMENSIONS length.
 */
@Service
public class EmbeddingService {

    public static final int DIMENSIONS = 128;

    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return vector;
        }

        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            int hash = token.hashCode();
            int index = Math.floorMod(hash, DIMENSIONS);
            // Sign derived from a different bit of the hash than the index,
            // so hash collisions partially cancel instead of always stacking.
            float sign = ((hash >>> 31) == 0) ? 1f : -1f;
            vector[index] += sign;
        }

        return normalize(vector);
    }

    private float[] normalize(float[] vector) {
        double sumSquares = 0.0;
        for (float v : vector) {
            sumSquares += (double) v * v;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm == 0.0) {
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }
}
