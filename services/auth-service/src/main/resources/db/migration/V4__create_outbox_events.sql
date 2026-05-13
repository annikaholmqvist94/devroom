-- Transactional outbox för domänevents. SignupService skriver hit i samma transaktion som
-- user-INSERTen; OutboxPublisher (Task 14) pollar denna tabell och publicerar till RabbitMQ
-- (kopplas in i Plan 04). At-least-once leverans → consumers måste vara idempotenta.

CREATE TABLE outbox_events (
    id            BIGSERIAL PRIMARY KEY,
    event_type    VARCHAR(64) NOT NULL,        -- "user.registered" etc
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at  TIMESTAMPTZ NULL             -- NULL = väntar på publicering
);

-- Partial index: bara unprocessed events ingår, vilket gör publisher-query effektiv
-- även när tabellen växer ("SELECT ... WHERE processed_at IS NULL ORDER BY created_at LIMIT N").
CREATE INDEX idx_outbox_unprocessed
    ON outbox_events(created_at)
    WHERE processed_at IS NULL;
