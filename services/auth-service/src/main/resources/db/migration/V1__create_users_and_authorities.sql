-- Spring Security users-tabell, utökad med Devroom-specifika kolumner (user_id, team_id).
-- DevroomUserDetailsService (Task 12) läser denna via JPA-entiteten DevroomUser.

CREATE TABLE users (
    user_id     UUID PRIMARY KEY,
    username    VARCHAR(255) UNIQUE NOT NULL,        -- email
    password    VARCHAR(255) NOT NULL,                -- BCrypt-hash (~60 tecken)
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    team_id     UUID NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- UNIQUE-constraint på username skapar redan B-tree-index automatiskt i Postgres,
-- så ingen separat CREATE INDEX behövs för findByUsername-lookups.

-- Spring Security authorities-tabell: roller/grants per user.
-- authority = "ROLE_USER", "ROLE_ADMIN", "SCOPE_openid" etc.
CREATE TABLE authorities (
    username   VARCHAR(255) NOT NULL,
    authority  VARCHAR(255) NOT NULL,
    CONSTRAINT fk_authorities_users FOREIGN KEY (username) REFERENCES users(username),
    UNIQUE (username, authority)
);
