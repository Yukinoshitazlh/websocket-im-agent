-- Clean up legacy benchmark accounts (bench_u_*) and orphaned temporary load-test accounts (tmp_lt_*)
-- Safe scope:
--   1) Only removes users whose username starts with 'bench_u_' or 'tmp_lt_'.
--   2) Only removes conversations that have NO real human users (user_type <> 2 and non-bench).
--   3) Runs all DELETE statements inside a single transaction.
--
-- PowerShell usage:
--   Get-Content -Raw -Encoding UTF8 scripts/cleanup-bench-users.sql | mysql --default-character-set=utf8mb4 -u root -p
USE im_agent_chat;

DROP TEMPORARY TABLE IF EXISTS tmp_bench_user;
DROP TEMPORARY TABLE IF EXISTS tmp_keep_user;
DROP TEMPORARY TABLE IF EXISTS tmp_bench_conv;
DROP TEMPORARY TABLE IF EXISTS tmp_bench_msg;

CREATE TEMPORARY TABLE tmp_bench_user (id BIGINT PRIMARY KEY)
SELECT id FROM im_user
WHERE username LIKE 'bench\_u\_%' OR username LIKE 'tmp\_lt\_%';

CREATE TEMPORARY TABLE tmp_keep_user (id BIGINT PRIMARY KEY)
SELECT u.id FROM im_user u
LEFT JOIN tmp_bench_user b ON b.id = u.id
WHERE b.id IS NULL AND u.user_type <> 2;

CREATE TEMPORARY TABLE tmp_bench_conv (id BIGINT PRIMARY KEY)
SELECT m.conversation_id AS id
FROM im_conversation_member m
LEFT JOIN tmp_keep_user k ON k.id = m.user_id
GROUP BY m.conversation_id
HAVING COUNT(k.id) = 0
   AND SUM(m.user_id IN (SELECT id FROM tmp_bench_user)) > 0;

CREATE TEMPORARY TABLE tmp_bench_msg (id BIGINT PRIMARY KEY)
SELECT msg.id FROM im_message msg
JOIN tmp_bench_conv c ON c.id = msg.conversation_id;

SELECT
  (SELECT COUNT(*) FROM tmp_bench_user) AS bench_users,
  (SELECT COUNT(*) FROM tmp_bench_conv) AS bench_conversations,
  (SELECT COUNT(*) FROM tmp_bench_msg)  AS bench_messages;

START TRANSACTION;

DELETE r FROM im_message_reaction r JOIN tmp_bench_conv c ON c.id = r.conversation_id;
DELETE p FROM im_conversation_pin p JOIN tmp_bench_conv c ON c.id = p.conversation_id;
DELETE g FROM im_group_reminder g JOIN tmp_bench_conv c ON c.id = g.conversation_id;
DELETE a FROM im_agent_approval a JOIN tmp_bench_conv c ON c.id = a.conversation_id;
DELETE t FROM im_agent_task t JOIN tmp_bench_conv c ON c.id = t.conversation_id;
DELETE msg FROM im_message msg JOIN tmp_bench_msg x ON x.id = msg.id;
DELETE m FROM im_conversation_member m JOIN tmp_bench_conv c ON c.id = m.conversation_id;
DELETE cv FROM im_conversation cv JOIN tmp_bench_conv c ON c.id = cv.id;

DELETE r FROM im_message_reaction r JOIN tmp_bench_user b ON b.id = r.user_id;
DELETE m FROM im_conversation_member m JOIN tmp_bench_user b ON b.id = m.user_id;
DELETE f FROM im_friendship f JOIN tmp_bench_user b ON b.id = f.user_id;
DELETE f FROM im_friendship f JOIN tmp_bench_user b ON b.id = f.friend_id;
DELETE fr FROM im_friend_request fr JOIN tmp_bench_user b ON b.id = fr.from_user_id;
DELETE fr FROM im_friend_request fr JOIN tmp_bench_user b ON b.id = fr.to_user_id;
DELETE l FROM im_user_llm_config l JOIN tmp_bench_user b ON b.id = l.user_id;
DELETE u FROM im_user u JOIN tmp_bench_user b ON b.id = u.id;

COMMIT;

SELECT
  (SELECT COUNT(*) FROM im_user WHERE username LIKE 'bench\_u\_%' OR username LIKE 'tmp\_lt\_%') AS remaining_bench_users,
  (SELECT COUNT(*) FROM im_user) AS total_remaining_users;
