package com.miniongvang.entity;

import com.miniongvang.entity.enums.DriverStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;

@Entity
@Table(name = "tai_xe")
public class TaiXe {
    @Id @Column(name = "ma_tx", nullable = false, length = 36)
    private String id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ma_tk", nullable = false, unique = true)
    private TaiKhoan taiKhoan;
    @Column(name = "cccd", nullable = false, length = 12)
    private String cccd;
    @Column(name = "so_giay_phep", nullable = false, length = 50)
    private String soGiayPhep;
    @Column(name = "ngay_het_han_gplx", nullable = false)
    private LocalDate ngayHetHanGplx;
    @Enumerated(EnumType.STRING) @Column(name = "trang_thai", nullable = false, length = 40)
    private DriverStatus trangThai = DriverStatus.OFFLINE;
    @Column(name = "ranh_tu")
    private Instant ranhTu;
    @Column(name = "dia_chi_vi_tri_demo", length = 255) private String diaChiViTriDemo;
    @Column(name = "vi_do_demo", precision = 10, scale = 7) private BigDecimal viDoDemo;
    @Column(name = "kinh_do_demo", precision = 10, scale = 7) private BigDecimal kinhDoDemo;
    @Column(name = "nguon_vi_tri", length = 20) private String nguonViTri;
    @Column(name = "vi_tri_cap_nhat_luc") private Instant viTriCapNhatLuc;

    @Transient private String legacyViTriHienTai;

    public TaiXe() {}
    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public TaiKhoan getTaiKhoan() { return taiKhoan; }
    public void setTaiKhoan(TaiKhoan value) { taiKhoan = value; }
    public String getCccd() { return cccd; }
    public void setCccd(String value) { cccd = value; }
    public String getSoGiayPhep() { return soGiayPhep; }
    public void setSoGiayPhep(String value) { soGiayPhep = value; }
    public LocalDate getNgayHetHanGplx() { return ngayHetHanGplx; }
    public void setNgayHetHanGplx(LocalDate value) { ngayHetHanGplx = value; }
    public DriverStatus getTrangThai() { return trangThai; }
    public void setTrangThai(DriverStatus value) { trangThai = value; }
    public Instant getRanhTu() { return ranhTu; }
    public void setRanhTu(Instant value) { ranhTu = value; }
    public String getDiaChiViTriDemo() { return diaChiViTriDemo; }
    public void setDiaChiViTriDemo(String value) { diaChiViTriDemo = value; }
    public BigDecimal getViDoDemo() { return viDoDemo; }
    public void setViDoDemo(BigDecimal value) { viDoDemo = value; }
    public BigDecimal getKinhDoDemo() { return kinhDoDemo; }
    public void setKinhDoDemo(BigDecimal value) { kinhDoDemo = value; }
    public String getNguonViTri() { return nguonViTri; }
    public void setNguonViTri(String value) { nguonViTri = value; }
    public Instant getViTriCapNhatLuc() { return viTriCapNhatLuc; }
    public void setViTriCapNhatLuc(Instant value) { viTriCapNhatLuc = value; }

    @Deprecated public String getHoTen() { return taiKhoan == null ? null : taiKhoan.getHoTen(); }
    @Deprecated public void setHoTen(String value) { if (taiKhoan != null) taiKhoan.setHoTen(value); }
    @Deprecated public String getSoDienThoai() { return taiKhoan == null ? null : taiKhoan.getSoDienThoai(); }
    @Deprecated public void setSoDienThoai(String value) { if (taiKhoan != null) taiKhoan.setSoDienThoai(value); }
    @Deprecated public String getViTriHienTai() { return legacyViTriHienTai; }
    @Deprecated public void setViTriHienTai(String value) { legacyViTriHienTai = value; }
}
