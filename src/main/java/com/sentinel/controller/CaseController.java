package com.sentinel.controller;

import com.sentinel.domain.CaseStatus;
import com.sentinel.domain.FraudCase;
import com.sentinel.repository.FraudCaseRepository;
import com.sentinel.service.CaseEventBroadcaster;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/cases")
@RequiredArgsConstructor
public class CaseController {

    private final FraudCaseRepository fraudCaseRepository;
    private final CaseEventBroadcaster caseEventBroadcaster;

    @GetMapping
    public List<FraudCase> open() {
        return fraudCaseRepository.findByStatusOrderByCreatedAtDesc(CaseStatus.OPEN);
    }

    /** All cases regardless of status, most recently active first — the dashboard's initial snapshot. */
    @GetMapping("/all")
    public List<FraudCase> all() {
        return fraudCaseRepository.findMostRecentlyActive(PageRequest.of(0, 100));
    }

    @GetMapping("/account/{accountId}")
    public List<FraudCase> byAccount(@PathVariable String accountId) {
        return fraudCaseRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    /** Live case updates over Server-Sent Events — what the dashboard subscribes to after its initial fetch. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return caseEventBroadcaster.subscribe();
    }

    @PostMapping("/{caseId}/decision")
    public FraudCase decide(@PathVariable String caseId, @RequestParam CaseStatus status) {
        FraudCase fraudCase = fraudCaseRepository.findById(caseId)
                .orElseThrow(() -> new EntityNotFoundException("Case not found: " + caseId));
        fraudCase.setStatus(status);
        fraudCase.setReviewedAt(Instant.now());
        FraudCase saved = fraudCaseRepository.save(fraudCase);
        caseEventBroadcaster.broadcast(saved);
        return saved;
    }
}
