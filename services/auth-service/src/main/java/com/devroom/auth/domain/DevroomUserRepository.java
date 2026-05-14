package com.devroom.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data genererar implementationen vid uppstart. Metod-namnen följer Spring Data-konventionen
 * (findBy.../existsBy...) och översätts automatiskt till JPQL.
 */
public interface DevroomUserRepository extends JpaRepository<DevroomUser, UUID> {

    Optional<DevroomUser> findByUsername(String username);

    boolean existsByUsername(String username);
}
