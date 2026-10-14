"use client";

import { useEffect, useRef, useState } from "react";
import { DispatcherNavbar } from "@/components/layout/DispatcherNavbar";
import { api, type DriverPage } from "@/lib/live-orders";

export default function DriverFleet() {
  const [result, setResult] = useState<DriverPage | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [status, setStatus] = useState("ALL");
  const [availability, setAvailability] = useState("ALL");
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<string | null>(null);
  const [address, setAddress] = useState("");
  const [saving, setSaving] = useState(false);
  const lock = useRef(false);
  useEffect(() => {
    const controller = new AbortController();
    const query = new URLSearchParams({ page: String(page), size: "20" });
    if (status !== "ALL") query.set("trangThaiHoatDong",status);
    if (availability !== "ALL") query.set("dangBanChuyen",availability);
    api<DriverPage>(`/drivers?${query}`,{ signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) { setResult(value); setError(""); } })
      .catch(cause => { if (!controller.signal.aborted) { setResult(null); setError((cause as Error).message); } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  },[page,status,availability,revision]);
  function refresh() { setLoading(true); setRevision(value => value + 1); }
  return <div className="min-h-screen bg-stone-50 text-stone-900">
    <DispatcherNavbar />
    <main className="mx-auto max-w-6xl px-4 py-8 space-y-5">
      <div className="flex flex-wrap justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-widest text-amber-700">TX4 · Đội xe</p><h1 className="mt-1 text-3xl font-extrabold">Vị trí mô phỏng và tài xế</h1></div><button type="button" disabled={loading || saving} onClick={refresh} className="rounded-xl border bg-white px-4 py-2 font-bold disabled:opacity-50">Làm mới</button></div>
      <div className="rounded-2xl border border-amber-200 bg-amber-50 p-5 text-sm"><strong>Vị trí cố định mô phỏng — không phải GPS trực tiếp.</strong><p className="mt-2">Địa chỉ được backend xác minh bằng Goong rồi lưu cùng tọa độ và thời điểm cấu hình trong SQL Server. Mọi phiên điều phối dùng chung vị trí này.</p></div>
      {error && <p role="alert" className="rounded-xl bg-red-50 p-4 text-red-800">{error}</p>}
      {notice && <p role="status" className="rounded-xl bg-emerald-50 p-4 text-emerald-800">{notice}</p>}
      <div className="flex flex-wrap gap-3"><label className="text-sm font-bold">Hoạt động<select aria-label="Trạng thái tài xế" value={status} disabled={saving} onChange={event => { setStatus(event.target.value); setPage(0); setLoading(true); }} className="ml-2 rounded-xl border bg-white p-2"><option value="ALL">Tất cả</option><option value="ONLINE">Online</option><option value="OFFLINE">Offline</option><option value="NGHI">Nghỉ</option></select></label><label className="text-sm font-bold">Phân công<select aria-label="Tài xế rảnh hoặc bận" value={availability} disabled={saving} onChange={event => { setAvailability(event.target.value); setPage(0); setLoading(true); }} className="ml-2 rounded-xl border bg-white p-2"><option value="ALL">Tất cả</option><option value="false">Rảnh</option><option value="true">Đang bận</option></select></label></div>
      {loading ? <p role="status">Đang tải tài xế...</p> : result && <>
        <p className="text-sm text-stone-500">{result?.totalElements ?? 0} tài xế khớp bộ lọc. Giấy phép, xe và điều kiện nhận đơn sẽ được kiểm tra lại khi gán.</p>
        {!result?.items.length && <p className="rounded-2xl border bg-white p-8 text-center">Không có tài xế phù hợp bộ lọc.</p>}
        <div className="grid gap-4 md:grid-cols-2">{result?.items.map(driver => <article key={driver.maTx} className="rounded-2xl border border-stone-200 bg-white p-5 space-y-3">
          <div className="flex flex-wrap justify-between gap-2"><div><h2 className="font-extrabold">{driver.hoTen}</h2><p className="text-xs text-stone-500">{driver.maTx} · {driver.soDienThoai}</p></div><span className="rounded-lg bg-stone-100 px-2 py-1 text-xs font-bold">{driver.trangThaiHoatDong} · {driver.dangBanChuyen ? "Bận" : "Rảnh"}</span></div>
          <p className="text-sm">Xe: {driver.phuongTien?.bienSoXe ?? "Chưa có phương tiện"}</p>
          {driver.diaChiViTriDemo ? <div className="rounded-xl bg-emerald-50 p-3 text-sm"><p className="font-bold">{driver.diaChiViTriDemo}</p><p className="mt-1 text-xs">{driver.viDoDemo}, {driver.kinhDoDemo} · {driver.nguonViTri}</p><p className="mt-1 text-xs">Cấu hình lúc {driver.viTriCapNhatLuc ? new Date(driver.viTriCapNhatLuc).toLocaleString("vi-VN") : "—"}</p></div> : <p className="rounded-xl bg-amber-50 p-3 text-sm text-amber-900">Chưa có vị trí đã xác minh. Chưa thể xếp gần nhất theo Goong.</p>}
          <button type="button" disabled={saving} onClick={() => { setEditing(driver.maTx); setAddress(driver.diaChiViTriDemo ?? ""); setError(""); setNotice(""); }} className="rounded-xl border px-3 py-2 text-sm font-bold disabled:opacity-50">Cấu hình vị trí {driver.maTx}</button>
          {editing === driver.maTx && <form className="space-y-3 border-t pt-4" onSubmit={async event => {
            event.preventDefault(); if (lock.current || !address.trim()) return; lock.current = true; setSaving(true); setError(""); setNotice("");
            try {
              await api(`/drivers/${encodeURIComponent(driver.maTx)}/demo-location`,{ method: "POST", body: JSON.stringify({ diaChi: address.trim() }) });
              setNotice(`Đã xác minh bằng Goong và lưu vị trí cố định của ${driver.hoTen}.`); setEditing(null); refresh();
            } catch (cause) { setError((cause as Error).message); } finally { lock.current = false; setSaving(false); }
          }}><label className="block text-sm font-bold">Địa chỉ cố định<input className="mt-2 w-full rounded-xl border p-3 font-normal" required maxLength={255} disabled={saving} value={address} onChange={event => setAddress(event.target.value)} placeholder="Địa chỉ đầy đủ tại TP.HCM" /></label><div className="flex gap-2"><button disabled={saving || !address.trim()} className="rounded-xl bg-amber-600 px-4 py-2 font-bold text-white disabled:opacity-50">{saving ? "Đang xác minh..." : "Xác minh Goong và lưu"}</button><button type="button" disabled={saving} onClick={() => setEditing(null)} className="rounded-xl border px-3 py-2 text-sm">Đóng</button></div></form>}
        </article>)}</div>
        <nav aria-label="Phân trang tài xế" className="flex justify-center gap-4 text-sm"><button disabled={saving || page===0} onClick={() => { setPage(value => value-1); setLoading(true); }} className="rounded-xl border bg-white px-3 py-2 disabled:opacity-40">Trang trước</button><span className="py-2">Trang {result?.totalPages ? page+1 : 0}/{result?.totalPages ?? 0}</span><button disabled={saving || page+1 >= (result?.totalPages ?? 0)} onClick={() => { setPage(value => value+1); setLoading(true); }} className="rounded-xl border bg-white px-3 py-2 disabled:opacity-40">Trang sau</button></nav>
      </>}
    </main>
  </div>;
}
