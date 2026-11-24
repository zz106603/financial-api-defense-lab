-- Apply explicitly after Flyway migrations, including on an existing local volume.
-- psql reads the secret from its environment; do not enable SQL echo/log_statement.
\set ON_ERROR_STOP on
\getenv grafana_password GRAFANA_DB_PASSWORD
SELECT length(:'grafana_password') >= 24 AS password_ok \gset
\if :password_ok
\else
    DO $$ BEGIN RAISE EXCEPTION 'GRAFANA_DB_PASSWORD must contain at least 24 characters'; END $$;
\endif

BEGIN;
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='grafana_reader'
               AND shobj_description(oid, 'pg_authid') IS DISTINCT FROM 'Financial API Defense Lab monitoring') THEN
        RAISE EXCEPTION 'Refusing to change an unrelated grafana_reader role';
    END IF;
    IF EXISTS (SELECT FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='grafana_reader')) THEN
        RAISE EXCEPTION 'Monitoring role must not belong to other roles';
    END IF;
END $$;
SELECT 'CREATE ROLE grafana_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='grafana_reader') \gexec
COMMENT ON ROLE grafana_reader IS 'Financial API Defense Lab monitoring';
SELECT format('ALTER ROLE grafana_reader WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3 PASSWORD %L',
              :'grafana_password') \gexec
ALTER ROLE grafana_reader SET default_transaction_read_only=on;
ALTER ROLE grafana_reader SET statement_timeout='5s';
ALTER ROLE grafana_reader SET lock_timeout='1s';
ALTER ROLE grafana_reader SET idle_in_transaction_session_timeout='5s';
REVOKE ALL ON SCHEMA public FROM grafana_reader;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM grafana_reader;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM grafana_reader;
SELECT format('REVOKE ALL ON DATABASE %I FROM grafana_reader', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO grafana_reader', current_database()) \gexec
GRANT USAGE ON SCHEMA public TO grafana_reader;
GRANT SELECT ON public.audit_event, public.security_alert, public.detection_state TO grafana_reader;
-- PUBLIC grants cannot be denied per user. Fail closed rather than change other users' privileges.
DO $$
BEGIN
    IF has_schema_privilege('grafana_reader', 'public', 'CREATE')
       OR has_database_privilege('grafana_reader', current_database(), 'CREATE')
       OR EXISTS (SELECT FROM pg_tables WHERE schemaname='public'
           AND tablename NOT IN ('audit_event','security_alert','detection_state')
           AND has_table_privilege('grafana_reader', format('%I.%I',schemaname,tablename),
               'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))
       OR EXISTS (SELECT FROM pg_tables WHERE schemaname='public'
           AND tablename IN ('audit_event','security_alert','detection_state')
           AND has_table_privilege('grafana_reader', format('%I.%I',schemaname,tablename),
               'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')) THEN
        RAISE EXCEPTION 'Monitoring role has excess privileges (including PUBLIC); review grants first';
    END IF;
END $$;
COMMIT;
