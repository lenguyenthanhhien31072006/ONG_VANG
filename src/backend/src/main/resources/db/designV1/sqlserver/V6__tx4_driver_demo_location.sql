ALTER TABLE dbo.tai_xe ADD
    dia_chi_vi_tri_demo NVARCHAR(255) NULL,
    vi_do_demo DECIMAL(10,7) NULL,
    kinh_do_demo DECIMAL(10,7) NULL,
    nguon_vi_tri VARCHAR(20) NULL,
    vi_tri_cap_nhat_luc DATETIME2(3) NULL;

ALTER TABLE dbo.tai_xe ADD CONSTRAINT ck_tai_xe_demo_location CHECK (
    (dia_chi_vi_tri_demo IS NULL AND vi_do_demo IS NULL AND kinh_do_demo IS NULL
        AND nguon_vi_tri IS NULL AND vi_tri_cap_nhat_luc IS NULL)
    OR (dia_chi_vi_tri_demo IS NOT NULL AND LEN(LTRIM(RTRIM(dia_chi_vi_tri_demo))) > 0
        AND vi_do_demo IS NOT NULL AND vi_do_demo BETWEEN -90 AND 90
        AND kinh_do_demo IS NOT NULL AND kinh_do_demo BETWEEN -180 AND 180
        AND nguon_vi_tri IS NOT NULL AND nguon_vi_tri = 'DEMO_CO_DINH'
        AND vi_tri_cap_nhat_luc IS NOT NULL)
);
-- Existing drivers stay unconfigured. Coordinates must come from a successful Goong lookup.
