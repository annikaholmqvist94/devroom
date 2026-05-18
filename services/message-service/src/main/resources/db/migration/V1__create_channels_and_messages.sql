CREATE TABLE channels (
    id          UUID PRIMARY KEY,
    team_id     UUID NOT NULL,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (team_id, name)
);

CREATE TABLE messages (
    id                  UUID PRIMARY KEY,
    channel_id          UUID NOT NULL REFERENCES channels(id),
    sender_id           UUID NOT NULL,
    body                TEXT NOT NULL,
    parent_message_id   UUID REFERENCES messages(id),
    mentions            JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_messages_channel_created
    ON messages(channel_id, created_at DESC);

CREATE INDEX idx_messages_parent
    ON messages(parent_message_id)
    WHERE parent_message_id IS NOT NULL;
