"use client";

import { useCallback, useEffect, useState } from "react";
import { AdminEmpty, AdminHeading, AdminStat } from "@/components/admin/AdminUI";
import { api } from "@/lib/live-orders";
import type { DriverEarnings, RevenueReport } from "@/lib/finance";
import { reportRange } from "@/lib/finance";
import { formatVND } from "@/lib/utils";

type Group = "NGAY" | "TUAN" | "THANG";
const money = (value?: string) => formatVND(Number(value ?? 0));

export default function AdminReportPage() {
  const initial = reportRange("MONTH");
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [group, setGroup] = useState<Group>("NGAY");
  const [report, setReport] = useState<RevenueReport | null>(null);
  const [drivers, setDrivers] = useState<DriverEarnings[]>([]);
  const [error, setError] = useState("");
  const invalid = !from || !to || from >= to;
  const query = `tuNgay=${from}&denNgay=${to}&nhomTheo=${group}`;
  const refresh = useCallback(async () => {
    if (invalid) return;
    try {
      const [revenue, earnings] = await Promise.all([
        api<RevenueReport>(`/reports/revenue?${query}`),
        api<DriverEarnings[]>(`/reports/drivers?tuNgay=${from}&denNgay=${to}`),
      ]);
      setReport(revenue); setDrivers(earnings); setError("");
    } catch (cause) { setError((cause as Error).message); }
  }, [query, from, to, invalid]);
  useEffect(() => { const timer = setTimeout(() => { void refresh(); }, 0); return () => clearTimeout(timer); }, [refresh]);
  const totals = report?.tong;
  const rows = report?.items ?? [];
  const maxValue = Math.max(1, ...rows.map((row) => Number(row.chiTieu.giaTriDonHoanTat)));

  return <>
    <AdminHeading eyebrow="Tài chính đội xe" title="Báo cáo" description="Cước và phần chia 20/80 từ cơ sở dữ liệu."
      actions={<a className="admin-button primary" href={`/api/reports/revenue/export?${query}`}>Tải báo cáo Excel</a>} />
    {error && <p role="alert" className="admin-modal-error">{error}</p>}
    <section className="admin-panel admin-filter-panel"><div className="admin-filter-grid admin-report-filters">
      <div className="admin-field"><label htmlFor="report-from">Từ ngày</label><input id="report-from" type="date" value={from} onChange={(event) => setFrom(event.target.value)} /></div>
      <div className="admin-field"><label htmlFor="report-to">Đến trước ngày</label><input id="report-to" type="date" value={to} onChange={(event) => setTo(event.target.value)} /></div>
      <div className="admin-field"><label htmlFor="report-group">Nhóm theo</label><select id="report-group" value={group} onChange={(event) => setGroup(event.target.value as Group)}><option value="NGAY">Ngày</option><option value="TUAN">Tuần</option><option value="THANG">Tháng</option></select></div>
      <button className="admin-filter-reset" onClick={() => { const range = reportRange("MONTH"); setFrom(range.from); setTo(range.to); setGroup("NGAY"); }}>Xóa lọc</button>
    </div>{invalid && <p role="alert" className="admin-modal-error">Ngày kết thúc phải sau ngày bắt đầu.</p>}</section>
    <div className="admin-stat-grid">
      <AdminStat label="Giá trị đơn hoàn tất" value={money(totals?.giaTriDonHoanTat)} note={`${totals?.soDonHoanTat ?? 0} đơn hoàn tất`} tone="accent" />
      <AdminStat label="Cước khách đã trả" value={money(totals?.tienThucThu)} note="Theo lúc thu cước" />
      <AdminStat label="Cước còn phải thu" value={money(totals?.cuocConPhaiThuCuoiKy)} note="Số dư cuối mốc" tone="alert" />
    </div>
    <div className="admin-stat-grid">
      <AdminStat label="Công ty 20%" value={money(totals?.phanCongTy)} note="Theo lúc ghi nhận phần chia" />
      <AdminStat label="Tài xế 80%" value={money(totals?.phanTaiXe)} note="Thu nhập phát sinh" />
      <AdminStat label="Đơn hủy" value={totals?.soDonHuy ?? 0} note="Trong kỳ đã chọn" />
    </div>
    <div className="admin-main-grid"><section className="admin-panel"><div className="admin-section-head"><div><h2>Giá trị hoàn tất theo kỳ</h2><p>Phân nhóm theo thời điểm hoàn tất</p></div></div>
      <div className="admin-chart">{rows.length && !invalid ? rows.map((row) => <div className="admin-chart-row admin-report-bar" key={row.batDau}><span>{row.batDau.slice(0,10)}</span><div className="admin-chart-track" role="img" aria-label={`${row.batDau}: ${money(row.chiTieu.giaTriDonHoanTat)}`}><div className="admin-chart-fill" style={{ width: `${Number(row.chiTieu.giaTriDonHoanTat) / maxValue * 100}%` }} /></div><strong>{money(row.chiTieu.giaTriDonHoanTat)}</strong></div>) : <AdminEmpty title="Không có số liệu" detail="Chọn khoảng ngày khác để xem báo cáo." />}</div>
    </section><section className="admin-panel"><div className="admin-section-head"><h2>Cách đọc số liệu</h2></div><div className="admin-detail-body admin-report-explain">
      <p><strong>Cước khách đã trả</strong> tính theo lúc thu tiền; <strong>giá trị hoàn tất</strong> tính theo lúc giao xong.</p>
      <p><strong>20/80</strong> phát sinh khi đã giao xong và thu đủ.</p>
    </div></section></div>
    <section className="admin-panel"><div className="admin-section-head"><h2>Bảng số liệu</h2></div>{rows.length && !invalid ? <div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>Bắt đầu</th><th>Hoàn tất</th><th className="money">Tổng cước</th><th className="money">Đã thu</th><th className="money">Công ty 20%</th><th className="money">Tài xế 80%</th></tr></thead><tbody>{rows.map((row) => <tr key={row.batDau}><td>{row.batDau.slice(0,10)}</td><td>{row.chiTieu.soDonHoanTat}</td><td className="money">{money(row.chiTieu.giaTriDonHoanTat)}</td><td className="money">{money(row.chiTieu.tienThucThu)}</td><td className="money">{money(row.chiTieu.phanCongTy)}</td><td className="money">{money(row.chiTieu.phanTaiXe)}</td></tr>)}</tbody></table></div> : <AdminEmpty title="Không có dữ liệu báo cáo" detail="Hãy thay đổi khoảng ngày." />}</section>
    <section className="admin-panel"><div className="admin-section-head"><h2>Thu nhập theo tài xế</h2></div><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>Tài xế</th><th className="money">Cước hoàn tất</th><th className="money">80% phát sinh</th></tr></thead><tbody>{drivers.map((driver) => <tr key={driver.maTx}><td>{driver.hoTen}</td><td className="money">{money(driver.tongCuocDaHoanTat)}</td><td className="money">{money(driver.phanTaiXePhatSinh)}</td></tr>)}</tbody></table></div></section>
  </>;
}
