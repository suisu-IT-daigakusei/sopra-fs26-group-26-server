ALTER TABLE users
    ADD COLUMN rounds_played integer NOT NULL DEFAULT 0,
    ADD COLUMN total_round_points_accumulated integer NOT NULL DEFAULT 0;

UPDATE users u
SET rounds_played = stats.rounds_played,
    total_round_points_accumulated = stats.total_round_points,
    rounds_won = stats.rounds_won,
    average_score_per_round = round(
        stats.total_round_points::numeric / NULLIF(stats.rounds_played, 0)
    )::integer
FROM (
    WITH raw_scores AS (
        SELECT s.id AS session_id,
               round_scores.round_number,
               score.key AS user_key,
               score.value AS score_value
        FROM sessions s
        CROSS JOIN LATERAL jsonb_array_elements(
            CASE
                WHEN jsonb_typeof(s.user_scores_per_round) = 'array'
                    THEN s.user_scores_per_round
                ELSE '[]'::jsonb
            END
        ) WITH ORDINALITY AS round_scores(round_value, round_number)
        CROSS JOIN LATERAL jsonb_each(
            CASE
                WHEN jsonb_typeof(round_scores.round_value) = 'object'
                    THEN round_scores.round_value
                ELSE '{}'::jsonb
            END
        ) AS score
    ),
    normalized_scores AS (
        SELECT session_id,
               round_number,
               CASE
                   WHEN user_key ~ '^[0-9]+$' THEN
                       CASE
                           WHEN user_key::numeric BETWEEN 1 AND 9223372036854775807
                               THEN user_key::numeric::bigint
                       END
               END AS user_id,
               CASE
                   WHEN jsonb_typeof(score_value) = 'number' THEN
                       CASE
                           WHEN (score_value #>> '{}') ~ '^-?[0-9]+$' THEN
                               CASE
                                   WHEN (score_value #>> '{}')::numeric
                                           BETWEEN -2147483648 AND 2147483647
                                       THEN (score_value #>> '{}')::integer
                               END
                       END
               END AS score
        FROM raw_scores
    ),
    valid_scores AS (
        SELECT session_id, round_number, user_id, score
        FROM normalized_scores
        WHERE user_id IS NOT NULL
          AND score IS NOT NULL
    ),
    scored_rounds AS (
        SELECT user_id,
               score,
               min(score) OVER (
                   PARTITION BY session_id, round_number
               ) AS best_round_score
        FROM valid_scores
    )
    SELECT user_id,
           count(*)::integer AS rounds_played,
           sum(score)::integer AS total_round_points,
           count(*) FILTER (WHERE score = best_round_score)::integer AS rounds_won
    FROM scored_rounds
    GROUP BY user_id
) AS stats
WHERE u.id = stats.user_id;

ALTER TABLE users
    ADD CONSTRAINT ck_users_round_stats_nonnegative CHECK (
        rounds_played >= 0 AND total_round_points_accumulated >= 0
    );
