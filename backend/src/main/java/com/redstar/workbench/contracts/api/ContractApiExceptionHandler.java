package com.redstar.workbench.contracts.api;

import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ContractController.class)
class ContractApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Void> handleNotFound(NoSuchElementException exception) {
        return ResponseEntity.notFound().build();
    }
}
