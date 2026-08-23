package com.sentinel.repository;

import com.sentinel.domain.CaseStatus;
import com.sentinel.domain.FraudCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudCaseRepository extends JpaRepository<FraudCase, String> {

    List<FraudCase> findByStatusOrderByCreatedAtDesc(CaseStatus status);

    List<FraudCase> findByAccountIdOrderByCreatedAtDesc(String accountId);

    List<FraudCase> findTop100ByOrderByCreatedAtDesc();
}
