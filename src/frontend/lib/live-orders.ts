export type PaymentChoice = "NGUOI_DAT" | "NGUOI_NHAN";
export type PaymentMethod = "TIEN_MAT" | "VNPAY_QR";

export type Parcel = { loaiHangHoa: string; ghiChuBaoQuan?: string; khoiLuongKg: string };
export type Fare = {
  cuocGoc: string; cuocKm: string; cuocKhoiLuong: string; tongKhoiLuongKg: string;
  tienPhuThu: string; tienGiamGia: string; tongCuoc: string;
  maBieuPhi: string; phienBanBieuPhi: number;
  phuThu: { maPhuThu: string; tenPhuThu: string; soTienTinh: string }[];
};
export type Quote = {
  maBaoGia: string; hetHanLuc: string;
  loTrinh: { diemLayHang: string; diemGiaoHang: string; quangDuongKm: string; thoiGianDuKienGiay: number; nguon: string };
  cuoc: Fare;
  hangThanhVien: { tenHang: string; phanTramGiamGia: string; conHieuLuc: boolean; ngayHetHan: string | null } | null;
};
export type Order = {
  maDon: string; thoiGianTao: string; diemLayHang: string; diemGiaoHang: string;
  tenNguoiNhan: string | null; sdtNguoiNhan: string; quangDuongKm: string;
  trangThai: string; maKh: string; maTkTao: string; benThanhToan: PaymentChoice;
  maTx: string | null;
  nguonLoTrinh: "FAKE" | "GOONG";
  phuongThucThanhToan: PaymentMethod; cuoc: Fare;
  thanhToan: { trangThai: string; soTienConPhaiThu: string };
  kienHang: Parcel[];
};
export type OrderDetail = Order & {
  nhatKy: { thuTu: number; loaiSuKien: string; trangThaiSau: string; thoiGian: string; nguoiThucHien: string | null; ghiChu: string | null }[];
  phanCong: { maPhanCong: string; maTx: string; batDauLuc: string; ketThucLuc: string | null; ketQuaKetThuc: string | null; lyDo: string | null }[];
};
export type Driver = { maTx: string; hoTen: string; soDienThoai: string; trangThaiHoatDong: string; dangBanChuyen: boolean;
  phuongTien: { bienSoXe: string; loaiXe: string } | null;
  diaChiViTriDemo: string | null; viDoDemo: string | null; kinhDoDemo: string | null;
  nguonViTri: "DEMO_CO_DINH" | null; viTriCapNhatLuc: string | null;
  quangDuongDenDiemLayKm: string | null; thoiGianDenDiemLayGiay: number | null; nguonLoTrinh: string | null };
export type DriverPage = { items: Driver[]; page: number; size: number; totalElements: number; totalPages: number;
  cachSapXep: "TEN_TAI_XE" | "GOONG_DEN_DIEM_LAY" | "THOI_GIAN_RANH_DU_PHONG" | "KHONG_CO_TAI_XE"; lyDoDuPhong: string | null };
export const fallbackReasons: Record<string,string> = {
  GOONG_NOT_CONFIGURED: "Chưa cấu hình Goong REST key ở backend.",
  DRIVER_LOCATION_MISSING: "Có tài xế chưa được xác minh và lưu vị trí mô phỏng.",
  ROUTE_PROVIDER_ERROR: "Goong đang lỗi hoặc không cho phép truy cập/hết hạn mức.",
  ROUTE_PROVIDER_TIMEOUT: "Goong phản hồi quá thời gian chờ.",
  ADDRESS_NOT_FOUND: "Goong không tìm thấy vị trí hoặc điểm lấy hàng.",
  DRIVER_SNAPSHOT_CHANGED: "Điều kiện hoặc vị trí tài xế đã đổi trong lúc tính tuyến.",
  ROUTING_CANDIDATE_LIMIT: "Số ứng viên vượt giới hạn tính tuyến của một lần gợi ý.",
};

const messages: Record<string, string> = {
  ADDRESS_NOT_FOUND: "Không tìm thấy địa chỉ. Hãy nhập địa chỉ đầy đủ và thử lại.",
  OUT_OF_SERVICE_AREA: "Địa chỉ hoặc quãng đường nằm ngoài vùng phục vụ.",
  DISTANCE_INVALID: "Không tính được tuyến đường giữa hai địa chỉ này.",
  ROUTE_PROVIDER_ERROR: "Dịch vụ bản đồ đang lỗi. Vui lòng thử lại sau.",
  ROUTE_PROVIDER_TIMEOUT: "Dịch vụ bản đồ quá thời gian chờ. Vui lòng thử lại.",
  WEIGHT_LIMIT_EXCEEDED: "Tổng khối lượng vượt giới hạn biểu phí.",
  QUOTE_EXPIRED: "Báo giá đã hết hạn. Vui lòng báo giá lại.",
  QUOTE_CHANGED: "Báo giá đã thay đổi. Vui lòng báo giá lại.",
  FARE_CONFIG_MISSING: "Chưa có biểu phí phù hợp. Vui lòng liên hệ tổng đài.",
  ORDER_STATE_CONFLICT: "Trạng thái đơn đã đổi. Vui lòng tải lại đơn.",
  DRIVER_UNAVAILABLE: "Tài xế không còn khả dụng. Hãy chọn tài xế khác.",
  CANCELLATION_WINDOW_EXPIRED: "Đã hết thời hạn hủy đơn.",
  CSRF_INVALID: "Phiên xác thực đã đổi. Vui lòng tải lại trang.",
  FORBIDDEN: "Tài khoản không có quyền thực hiện thao tác này.",
  VALIDATION_ERROR: "Thông tin chưa hợp lệ. Hãy kiểm tra địa chỉ, SĐT và khối lượng.",
  GOONG_NOT_CONFIGURED: "Chưa cấu hình Goong REST key ở backend. Chưa thể xác minh và lưu vị trí tài xế.",
  AUTH_REQUIRED: "Bạn cần đăng nhập để thực hiện thao tác.",
  SESSION_EXPIRED: "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.",
  NETWORK_ERROR: "Không nhận được phản hồi. Hãy làm mới để kiểm tra dữ liệu trước khi thao tác lại.",
};

export class ApiError extends Error {
  constructor(public code: string, public status: number) {
    super(messages[code] ?? `Yêu cầu không thành công (${code || status}).`);
  }
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  try {
  const signal = options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(30000)]) : AbortSignal.timeout(30000);
  const headers = new Headers(options.headers);
  if (options.body) headers.set("Content-Type", "application/json");
  if (options.method && options.method !== "GET") {
    const csrf = await fetch("/api/auth/csrf", { credentials: "include", cache: "no-store", signal });
    if (!csrf.ok) throw new ApiError("CSRF_INVALID", csrf.status);
    headers.set("X-CSRF-Token", (await csrf.json()).csrfToken);
  }
  const response = await fetch(`/api${path}`, { ...options, signal, headers, credentials: "include", cache: "no-store" });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    if (["AUTH_REQUIRED","SESSION_EXPIRED","FORBIDDEN"].includes(body.code)) window.dispatchEvent(new Event("api-session-check"));
    throw new ApiError(body.code ?? "INTERNAL_ERROR", response.status);
  }
  return response.status === 204 ? undefined as T : (await response.json()) as T;
  } catch (cause) {
    if (cause instanceof ApiError || options.signal?.aborted) throw cause;
    throw new ApiError("NETWORK_ERROR",0);
  }
}

export const vnd = (amount: string) => `${Number(amount).toLocaleString("vi-VN")} ₫`;
