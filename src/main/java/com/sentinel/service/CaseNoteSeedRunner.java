package com.sentinel.service;

import com.sentinel.repository.CaseNoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Bootstraps the case_notes table on startup — creates the pgvector
 * extension and table if missing (Hibernate's ddl-auto doesn't know about
 * the `vector` type, so this table manages its own schema), then seeds it
 * with synthetic past-case narratives exactly once. Safe to run on every
 * startup: both the DDL and the seed insert are idempotent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CaseNoteSeedRunner implements CommandLineRunner {

    private final CaseNoteRepository caseNoteRepository;
    private final EmbeddingService embeddingService;

    @Override
    public void run(String... args) {
        caseNoteRepository.createSchemaIfMissing(EmbeddingService.DIMENSIONS);

        long existing = caseNoteRepository.count();
        if (existing > 0) {
            log.info("case_notes already seeded ({} rows) — skipping", existing);
            return;
        }

        for (SeedCaseNotes.SeedNote note : SeedCaseNotes.ALL) {
            float[] embedding = embeddingService.embed(note.narrative());
            caseNoteRepository.insert(note.narrative(), note.label(), embedding);
        }
        log.info("Seeded {} synthetic case notes into pgvector", SeedCaseNotes.ALL.size());
    }
}
