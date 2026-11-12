-- Removes the tables of the retired End of Day, Commission, Devices and Performance features.
--
-- The app no longer reads or writes any of these (they were removed in the same release), and nothing the
-- schedule, days off, stores, employees or time clock use points at them: every link goes from these tables to
-- employees / stores, never the other way. So the schedule keeps working before, during and after this runs.
--
-- It is NOT run automatically. Run it once, by hand, after the new version is deployed:
--   1. Take a backup first (Railway: Postgres service -> Backups, or `pg_dump`). Dropped data can't be brought back.
--   2. psql "$DATABASE_URL" -f 2026-11-12-remove-eod-commission-devices.sql
-- Safe to run twice (IF EXISTS everywhere). It all happens in one transaction: if anything fails, nothing changes.

BEGIN;

-- End of Day (child tables first)
DROP TABLE IF EXISTS eod_line_shares;
DROP TABLE IF EXISTS eod_accessory_shares;
DROP TABLE IF EXISTS eod_lines;
DROP TABLE IF EXISTS eod_reports;

-- Commission
DROP TABLE IF EXISTS commission_payouts;
DROP TABLE IF EXISTS commission_adjustments;
DROP TABLE IF EXISTS commission_periods;
DROP TABLE IF EXISTS commission_plan;

-- Devices
DROP TABLE IF EXISTS devices;
DROP TABLE IF EXISTS brands;

-- Sales tax per store was only used by End of Day and Commission.
ALTER TABLE stores DROP COLUMN IF EXISTS tax_rate;

COMMIT;
