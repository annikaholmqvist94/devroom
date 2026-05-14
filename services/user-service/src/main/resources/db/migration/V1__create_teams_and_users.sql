CREATE TABLE teams (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE users (
    user_id              UUID PRIMARY KEY,
    display_name         VARCHAR(100) NOT NULL,
    avatar_url           VARCHAR(500),
    team_id              UUID NOT NULL REFERENCES teams(id),
    is_system            BOOLEAN NOT NULL DEFAULT FALSE,
    mentor_personality   VARCHAR(50),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_team ON users(team_id);
CREATE INDEX idx_users_team_name ON users(team_id, display_name);
