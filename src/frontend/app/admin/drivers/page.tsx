"use client";

import Link from "next/link";
import { useState } from "react";
import { useAdmin } from "@/components/admin/AdminProvider";
import { AdminDialog, AdminEmpty, AdminHeading, AdminStat, adminActivity } from "@/components/admin/AdminUI";
import type { AdminDriver } from "@/lib/admin-extras";

const blankDriver = (): AdminDriver => ({
  id: "", name: "", phone: "", vehiclePlate: "Chưa gán", vehicleType: "Chưa gán",
  currentDistrict: "Quận 1", status: "OFFLINE", idleSinceMinutes: 0, rating: 5,
  completedOrdersToday: 0, acceptanceRate: 100, locked: false,
});

export default function AdminDriversPage() {
  const { drivers, orders, vehicles, updateDemo } = useAdmin();
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState("ALL");
  const [form, setForm] = useState<AdminDriver | null>(null);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const occupiedIds = new Set(orders.filter((order) => ["DA_GAN", "DA_LAY_HANG", "DANG_GIAO"].includes(order.status)).map((order) => order.driver?.id));
  const status = (driver: AdminDriver) => driver.locked ? "Đã khóa" : occupiedIds.has(driver.id) ? "Đang giao" : driver.vehiclePlate === "Chưa gán" ? "Chưa có xe" : driver.status === "ONLINE_IDLE" ? "Sẵn sàng" : driver.status === "ONLINE_BUSY" ? "Đang giao" : "Ngoại tuyến";
  const visible = drivers.filter((driver) => {
    const text = `${driver.name} ${driver.phone} ${driver.vehiclePlate}`.toLocaleLowerCase("vi-VN");
    return text.includes(search.toLocaleLowerCase("vi-VN")) && (filter === "ALL" || (filter === "READY" ? status(driver) === "Sẵn sàng" : filter === "BUSY" ? status(driver) === "Đang giao" : status(driver) === "Đã khóa"));
  });
  const current = drivers.find((driver) => driver.id === selected);
  const save = () => {
    if (!form) return;
    if (form.name.trim().length < 3 || !/^0\d{9}$/.test(form.phone)) { setError("Nhập tên ít nhất 3 ký tự và số điện thoại 10 chữ số."); return; }
    if (drivers.some((driver) => driver.phone === form.phone && driver.id !== form.id)) { setError("Số điện thoại đã thuộc tài xế khác."); return; }
    const isNew = !form.id;
    const id = form.id || `TX-DEMO-${Math.max(0, ...drivers.map((driver) => Number(driver.id.split("-").at(-1)) || 0)) + 1}`;
    const saved = { ...form, id, name: form.name.trim() };
    updateDemo((previous) => ({ ...previous,
      drivers: isNew ? [...previous.drivers, saved] : previous.drivers.map((driver) => driver.id === id ? saved : driver),
      orders: previous.orders.map((order) => order.driver?.id === id ? { ...order, driver: { ...order.driver, name: saved.name, phone: saved.phone } } : order),
      activity: [adminActivity(isNew ? "Thêm tài xế" : "Cập nhật tài xế", saved.name), ...previous.activity],
    }));
    setForm(null); setSelected(id); setNotice(isNew ? "Đã thêm tài xế vào dữ liệu demo." : "Đã cập nhật hồ sơ tài xế.");
  };
  const toggleLock = (driver: AdminDriver) => {
    if (!window.confirm(`${driver.locked ? "Mở khóa" : "Khóa"} tài xế ${driver.name}?`)) return;
    updateDemo((previous) => ({ ...previous,
      drivers: previous.drivers.map((item) => item.id === driver.id ? { ...item, locked: !item.locked, status: !item.locked ? "OFFLINE" : "ONLINE_IDLE" } : item),
      activity: [adminActivity(driver.locked ? "Mở khóa tài xế" : "Khóa tài xế", driver.name), ...previous.activity],
    }));
    setNotice(`Đã ${driver.locked ? "mở khóa" : "khóa"} ${driver.name}.`);
  };

  return <>
    <AdminHeading eyebrow="Điều hành đội xe" title="Tài xế" description="Theo dõi khả năng nhận đơn, hồ sơ và công việc của từng tài xế." actions={<button className="admin-button primary" onClick={() => { setError(""); setForm(blankDriver()); }}>+ Thêm tài xế</button>} />
    {notice && <p className="admin-feedback" role="status">{notice}</p>}
    <div className="admin-stat-grid"><AdminStat label="Tổng tài xế" value={drivers.length} note="Trong đội xe" /><AdminStat label="Sẵn sàng" value={drivers.filter((driver) => status(driver) === "Sẵn sàng").length} note="Có thể nhận đơn" tone="accent" /><AdminStat label="Đang giao" value={drivers.filter((driver) => status(driver) === "Đang giao").length} note="Đã nhận công việc" /><AdminStat label="Đã khóa" value={drivers.filter((driver) => driver.locked).length} note="Không thể nhận đơn" tone="alert" /></div>
    <section className="admin-panel admin-filter-panel"><div className="admin-filter-grid admin-filter-compact"><div className="admin-field"><label htmlFor="driver-search">Tìm tài xế</label><input id="driver-search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Tên, SĐT hoặc biển số" /></div><div className="admin-field"><label htmlFor="driver-status">Trạng thái</label><select id="driver-status" value={filter} onChange={(event) => setFilter(event.target.value)}><option value="ALL">Tất cả</option><option value="READY">Sẵn sàng</option><option value="BUSY">Đang giao</option><option value="LOCKED">Đã khóa</option></select></div></div></section>
    <section className="admin-panel"><div className="admin-result-bar"><strong>{visible.length} tài xế</strong><span>Chọn một hàng để xem hồ sơ</span></div>{visible.length ? <div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>Tài xế</th><th>Phương tiện</th><th>Khu vực</th><th>Trạng thái</th><th>Cuốc hôm nay</th><th className="action">Thao tác</th></tr></thead><tbody>{visible.map((driver) => <tr key={driver.id}><td><strong>{driver.name}</strong><small>{driver.phone}</small></td><td>{driver.vehiclePlate}</td><td>{driver.currentDistrict}</td><td><span className={`admin-status ${driver.locked ? "danger" : status(driver) === "Sẵn sàng" ? "success" : status(driver) === "Đang giao" ? "info" : "muted"}`}>{status(driver)}</span></td><td>{driver.completedOrdersToday}</td><td className="action"><button className="admin-text-button" onClick={() => setSelected(driver.id)}>Chi tiết</button></td></tr>)}</tbody></table></div> : <AdminEmpty title="Không tìm thấy tài xế" detail="Thử từ khóa hoặc trạng thái khác." />}</section>
    {current && <section className="admin-panel admin-extra-detail"><div className="admin-section-head"><div><h2>{current.name}</h2><p>{current.id} · {current.phone}</p></div><button className="admin-text-button" onClick={() => setSelected(null)}>Đóng</button></div><div className="admin-detail-body"><div className="admin-summary-grid"><div><small>Đánh giá</small><strong>{current.rating.toFixed(2)} / 5</strong></div><div><small>Tỷ lệ nhận đơn</small><strong>{current.acceptanceRate}%</strong></div><div><small>Xe được gán</small><strong>{vehicles.find((vehicle) => vehicle.driverId === current.id)?.plate ?? "Chưa có"}</strong></div></div><div className="admin-inline-actions"><button className="admin-button" onClick={() => { setError(""); setForm({ ...current }); }}>Sửa hồ sơ</button><button className="admin-button danger" onClick={() => toggleLock(current)}>{current.locked ? "Mở khóa" : "Khóa tài khoản"}</button></div><h3 className="admin-subheading">Đơn đang phụ trách</h3>{orders.filter((order) => order.driver?.id === current.id && !["DA_HUY", "HOAN_TAT", "CHUA_THANH_TOAN"].includes(order.status)).map((order) => <Link className="admin-list-link" key={order.id} href={`/admin/orders/${order.id}`}>{order.id} · {order.sender.district} → {order.receiver.district}<span>Chi tiết →</span></Link>)}{!orders.some((order) => order.driver?.id === current.id && !["DA_HUY", "HOAN_TAT", "CHUA_THANH_TOAN"].includes(order.status)) && <p className="admin-muted">Chưa có đơn đang phụ trách.</p>}</div></section>}
    {form && <AdminDialog title={form.id ? "Sửa hồ sơ tài xế" : "Thêm tài xế"} description="Thông tin lưu trong dữ liệu demo của cổng quản trị." onClose={() => setForm(null)} onSubmit={save} submitLabel="Lưu tài xế" error={error}><div className="admin-form-grid"><div className="admin-field"><label htmlFor="driver-name">Họ tên</label><input id="driver-name" value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></div><div className="admin-field"><label htmlFor="driver-phone">Số điện thoại</label><input id="driver-phone" value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} /></div><div className="admin-field"><label htmlFor="driver-district">Khu vực</label><select id="driver-district" value={form.currentDistrict} onChange={(event) => setForm({ ...form, currentDistrict: event.target.value })}>{["Quận 1", "Quận 3", "Quận 5", "Quận 7", "Quận 10", "Bình Thạnh"].map((district) => <option key={district}>{district}</option>)}</select></div><div className="admin-field"><label htmlFor="driver-online">Trạng thái trực</label><select id="driver-online" value={form.status} onChange={(event) => setForm({ ...form, status: event.target.value as AdminDriver["status"] })}><option value="ONLINE_IDLE">Sẵn sàng</option><option value="OFFLINE">Ngoại tuyến</option></select></div></div></AdminDialog>}
  </>;
}
