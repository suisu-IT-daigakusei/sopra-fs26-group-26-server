ALTER TABLE games
    ADD COLUMN session_ended boolean NOT NULL DEFAULT false;

-- Preserve the match-end boundary for games that were between the reveal and
-- rematch phases while this migration was deployed.
UPDATE games AS g
SET session_ended = true
WHERE g.status IN ('CABO_REVEAL', 'ROUND_AWAITING_REMATCH')
  AND EXISTS (
    SELECT 1
    FROM lobbies AS l
    JOIN sessions AS s ON s.session_id = l.session_id
    WHERE l.status = 'PLAYING'
      AND s.is_ended = true
      AND l.player_set_key = COALESCE((
          SELECT string_agg(player_id, ',' ORDER BY player_id::bigint)
          FROM jsonb_array_elements_text(g.ordered_player_ids) AS ids(player_id)
          WHERE player_id ~ '^[0-9]+$'
      ), '')
);
