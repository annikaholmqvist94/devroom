package com.devroom.user.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    List<User> findAllByTeamIdAndDisplayNameIn(UUID teamId, List<String> displayNames);

    boolean existsByUserId(UUID userId);
}
