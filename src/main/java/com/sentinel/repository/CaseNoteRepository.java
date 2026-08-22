package com.sentinel.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Plain JDBC, not Spring Data JPA — Hibernate has no built-in mapping for
 * Postgres's `vector` column type, and pulling in a Hibernate UserType just
 * for one table isn't worth it here. This is the one place in the app that
 * talks to the DB with raw SQL instead of a JpaRepository; everything else
 * stays ordinary JPA.
 */
@Repository
@RequiredArgsConstructor
public class CaseNoteRepository {

    private final JdbcTemplate jdbcTemplate;

    public void createSchemaIfMissing(int dimensions) {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS case_notes (
                    id BIGSERIAL PRIMARY KEY,
                    narrative TEXT NOT NULL,
                    label VARCHAR(64) NOT NULL,
                    embedding vector(%d) NOT NULL
                )
                """.formatted(dimensions));
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM case_notes", Long.class);
        return count == null ? 0 : count;
    }

    public void insert(String narrative, String label, float[] embedding) {
        jdbcTemplate.update(
                "INSERT INTO case_notes (narrative, label, embedding) VALUES (?, ?, ?::vector)",
                narrative, label, toVectorLiteral(embedding));
    }

    /** Nearest neighbors by cosine distance (pgvector's {@code <=>} operator). */
    public List<SimilarCaseNote> findTopKSimilar(float[] queryEmbedding, int k) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        return jdbcTemplate.query(
                """
                SELECT narrative, label, embedding <=> ?::vector AS distance
                FROM case_notes
                ORDER BY distance ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new SimilarCaseNote(
                        rs.getString("narrative"), rs.getString("label"), rs.getDouble("distance")),
                vectorLiteral, k);
    }

    private static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }
}
