-- Schema hämtat från spring-security-oauth2-authorization-server-7.0.5.jar
-- (org/springframework/security/oauth2/server/authorization/client/oauth2-registered-client-schema.sql).
-- Timestamp-kolumner konverterade till TIMESTAMPTZ per jar:ens egna Postgres-instruktion.
--
-- Klienter seedas programmatiskt i OAuth2ClientSeeder (Task 8) via RegisteredClient.Builder
-- istället för INSERT här — Jackson-formatet i client_settings/token_settings är för fragilt
-- att skriva för hand.

CREATE TABLE oauth2_registered_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamptz DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    PRIMARY KEY (id)
);
