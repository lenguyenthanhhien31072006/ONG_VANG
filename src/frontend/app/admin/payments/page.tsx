"use client";

import Link from "next/link";
import { useState } from "react";
import { useAdmin } from "@/components/admin/AdminProvider";
import { AdminDialog, AdminEmpty, AdminHeading, AdminStat, adminActivity } from "@/components/admin/AdminUI";
import { formatVND } from "@/lib/utils";

export default function AdminPaymentsPage() {
  const { orders, updateDemo } = useAdmin();
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState("ALL");
  const [method, setMethod] = useState("ALL");
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const active = orders.filter((order) => order.status !== "DA_HUY");
  const completed = active.filter((order) => ["HOAN_TAT", "CHUA_THANH_TOAN"].includes(order.status));
  const collected = completed.filter((order) => order.paid).reduce((sum, order) => sum + order.totalFee, 0);
  const due = completed.filter((order) => !order.paid).reduce((sum, order) => sum + order.totalFee, 0);
  const visible = active.filter((order) => order.id.toLocaleLowerCase().includes(query.toLocaleLowerCase()) && (filter === "ALL" || (filter === "PAID" ? order.paid : !order.paid)) && (method === "ALL" || order.paymentMethod === method));
  const selectedOrder = orders.find((order) => order.id === selected);
  const confirmOrder = orders.find((order) => order.id === confirmId);
  const recordPayment = () => {
    if (!confirmOrder || confirmOrder.paid || !["HOAN_TAT", "CHUA_THANH_TOAN"].includes(confirmOrder.status)) return;
    updateDemo((previous) => ({ ...previous,
      orders: previous.orders.map((order) => order.id === confirmOrder.id ? { ...order, paid: true, status: "HOAN_TAT", statusText: "Đã hoàn tất", paymentMethod: "TIEN_MAT" } : order),
      activity: [adminActivity("Ghi nhận thu tiền mặt", `${confirmOrder.id}: ${formatVND(confirmOrder.totalFee)}`), ...previous.activity],
    }));
    setConfirmId(null); setNotice(`Đã ghi nhận thu ${formatVND(confirmOrder.totalFee)} cho ${confirmOrder.id} trong dữ liệu demo.`);
  };

  return <>
    <AdminHeading eyebrow="Tài chính đội xe" title="Thanh toán & đối soát" description="Theo dõi cước vận chuyển và tình trạng thanh toán." />
    {notice && <p className="admin-feedback" role="status">{notice}</p>}
    <div className="admin-stat-grid"><AdminStat label="Cước đã thu" value={formatVND(collected)} note="Đơn hoàn tất đã thanh toán" tone="accent" /><AdminStat label="Cước chưa thu" value={formatVND(due)} note="Đơn hoàn tất cần thu" tone="alert" /><AdminStat label="Đơn hoàn tất chưa thu" value={completed.filter((order) => !order.paid).length} note="Chưa xác nhận thanh toán" /></div>
    <section className="admin-panel admin-filter-panel"><div className="admin-filter-grid admin-filter-compact"><div className="admin-field"><label htmlFor="payment-search">Tìm mã đơn</label><input id="payment-search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Ví dụ: DH-2026-001230" /></div><div className="admin-field"><label htmlFor="payment-state">Tình trạng</label><select id="payment-state" value={filter} onChange={(event) => setFilter(event.target.value)}><option value="ALL">Tất cả</option><option value="PAID">Đã thu</option><option value="UNPAID">Chưa thu</option></select></div><div className="admin-field"><label htmlFor="payment-method">Phương thức</label><select id="payment-method" value={method} onChange={(event) => setMethod(event.target.value)}><option value="ALL">Tất cả</option><option value="TIEN_MAT">Tiền mặt</option><option value="VNPAY">VNPay</option></select></div></div></section>
    <section className="admin-panel"><div className="admin-result-bar"><strong>{visible.length} đơn hàng</strong><span>Tình trạng thanh toán demo</span></div>{visible.length ? <div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>Mã đơn</th><th>Khách hàng</th><th>Phương thức</th><th>Trạng thái đơn</th><th>Thanh toán</th><th className="money">Cước</th><th className="action">Thao tác</th></tr></thead><tbody>{visible.map((order) => <tr key={order.id}><td><strong>{order.id}</strong><small>{order.createdAt}</small></td><td>{order.sender.name}</td><td>{order.paymentMethod === "VNPAY" ? "VNPay" : order.paymentMethod === "TIEN_MAT" ? "Tiền mặt" : "Chưa chọn"}</td><td>{order.statusText}</td><td><span className={`admin-status ${order.paid ? "success" : "danger"}`}>{order.paid ? "Đã thu" : "Chưa thu"}</span></td><td className="money">{formatVND(order.totalFee)}</td><td className="action"><button className="admin-text-button" onClick={() => setSelected(order.id)}>Chi tiết</button>{!order.paid && ["HOAN_TAT", "CHUA_THANH_TOAN"].includes(order.status) && <button className="admin-text-button" onClick={() => setConfirmId(order.id)}>Ghi nhận thu</button>}</td></tr>)}</tbody></table></div> : <AdminEmpty title="Không có đơn phù hợp" detail="Thử thay đổi bộ lọc thanh toán." />}</section>
    {selectedOrder && <section className="admin-panel admin-extra-detail"><div className="admin-section-head"><div><h2>Thanh toán {selectedOrder.id}</h2><p>{selectedOrder.sender.name} · {selectedOrder.createdAt}</p></div><button className="admin-text-button" onClick={() => setSelected(null)}>Đóng</button></div><div className="admin-detail-body"><div className="admin-summary-grid"><div><small>Cước gốc</small><strong>{formatVND(selectedOrder.baseFee)}</strong></div><div><small>Phụ thu / giảm</small><strong>+{formatVND(selectedOrder.surchargeFee)} / −{formatVND(selectedOrder.vipDiscountFee)}</strong></div><div><small>Cước cần thanh toán</small><strong>{formatVND(selectedOrder.totalFee)}</strong></div></div><p className="admin-muted">Trạng thái: {selectedOrder.paid ? "Đã thu" : "Chưa thu"}. Phương thức: {selectedOrder.paymentMethod === "VNPAY" ? "VNPay" : selectedOrder.paymentMethod === "TIEN_MAT" ? "Tiền mặt" : "Chưa chọn"}.</p><Link className="admin-button" href={`/admin/orders/${selectedOrder.id}`}>Xem chi tiết đơn →</Link></div></section>}
    {confirmOrder && <AdminDialog title="Ghi nhận thu tiền mặt" description={`Xác nhận đã thu ${formatVND(confirmOrder.totalFee)} cho đơn ${confirmOrder.id} trong dữ liệu demo.`} onClose={() => setConfirmId(null)} onSubmit={recordPayment} submitLabel="Xác nhận đã thu"><p className="admin-inline-note">Thao tác chỉ cập nhật trạng thái hiển thị trong cổng quản trị demo.</p></AdminDialog>}
  </>;
}
