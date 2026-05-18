package com.devroom.message.application;

public class SenderNotAllowedException extends RuntimeException {
    public SenderNotAllowedException(String message) {
        super(message);
    }
}
