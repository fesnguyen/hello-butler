INSERT INTO applications(id, name, description, github_url)
VALUES ('559dfac4-3136-446b-8393-d64b318d2520', 'Hello Butler',
'A personal AI butler for daily planning, conversations, and remembering what matters to you.',
'https://github.com/fesnguyen/hello-butler')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name,
 description = EXCLUDED.description, github_url = EXCLUDED.github_url;
