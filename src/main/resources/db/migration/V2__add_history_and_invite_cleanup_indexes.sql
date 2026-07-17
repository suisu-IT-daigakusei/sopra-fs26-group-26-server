CREATE INDEX idx_sessions_total_score_by_user_id_gin
    ON sessions USING gin (total_score_by_user_id);

CREATE INDEX idx_cabo_invites_status_created_at
    ON cabo_invites (status, created_at);
