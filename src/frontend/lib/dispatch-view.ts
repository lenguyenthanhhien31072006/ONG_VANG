import type { Driver as ApiDriver, Order as ApiOrder } from "./live-orders";
import type { DriverItem, OrderItem, OrderStatus } from "./types";

export type DispatchDriver = DriverItem & {
  routeKm?: string | null;
  routeSeconds?: number | null;
  routeSource?: string | null;
  locationSource?: string | null;
};

export function driverForView(driver: ApiDriver): DispatchDriver {
  return {
    id: driver.maTx, name: driver.hoTen, phone: driver.soDienThoai,
    vehiclePlate: driver.phuongTien?.bienSoXe ?? "—",
    vehicleType: driver.phuongTien?.loaiXe ?? "—",
    currentDistrict: driver.viTriDemoDiaChi ?? "Chưa cấu hình vị trí demo",
    status: driver.trangThaiHoatDong !== "ONLINE" ? "OFFLINE" : driver.dangBanChuyen ? "ONLINE_BUSY" : "ONLINE_IDLE",
    idleSinceMinutes: 0, rating: 0, completedOrdersToday: 0, acceptanceRate: 0,
    routeKm: driver.kmDenDiemLay, routeSeconds: driver.giayDenDiemLay,
    routeSource: driver.nguonDinhTuyen, locationSource: driver.viTriDemoNguon,
  };
}

export function orderForView(order: ApiOrder, drivers: DispatchDriver[]): OrderItem {
  const driver = drivers.find(item => item.id === order.maTx);
  const created = Date.parse(order.thoiGianTao);
  return {
    id: order.maDon,
    sender: { name: order.maKh, phone: "", street: order.diemLayHang, district: "", city: "" },
    receiver: { name: order.tenNguoiNhan ?? "—", phone: order.sdtNguoiNhan,
      street: order.diemGiaoHang, district: "", city: "", notes: order.ghiChuGiaoHang ?? undefined },
    packageType: order.kienHang.map(item => item.loaiHangHoa).join(", "),
    packageWeightKg: Number(order.cuoc.tongKhoiLuongKg), distanceKm: Number(order.quangDuongKm),
    baseFee: Number(order.cuoc.cuocGoc), surchargeFee: Number(order.cuoc.tienPhuThu),
    vipDiscountFee: Number(order.cuoc.tienGiamGia), totalFee: Number(order.cuoc.tongCuoc),
    status: order.trangThai as OrderStatus, statusText: order.trangThai,
    createdAt: Number.isFinite(created) ? new Date(created).toLocaleString("vi-VN") : order.thoiGianTao,
    createdTimestamp: created,
    paid: order.thanhToan.trangThai === "DA_THANH_TOAN" || order.thanhToan.trangThai === "MIEN_CUOC",
    paymentStatus: order.thanhToan.trangThai,
    paymentMethod: order.phuongThucThanhToan === "TIEN_MAT" ? "TIEN_MAT" : "VNPAY",
    driver: driver ? { id: driver.id, name: driver.name, phone: driver.phone,
      vehiclePlate: driver.vehiclePlate, vehicleType: driver.vehicleType } : undefined,
  };
}
