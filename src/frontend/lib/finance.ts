import { api } from "@/lib/live-orders";

export type RevenueMetrics = {
  giaTriDonHoanTat: string; tienThucThu: string; tienThuChuaPhanBo: string;
  cuocConPhaiThuCuoiKy: string; soDonHoanTat: number; soDonHuy: number;
  phanCongTy: string; phanTaiXe: string;
};
export type RevenueReport = {
  tuNgay: string; denNgay: string; muiGio: string;
  tong: RevenueMetrics; items: { batDau: string; ketThuc: string; chiTieu: RevenueMetrics }[];
};
export type DriverEarnings = {
  maTx: string; hoTen: string; tongCuocDaHoanTat: string; phanTaiXePhatSinh: string;
};

export function reportRange(period: "TODAY" | "WEEK" | "MONTH") {
  const now = new Date();
  const local = new Date(now.toLocaleString("en-US", { timeZone: "Asia/Ho_Chi_Minh" }));
  const start = new Date(local.getFullYear(), local.getMonth(), local.getDate());
  if (period === "WEEK") start.setDate(start.getDate() - 6);
  if (period === "MONTH") start.setDate(1);
  const end = new Date(local.getFullYear(), local.getMonth(), local.getDate() + 1);
  const iso = (value: Date) => `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, "0")}-${String(value.getDate()).padStart(2, "0")}`;
  return { from: iso(start), to: iso(end) };
}
export function revenue(period: "TODAY" | "WEEK" | "MONTH") {
  const { from, to } = reportRange(period);
  return api<RevenueReport>(`/reports/revenue?tuNgay=${from}&denNgay=${to}&nhomTheo=NGAY`);
}
