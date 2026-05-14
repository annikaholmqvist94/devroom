package com.devroom.user.application;

import com.devroom.user.domain.User;
import com.devroom.user.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserRegisteredHandler {

    private static final Logger log = LoggerFactory.getLogger(UserRegisteredHandler.class);

    private final UserRepository repo;

    public UserRegisteredHandler(UserRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void handle(UUID userId, String email, UUID teamId) {
        if (repo.existsByUserId(userId)) {
            log.info("User {} already exists, skipping (idempotency)", userId);
            return;
        }
        User user = new User(userId, email, teamId, false, null);
        repo.save(user);
        log.info("Created profile for user {}", userId);
    }
}
