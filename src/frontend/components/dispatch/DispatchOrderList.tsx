"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { DispatcherNavbar } from "@/components/layout/DispatcherNavbar";
import { Navbar } from "@/components/layout/Navbar";
import { api, type Order, vnd } from "@/lib/live-orders";

async function loadOrders(page: number, filter: string, dispatcher: boolean, signal?: AbortSignal) {
  const query = new URLSearchParams({ page: String(page), size: "20" });
  if (filter !== "ALL") query.set("trangThai", filter);
  const [result, pending] = await Promise.all([
    api<{ items: Order[]; totalPages: number }>(`/orders?${query}`,{ signal }),
    dispatcher ? api<{ totalElements: number }>("/orders?trangThai=CHO_GAN&size=1",{ signal }) : Promise.resolve(null),
  ]);
  return { result, pending };
}

export function DispatchOrderList({ dispatcher = false }: { dispatcher?: boolean }) {
  const [orders, setOrders] = useState<Order[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState(dispatcher ? "CHO_GAN" : "ALL");
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [pendingCount, setPendingCount] = useState<number | undefined>();
  const [revision, setRevision] = useState(0);
  function refresh() { setLoading(true); setRevision(value => value + 1); }
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    loadOrders(page, filter, dispatcher,controller.signal)
      .then(({ result, pending }) => {
        if (!active) return;
        setOrders(result.items); setTotalPages(result.totalPages); setError("");
        if (pending) setPendingCount(pending.totalElements);
      })
      .catch(cause => { if (active) setError((cause as Error).message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; controller.abort(); };
  }, [dispatcher, filter, page, revision]);
  return <div className="min-h-screen bg-stone-50 text-stone-900">
    {dispatcher ? <DispatcherNavbar pendingCount={pendingCount} /> : <Navbar />}
    <main className="mx-auto max-w-5xl space-y-6 px-4 py-8">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div><p className="text-xs font-bold uppercase tracking-widest text-amber-700">{dispatcher ? "Điều phối" : "Khách hàng"}</p>
          <h1 className="mt-1 text-3xl font-extrabold">{dispatcher ? "Bàn điều phối" : "Lịch sử đơn hàng"}</h1>
          <p className="mt-2 text-sm text-stone-600">Dữ liệu từ backend, dùng chung giữa các phiên đăng nhập.</p></div>
        <div className="flex flex-wrap gap-2">{dispatcher && <Link href="/dispatcher/live/fleet" className="rounded-xl border border-stone-300 px-4 py-2 text-sm font-bold">Vị trí và tài xế</Link>}<button onClick={refresh} disabled={loading} className="rounded-xl border border-stone-300 px-4 py-2 text-sm font-bold disabled:opacity-50">Làm mới</button>
          <Link href={dispatcher ? "/dispatcher/live/create-order" : "/create-order"} className="rounded-xl bg-amber-700 px-4 py-2 text-sm font-bold text-white">Tạo đơn</Link></div>
      </div>
      {error && <p role="alert" className="rounded-xl bg-red-50 p-4 text-red-800">{error}</p>}
      <div className="flex gap-2"><button onClick={() => { setLoading(true); setPage(0); setFilter("ALL"); setRevision(value => value + 1); }} className={`rounded-xl px-4 py-2 text-sm ${filter === "ALL" ? "bg-stone-900 text-white" : "bg-white"}`}>Tất cả</button>
        <button onClick={() => { setLoading(true); setPage(0); setFilter("CHO_GAN"); setRevision(value => value + 1); }} className={`rounded-xl px-4 py-2 text-sm ${filter === "CHO_GAN" ? "bg-stone-900 text-white" : "bg-white"}`}>Chờ gán</button></div>
      {loading ? <p role="status">Đang tải đơn...</p> : error ? null : orders.length === 0 ? <p className="rounded-2xl border bg-white p-8 text-center text-stone-600">Chưa có đơn phù hợp.</p> :
        <div className="space-y-3">{orders.map(order => <Link key={order.maDon}
          href={dispatcher ? `/dispatcher/live/orders/${order.maDon}` : `/orders/${order.maDon}`}
          className="block rounded-2xl border border-stone-200 bg-white p-5 hover:border-amber-500">
          <div className="flex flex-wrap items-center justify-between gap-2"><strong className="font-mono text-sm">{order.maDon}</strong>
            <span className="rounded-lg bg-amber-100 px-2 py-1 text-xs font-bold text-amber-900">{order.trangThai}</span></div>
          <p className="mt-3 text-sm">{order.diemLayHang} → {order.diemGiaoHang}</p>
          {dispatcher && <p className="mt-2 text-xs text-stone-500">{order.maTx ? `Đang phân công: ${order.maTx}` : "Chưa phân công tài xế"}</p>}
          <div className="mt-3 flex flex-wrap justify-between gap-2 text-sm text-stone-600"><span>{new Date(order.thoiGianTao).toLocaleString("vi-VN")}</span>
            <strong className="text-stone-900">{vnd(order.cuoc.tongCuoc)}</strong></div>
        </Link>)}</div>}
      {totalPages > 1 && <div className="flex items-center justify-center gap-4 text-sm">
        <button type="button" onClick={() => { setLoading(true); setPage(previous => Math.max(0, previous - 1)); }} disabled={loading || page === 0} className="rounded-lg border px-3 py-2 disabled:opacity-40">Trang trước</button>
        <span>Trang {page + 1}/{totalPages}</span>
        <button type="button" onClick={() => { setLoading(true); setPage(previous => Math.min(totalPages - 1, previous + 1)); }} disabled={loading || page + 1 >= totalPages} className="rounded-lg border px-3 py-2 disabled:opacity-40">Trang sau</button>
      </div>}
    </main>
  </div>;
}
