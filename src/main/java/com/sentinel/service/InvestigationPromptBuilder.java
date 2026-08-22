package com.sentinel.service;

import com.sentinel.domain.Transaction;
import com.sentinel.repository.SimilarCaseNote;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the prompt sent to the LLM from a flagged transaction, its
 * account's recent history, and similar past cases retrieved from
 * pgvector. Kept separate from InvestigationAgentService so the prompt
 * assembly logic can be unit tested without touching Kafka, the DB, or the
 * LLM.
 */
@Component
public class InvestigationPromptBuilder {

    public static final String SYSTEM_PROMPT = """
            You are a senior fraud analyst assistant helping triage flagged transactions. \
            Given a flagged transaction, the account's recent history, and similar past \
            cases retrieved by embedding similarity, assess the risk and recommend an \
            action. Respond with ONLY a JSON object matching exactly this schema — no \
            markdown fences, no commentary before or after:
            {"riskScore": <number 0.0-1.0>, "reasoning": "<2-4 sentences>", "recommendedAction": "<one of CONFIRM_FRAUD, DISMISS, ESCALATE>"}""";

    public String buildUserPrompt(Transaction txn, String flagReason,
                                   List<Transaction> recentHistory, List<SimilarCaseNote> similarCases) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Flagged Transaction\n");
        sb.append("Account: ").append(txn.getAccountId()).append('\n');
        sb.append("Amount: ").append(txn.getAmount()).append(' ').append(txn.getCurrency()).append('\n');
        sb.append("Merchant: ").append(txn.getMerchant()).append(" (").append(txn.getMerchantCategory()).append(")\n");
        sb.append("Country: ").append(txn.getCountry()).append('\n');
        sb.append("Timestamp: ").append(txn.getTimestamp()).append('\n');
        sb.append("Flag reason: ").append(flagReason).append("\n\n");

        sb.append("## Account's Recent Transaction History\n");
        if (recentHistory.isEmpty()) {
            sb.append("No prior transaction history for this account.\n\n");
        } else {
            for (Transaction h : recentHistory) {
                sb.append("- ").append(h.getAmount()).append(' ').append(h.getCurrency())
                        .append(" at ").append(h.getMerchant())
                        .append(" (").append(h.getCountry()).append("), ")
                        .append(h.getTimestamp()).append('\n');
            }
            sb.append('\n');
        }

        sb.append("## Similar Past Cases (retrieved by embedding similarity)\n");
        if (similarCases.isEmpty()) {
            sb.append("No similar past cases found.\n");
        } else {
            for (SimilarCaseNote note : similarCases) {
                sb.append("- [").append(note.label()).append(", distance=")
                        .append("%.3f".formatted(note.distance())).append("] ")
                        .append(note.narrative()).append('\n');
            }
        }

        return sb.toString();
    }
}
