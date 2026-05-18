-- Seed three channels in the same demo team that user-service seeds its mentor
-- users into. team_id must match user-service V2 exactly, otherwise mention
-- resolution will return empty results across the gRPC boundary.
INSERT INTO channels (id, team_id, name) VALUES
    ('33333333-3333-3333-3333-333333333301', '11111111-1111-1111-1111-111111111111', 'general'),
    ('33333333-3333-3333-3333-333333333302', '11111111-1111-1111-1111-111111111111', 'frontend'),
    ('33333333-3333-3333-3333-333333333303', '11111111-1111-1111-1111-111111111111', 'backend')
ON CONFLICT (id) DO NOTHING;
