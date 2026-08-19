package com.sentinel.controller;

import com.sentinel.domain.Transaction;
import com.sentinel.dto.TransactionEvent;
import com.sentinel.repository.TransactionRepository;
import com.sentinel.service.TransactionProducerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionRepository transactionRepository;
    private final TransactionProducerService producerService;

    /** Manually publish a transaction event — useful for testing specific scenarios. */
    @PostMapping
    public ResponseEntity<Void> submit(@Valid @RequestBody TransactionEvent event) {
        producerService.publish(event);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @GetMapping("/{accountId}")
    public List<Transaction> byAccount(@PathVariable String accountId) {
        return transactionRepository.findTop20ByAccountIdOrderByTimestampDesc(accountId);
    }

    @GetMapping
    public List<Transaction> recent() {
        return transactionRepository
                .findAll(PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "timestamp")))
                .getContent();
    }
}
