package com.devroom.message.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByChannelIdOrderByCreatedAtAsc(UUID channelId);

    @Query("""
            SELECT m FROM Message m
            WHERE m.channelId = :channelId
              AND m.createdAt > :since
            ORDER BY m.createdAt ASC
            """)
    List<Message> findByChannelSince(@Param("channelId") UUID channelId,
                                      @Param("since") Instant since);
}
