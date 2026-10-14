"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { LogoutButton } from "@/components/auth/LogoutButton";
import { api, type Order, vnd } from "@/lib/live-orders";

export default function DriverAssignedOrders() {
  const [orders,setOrders] = useState<Order[]>([]);
  const [error,setError] = useState("");
  const [loading,setLoading] = useState(true);
  const [page,setPage] = useState(0);
  const [pages,setPages] = useState(0);
  const [revision,setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    api<{items: Order[]; totalPages: number}>(`/orders?trangThai=DA_GAN&page=${page}&size=20`,{ signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) { setOrders(result.items); setPages(result.totalPages); setError(""); } })
      .catch(cause => { if (!controller.signal.aborted) setError((cause as Error).message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  },[page,revision]);
  return <div className="min-h-screen bg-stone-50 text-stone-900"><header className="flex flex-wrap justify-between gap-3 border-b bg-white p-4"><Link href="/driver/assigned" className="font-extrabold">Mini Ong Vàng · Tài xế</Link><LogoutButton className="rounded-xl border px-4 py-2 text-sm font-bold" /></header><main className="mx-auto max-w-4xl px-4 py-8 space-y-5"><div className="flex flex-wrap justify-between gap-3"><div><h1 className="text-3xl font-extrabold">Đơn được phân công</h1><p className="mt-2 text-sm text-stone-600">Chỉ các đơn DA_GAN thuộc tài xế đang đăng nhập, lấy từ backend.</p></div><button disabled={loading} onClick={() => { setLoading(true); setRevision(value => value+1); }} className="rounded-xl border bg-white px-4 py-2 font-bold disabled:opacity-50">Làm mới</button></div>{error && <p role="alert" className="rounded-xl bg-red-50 p-4 text-red-800">{error}</p>}{loading ? <p role="status">Đang tải đơn...</p> : !error && <>{!orders.length && <p className="rounded-2xl border bg-white p-8 text-center">Chưa có đơn mới được phân công.</p>}<div className="space-y-3">{orders.map(order => <Link key={order.maDon} href={`/driver/assigned/${encodeURIComponent(order.maDon)}`} className="block rounded-2xl border bg-white p-5 hover:border-amber-500"><div className="flex flex-wrap justify-between gap-2"><strong className="break-all text-sm">{order.maDon}</strong><span className="rounded-lg bg-amber-100 px-2 py-1 text-xs font-bold">{order.trangThai}</span></div><p className="mt-3 text-sm">{order.diemLayHang} → {order.diemGiaoHang}</p><p className="mt-2 text-sm font-bold">{vnd(order.cuoc.tongCuoc)}</p></Link>)}</div><nav aria-label="Phân trang đơn tài xế" className="flex justify-center gap-4 text-sm"><button disabled={page===0} onClick={() => { setPage(value => value-1); setLoading(true); }} className="rounded-xl border px-3 py-2 disabled:opacity-40">Trang trước</button><span className="py-2">Trang {pages ? page+1 : 0}/{pages}</span><button disabled={page+1>=pages} onClick={() => { setPage(value => value+1); setLoading(true); }} className="rounded-xl border px-3 py-2 disabled:opacity-40">Trang sau</button></nav></>}</main></div>;
}
