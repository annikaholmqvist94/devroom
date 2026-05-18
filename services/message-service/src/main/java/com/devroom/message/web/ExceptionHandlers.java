package com.devroom.message.web;

import com.devroom.message.application.ChannelNotFoundException;
import com.devroom.message.application.MentionResolutionException;
import com.devroom.message.application.SenderNotAllowedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ExceptionHandlers {

    @ExceptionHandler(ChannelNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleChannelNotFound(ChannelNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MentionResolutionException.class)
    public ResponseEntity<Map<String, String>> handleMentionResolution(MentionResolutionException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Mention resolution failed"));
    }

    @ExceptionHandler(SenderNotAllowedException.class)
    public ResponseEntity<Map<String, String>> handleSenderNotAllowed(SenderNotAllowedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
