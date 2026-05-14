INSERT INTO teams (id, name)
VALUES ('11111111-1111-1111-1111-111111111111', 'Devroom Demo Team')
ON CONFLICT (id) DO NOTHING;

INSERT INTO users (user_id, display_name, team_id, is_system, mentor_personality)
VALUES
    ('22222222-2222-2222-2222-222222222201', 'junior-helper',
     '11111111-1111-1111-1111-111111111111', TRUE, 'junior-helper'),
    ('22222222-2222-2222-2222-222222222202', 'senior-architect',
     '11111111-1111-1111-1111-111111111111', TRUE, 'senior-architect'),
    ('22222222-2222-2222-2222-222222222203', 'code-reviewer',
     '11111111-1111-1111-1111-111111111111', TRUE, 'code-reviewer'),
    ('22222222-2222-2222-2222-222222222204', 'rubber-duck',
     '11111111-1111-1111-1111-111111111111', TRUE, 'rubber-duck')
ON CONFLICT (user_id) DO NOTHING;
