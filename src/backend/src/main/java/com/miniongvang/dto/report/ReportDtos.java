package com.miniongvang.dto.report;

import java.util.List;
import com.miniongvang.entity.enums.*;

/** JSON contract types for report endpoints. */
public final class ReportDtos {
    private ReportDtos() {}

    public record Metrics(String giaTriDonHoanTat,String tienThucThu,String tienThuChuaPhanBo,
                          String cuocConPhaiThuCuoiKy,long soDonHoanTat,long soDonHuy,
                          String phanCongTy,String phanTaiXe) {
        public Metrics(String completed,String received,String unallocated,String due,long count,long cancelled) {
            this(completed,received,unallocated,due,count,cancelled,"0.00","0.00");
        }
    }

    public record Bucket(String batDau,String ketThuc,Metrics chiTieu) {}

    public enum Group { NGAY, TUAN, THANG }

    public record Report(String tuNgay,String denNgay,String muiGio,Group nhomTheo,Metrics tong,List<Bucket> items) {}
}
