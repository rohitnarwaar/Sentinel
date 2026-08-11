package com.sentinel.repository;

import com.sentinel.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.accountId = :accountId
        AND t.timestamp >= :since
        ORDER BY t.timestamp DESC
        """)
    List<Transaction> findRecentByAccount(@Param("accountId") String accountId,
                                           @Param("since") Instant since);

    List<Transaction> findTop20ByAccountIdOrderByTimestampDesc(String accountId);
}
