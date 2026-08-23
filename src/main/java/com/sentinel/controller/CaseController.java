package com.sentinel.controller;

import com.sentinel.domain.CaseStatus;
import com.sentinel.domain.FraudCase;
import com.sentinel.repository.FraudCaseRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/cases")
@RequiredArgsConstructor
public class CaseController {

    private final FraudCaseRepository fraudCaseRepository;

    @GetMapping
    public List<FraudCase> open() {
        return fraudCaseRepository.findByStatusOrderByCreatedAtDesc(CaseStatus.OPEN);
    }

    /** All cases regardless of status, most recent first — what the dashboard polls. */
    @GetMapping("/all")
    public List<FraudCase> all() {
        return fraudCaseRepository.findTop100ByOrderByCreatedAtDesc();
    }

    @GetMapping("/account/{accountId}")
    public List<FraudCase> byAccount(@PathVariable String accountId) {
        return fraudCaseRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    @PostMapping("/{caseId}/decision")
    public FraudCase decide(@PathVariable String caseId, @RequestParam CaseStatus status) {
        FraudCase fraudCase = fraudCaseRepository.findById(caseId)
                .orElseThrow(() -> new EntityNotFoundException("Case not found: " + caseId));
        fraudCase.setStatus(status);
        fraudCase.setReviewedAt(Instant.now());
        return fraudCaseRepository.save(fraudCase);
    }
}
