package com.sentinel.repository;

/**
 * A past case note retrieved by embedding similarity, with its cosine
 * distance to the query (lower = more similar; pgvector's `<=>` operator
 * returns 1 - cosine_similarity, so 0.0 is an exact match).
 */
public record SimilarCaseNote(String narrative, String label, double distance) {
}
