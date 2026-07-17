-- PostgreSQL's built-in, normalized query statistics. The production compose
-- file preloads the module; this extension exposes its queryable view.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
