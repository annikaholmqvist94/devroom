package com.devroom.auth.application;

/**
 * Kastas av SignupService när en user med samma email redan finns.
 * RuntimeException → @Transactional rullar tillbaka utan extra rollbackFor-konfiguration.
 */
public class DuplicateEmailException extends RuntimeException {

    private final String email;

    public DuplicateEmailException(String email) {
        super("Email already exists: " + email);
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}
