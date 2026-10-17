-- Remove COD remittance only. Keep fare payments and phan_chia_cuoc (20/80).
-- Existing remittance records in these four tables are intentionally removed.
-- Keep V10 unchanged so existing Flyway checksums remain valid.
SET XACT_ABORT ON;
DROP TABLE IF EXISTS dbo.su_kien_nop_cod;
DROP TABLE IF EXISTS dbo.lan_nop_cod;
DROP TABLE IF EXISTS dbo.khoan_cod_trong_dot;
DROP TABLE IF EXISTS dbo.dot_nop_cod;
