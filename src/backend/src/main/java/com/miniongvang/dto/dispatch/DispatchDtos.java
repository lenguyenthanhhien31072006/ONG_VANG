package com.miniongvang.dto.dispatch;
import com.miniongvang.dto.order.OrderDtos.Order;

import java.util.List;
import com.miniongvang.entity.enums.*;

/** JSON contract types for dispatch endpoints. */
public final class DispatchDtos {
    private DispatchDtos() {}

    public record TransitionRequest(OrderStatus trangThai) {}
    public record IncidentRequest(IncidentType loaiSuCo, String lyDo) {}
    public record AssignmentRequest(String maTx) {}
    public record ReasonRequest(String lyDo) {}

    public record Event(String maSuKien, String maDon, int thuTu, OrderEventType loaiSuKien,
                            OrderStatus trangThaiTruoc, OrderStatus trangThaiSau, String thoiGian,
                            String maTkThucHien, String nguoiThucHien, String ghiChu, IncidentType loaiSuCo) {}

    public record EventPage(List<Event> items,int page,int size,long totalElements,long totalPages) {}

    public record Vehicle(String maPhuongTien,String bienSoXe,String loaiXe,String mauXe) {}

    public record Driver(String maTx,String hoTen,String soDienThoai,String trangThaiHoatDong,boolean dangBanChuyen,Vehicle phuongTien,
                         String diaChiViTriDemo,String viDoDemo,String kinhDoDemo,String nguonViTri,String viTriCapNhatLuc,
                         String quangDuongDenDiemLayKm,Long thoiGianDenDiemLayGiay,String nguonLoTrinh) {}

    public record DriverPage(List<Driver> items,int page,int size,long totalElements,long totalPages,
                             String cachSapXep,String lyDoDuPhong) {}

    public record Summary(long tongSoDon,String tongCuoc) {}

    public record OrderPage(List<Order> items,int page,int size,long totalElements,long totalPages,Summary summary) {}
}
