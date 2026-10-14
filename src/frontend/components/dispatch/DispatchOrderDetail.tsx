"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { DispatcherNavbar } from "@/components/layout/DispatcherNavbar";
import { Navbar } from "@/components/layout/Navbar";
import { LogoutButton } from "@/components/auth/LogoutButton";
import { api, ApiError, fallbackReasons, type DriverPage, type OrderDetail, vnd } from "@/lib/live-orders";

export function DispatchOrderDetail({ id, dispatcher = false, driver = false }: { id: string; dispatcher?: boolean; driver?: boolean }) {
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [error, setError] = useState("");
  const [drivers, setDrivers] = useState<DriverPage | null>(null);
  const [selectedDriver, setSelectedDriver] = useState("");
  const [busy, setBusy] = useState(false);
  const [finding, setFinding] = useState(false);
  const [cancelReason, setCancelReason] = useState("");
  const [now, setNow] = useState(() => Date.now());
  const lock = useRef(false);
  const findLock = useRef(false);
  const refresh = useCallback(async () => {
    const current = await api<OrderDetail>(`/orders/${encodeURIComponent(id)}`);
    setOrder(current); return current;
  },[id]);
  useEffect(() => {
    const controller = new AbortController();
    api<OrderDetail>(`/orders/${encodeURIComponent(id)}`,{ signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setOrder(value); })
      .catch(cause => { if (!controller.signal.aborted) setError((cause as Error).message); });
    const timer = setInterval(() => setNow(Date.now()),1000);
    return () => { controller.abort(); clearInterval(timer); };
  },[id]);

  async function loadDrivers(clearError = true) {
    if (findLock.current) return;
    findLock.current = true; setFinding(true); setSelectedDriver(""); setDrivers(null);
    if (clearError) setError("");
    try { setDrivers(await api<DriverPage>(`/orders/${encodeURIComponent(id)}/driver-suggestions?size=100`)); }
    catch (cause) { setError((cause as Error).message); }
    finally { findLock.current = false; setFinding(false); }
  }
  async function assign() {
    if (lock.current || finding || order?.trangThai !== "CHO_GAN" || !drivers?.items.some(d => d.maTx === selectedDriver)) return;
    lock.current = true; setBusy(true); setError("");
    try {
      await api(`/orders/${encodeURIComponent(id)}/assignments`,{ method: "POST", body: JSON.stringify({ maTx: selectedDriver }) });
      setDrivers(null); setSelectedDriver(""); await refresh();
    } catch (cause) {
      const message = (cause as Error).message;
      setSelectedDriver(""); setDrivers(null);
      try {
        const current = await refresh();
        if (current.trangThai === "CHO_GAN" && cause instanceof ApiError && cause.code === "DRIVER_UNAVAILABLE") await loadDrivers(false);
      } catch { /* Keep the write failure visible; never retry an assignment automatically. */ }
      setError(message);
    } finally { lock.current = false; setBusy(false); }
  }
  const age = order ? now - Date.parse(order.thoiGianTao) : -1;
  const canCancel = !!order && ["CHO_GAN","DA_GAN"].includes(order.trangThai) && age >= 0 && age < 300000;
  async function cancel() {
    if (lock.current || !canCancel || !cancelReason.trim()) return;
    lock.current = true; setBusy(true); setError("");
    try {
      await api(`/orders/${encodeURIComponent(id)}/cancel`,{ method: "POST", body: JSON.stringify({ lyDo: cancelReason.trim() }) });
      setCancelReason(""); setDrivers(null); setSelectedDriver(""); await refresh();
    } catch (cause) {
      try { await refresh(); } catch { /* Keep the command error visible. */ }
      setError((cause as Error).message);
    } finally { lock.current = false; setBusy(false); }
  }
  const currentDriver = order?.phanCong?.find(item => item.ketThucLuc === null)?.maTx ?? order?.maTx;
  return <div className="min-h-screen bg-stone-50 text-stone-900">
    {dispatcher ? <DispatcherNavbar /> : driver ? <header className="flex flex-wrap justify-between gap-3 border-b bg-white p-4"><Link href="/driver/assigned" className="font-extrabold">Mini Ong Vàng · Tài xế</Link><LogoutButton className="rounded-xl border px-4 py-2 text-sm font-bold" /></header> : <Navbar />}
    <main className="mx-auto max-w-4xl space-y-5 px-4 py-8">
      <div className="flex items-center justify-between gap-3"><Link href={dispatcher ? "/dispatcher/live" : driver ? "/driver/assigned" : "/orders"} className="text-sm font-bold text-amber-800">← Danh sách đơn</Link><button type="button" disabled={busy || finding} onClick={() => { setDrivers(null); setSelectedDriver(""); void refresh().then(() => setError("")).catch(cause => setError((cause as Error).message)); }} className="rounded-lg border px-3 py-2 text-sm font-semibold disabled:opacity-50">Làm mới đơn</button></div>
      {error && <p role="alert" className="rounded-xl bg-red-50 p-4 text-red-800">{error}</p>}
      {!order && !error && <p role="status">Đang tải đơn...</p>}
      {order && <>
        <section className="space-y-3 rounded-2xl border bg-white p-5">
          <div className="flex flex-wrap justify-between gap-2"><h1 className="break-all font-mono text-xl font-bold">{order.maDon}</h1><span className="rounded-lg bg-amber-100 px-3 py-1 text-sm font-bold">{order.trangThai}</span></div>
          <p className="text-sm text-stone-600">Tạo lúc {new Date(order.thoiGianTao).toLocaleString("vi-VN")} · Tuyến giao {order.nguonLoTrinh === "FAKE" ? "DEMO · fixture" : "GOONG"}</p>
          <p><strong>Lấy:</strong> {order.diemLayHang}</p><p><strong>Giao:</strong> {order.diemGiaoHang}</p>
          <p><strong>Người nhận:</strong> {order.tenNguoiNhan ?? "—"} · {order.sdtNguoiNhan}</p>
          <p><strong>Kiện:</strong> {order.kienHang.map(item => `${item.loaiHangHoa} (${item.khoiLuongKg} kg)`).join(", ")}</p>
          <p><strong>Trả cước:</strong> {order.benThanhToan === "NGUOI_DAT" ? "Người đặt" : "Người nhận"} · {order.phuongThucThanhToan === "TIEN_MAT" ? "Tiền mặt" : "VNPay QR"}</p>
          <p className="rounded-xl bg-stone-50 p-3 text-sm"><strong>Tài xế đang phụ trách:</strong> {currentDriver ?? "Chưa phân công"}</p>
        </section>
        {dispatcher && order.trangThai === "CHO_GAN" && <section className="space-y-4 rounded-2xl border border-amber-200 bg-white p-5">
          <div><h2 className="text-lg font-bold">Gợi ý và gán tài xế</h2><p className="mt-1 text-sm text-stone-600">Tính đường đi từ vị trí mô phỏng cố định tới điểm lấy hàng. Đây không phải GPS trực tiếp và không cộng vào cước giao của khách.</p></div>
          <button type="button" onClick={() => void loadDrivers()} disabled={busy || finding} className="rounded-xl border px-4 py-2 text-sm font-semibold disabled:opacity-50">{finding ? "Đang tính gợi ý..." : "Tìm tài xế khả dụng"}</button>
          {drivers?.cachSapXep === "GOONG_DEN_DIEM_LAY" && <p className="rounded-xl bg-emerald-50 p-3 text-sm text-emerald-800">Xếp gần nhất theo đường đi Goong đến điểm lấy hàng; cùng khoảng cách thì theo mã tài xế.</p>}
          {drivers?.cachSapXep === "THOI_GIAN_RANH_DU_PHONG" && <p role="status" className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900"><strong>Thứ tự dự phòng theo thời gian rảnh, không phải gần nhất theo Goong.</strong><br />{fallbackReasons[drivers.lyDoDuPhong ?? ""] ?? drivers.lyDoDuPhong}<Link href="/dispatcher/live/fleet" className="block mt-2 font-bold underline">Kiểm tra vị trí tài xế</Link></p>}
          {drivers && drivers.items.length === 0 && <p role="status">Không có tài xế đủ điều kiện. Làm mới sau khi tài xế sẵn sàng.</p>}
          {drivers && drivers.items.length > 0 && <fieldset disabled={busy || finding} className="space-y-2"><legend className="mb-2 text-sm font-bold">Chọn một tài xế để xác nhận</legend>{drivers.items.map((d,index) => <label key={d.maTx} className={`flex gap-3 rounded-xl border p-4 text-sm cursor-pointer ${selectedDriver === d.maTx ? "border-amber-500 bg-amber-50" : "border-stone-200"}`}><input type="radio" name="driver" aria-label={`Chọn tài xế ${d.maTx}`} checked={selectedDriver === d.maTx} onChange={() => setSelectedDriver(d.maTx)} /><span className="space-y-1"><strong className="block">#{index+1} {d.hoTen} · {d.phuongTien?.bienSoXe ?? d.maTx}</strong><span className="block text-stone-500">{d.diaChiViTriDemo ?? "Chưa có vị trí mô phỏng đã xác minh"}</span>{d.quangDuongDenDiemLayKm !== null && <span className="block font-bold text-emerald-800">{d.quangDuongDenDiemLayKm} km đến điểm lấy · {d.thoiGianDenDiemLayGiay} giây · {d.nguonLoTrinh}</span>}</span></label>)}</fieldset>}
          {drivers && drivers.items.length > 0 && <button type="button" onClick={assign} disabled={busy || finding || !selectedDriver} className="rounded-xl bg-amber-600 px-4 py-2 font-bold text-white disabled:opacity-50">{busy ? "Đang ghi..." : "Xác nhận gán"}</button>}
        </section>}
        {!driver && ["CHO_GAN","DA_GAN"].includes(order.trangThai) && <section className="space-y-3 rounded-2xl border bg-white p-5"><h2 className="text-lg font-bold">Hủy đơn</h2><p className="text-sm text-stone-600">Chỉ hủy trước 5 phút từ lúc tạo. {canCancel ? "Cần nhập lý do." : "Đơn đã hết thời hạn hủy."}</p><form className="flex flex-wrap items-end gap-2" onSubmit={event => { event.preventDefault(); void cancel(); }}><label className="flex-1 text-sm font-bold">Lý do hủy<input required disabled={busy || !canCancel} value={cancelReason} onChange={event => setCancelReason(event.target.value)} maxLength={500} className="mt-2 w-full rounded-xl border px-3 py-2 font-normal" /></label><button disabled={busy || !canCancel || !cancelReason.trim()} className="rounded-xl border border-rose-300 px-4 py-2 font-bold text-rose-800 disabled:opacity-50">Hủy đơn</button></form></section>}
        <section className="rounded-2xl border bg-white p-5"><h2 className="mb-3 text-lg font-bold">Cước đã chốt của khách</h2><dl className="grid grid-cols-2 gap-2 text-sm"><dt>Cước km</dt><dd className="text-right">{vnd(order.cuoc.cuocKm)}</dd><dt>Cước kg</dt><dd className="text-right">{vnd(order.cuoc.cuocKhoiLuong)}</dd><dt>Phụ thu</dt><dd className="text-right">{vnd(order.cuoc.tienPhuThu)}</dd><dt>Giảm giá</dt><dd className="text-right">{vnd(order.cuoc.tienGiamGia)}</dd><dt className="border-t pt-2 font-bold">Tổng</dt><dd className="border-t pt-2 text-right text-xl font-extrabold">{vnd(order.cuoc.tongCuoc)}</dd></dl><p className="mt-3 text-xs text-stone-600">Biểu phí {order.cuoc.maBieuPhi} v{order.cuoc.phienBanBieuPhi} · {order.thanhToan.trangThai}</p></section>
        <section className="rounded-2xl border bg-white p-5"><h2 className="mb-3 text-lg font-bold">Lịch sử phân công</h2>{order.phanCong?.length ? <ol className="space-y-3">{order.phanCong.map(item => <li key={item.maPhanCong} className="rounded-xl bg-stone-50 p-3 text-sm"><strong>{item.maTx}</strong> · {item.ketThucLuc ? item.ketQuaKetThuc : "Đang phụ trách"}<p className="text-stone-500">{new Date(item.batDauLuc).toLocaleString("vi-VN")}</p>{item.lyDo && <p>Lý do: {item.lyDo}</p>}</li>)}</ol> : <p className="text-sm text-stone-500">Chưa có phân công.</p>}</section>
        <section className="rounded-2xl border bg-white p-5"><h2 className="mb-3 text-lg font-bold">Lịch sử trạng thái</h2><ol className="space-y-3">{order.nhatKy.map(event => <li key={event.thuTu} className="border-l-2 border-amber-500 pl-3 text-sm"><strong>#{event.thuTu} {event.trangThaiSau ?? event.loaiSuKien}</strong><span className="block text-stone-500">{new Date(event.thoiGian).toLocaleString("vi-VN")}</span>{event.ghiChu && <p>{event.ghiChu}</p>}</li>)}</ol></section>
      </>}
    </main>
  </div>;
}
