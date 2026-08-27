package com.sentinel.repository;

import com.sentinel.domain.CaseStatus;
import com.sentinel.domain.FraudCase;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FraudCaseRepository extends JpaRepository<FraudCase, String> {

    List<FraudCase> findByStatusOrderByCreatedAtDesc(CaseStatus status);

    List<FraudCase> findByAccountIdOrderByCreatedAtDesc(String accountId);

    /**
     * Most recently active first — see FraudCase.updatedAt for why this isn't
     * createdAt. Explicit NULLS LAST because Postgres sorts NULL as the
     * largest value by default: without it, any row that predates the
     * updatedAt column (or was never re-saved after adding it) would rank
     * as if it were the *most* recently active, which is backwards — a row
     * this method exists specifically to keep in the back seat.
     */
    @Query("SELECT f FROM FraudCase f ORDER BY f.updatedAt DESC NULLS LAST")
    List<FraudCase> findMostRecentlyActive(Pageable pageable);
}
