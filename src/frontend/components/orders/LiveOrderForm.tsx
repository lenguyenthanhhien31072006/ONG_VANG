"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { DispatcherNavbar } from "@/components/layout/DispatcherNavbar";
import { Navbar } from "@/components/layout/Navbar";
import { api, type Order, type PaymentChoice, type PaymentMethod, type Quote, vnd } from "@/lib/live-orders";

type Form = {
  maKh: string; diemLayHang: string; diemGiaoHang: string; tenNguoiNhan: string;
  sdtNguoiNhan: string; loaiHangHoa: string; ghiChuBaoQuan: string; khoiLuongKg: string;
  ghiChuGiaoHang: string; benThanhToan: PaymentChoice; phuongThucThanhToan: PaymentMethod;
};
type Customer = { maKh: string; hoTen: string; soDienThoai: string };
const initial: Form = {
  maKh: "", diemLayHang: "", diemGiaoHang: "", tenNguoiNhan: "", sdtNguoiNhan: "",
  loaiHangHoa: "Tài liệu", ghiChuBaoQuan: "", khoiLuongKg: "1.00", ghiChuGiaoHang: "",
  benThanhToan: "NGUOI_DAT", phuongThucThanhToan: "TIEN_MAT",
};

export function LiveOrderForm({ dispatcher = false, dispatcherPath = "/dispatcher" }: { dispatcher?: boolean; dispatcherPath?: string }) {
  const router = useRouter();
  const [form, setForm] = useState<Form>(initial);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState<"quote" | "order" | null>(null);
  const [customerQuery, setCustomerQuery] = useState("");
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [ready, setReady] = useState(false);
  const revision = useRef(0);
  const key = useRef<string | null>(null);

  useEffect(() => {
    api<{ vaiTro: string }>("/auth/me")
      .then(user => {
        if (user.vaiTro !== (dispatcher ? "TONG_DAI" : "KHACH_HANG")) throw new Error("Tài khoản không có quyền tạo đơn tại đây.");
        setReady(true);
      })
      .catch(cause => setError(cause instanceof Error ? cause.message : "Chưa đăng nhập."));
  }, [dispatcher]);

  function change<K extends keyof Form>(field: K, value: Form[K]) {
    revision.current++;
    key.current = null;
    setQuote(null);
    setError("");
    setForm(previous => ({ ...previous, [field]: value }));
  }

  async function searchCustomer() {
    setError("");
    if (!customerQuery.trim()) { setError("Hãy nhập tên hoặc SĐT khách hàng."); return; }
    try {
      const result = await api<{ items: Customer[] }>(`/customers?q=${encodeURIComponent(customerQuery.trim())}&size=20`);
      setCustomers(result.items);
      if (!result.items.length) setError("Không tìm thấy khách hàng trong DB.");
    } catch (cause) { setError((cause as Error).message); }
  }

  const quoteInput = {
    ...(dispatcher ? { maKh: form.maKh } : {}), diemLayHang: form.diemLayHang.trim(),
    diemGiaoHang: form.diemGiaoHang.trim(),
    kienHang: [{ loaiHangHoa: form.loaiHangHoa.trim(), ghiChuBaoQuan: form.ghiChuBaoQuan.trim(), khoiLuongKg: form.khoiLuongKg }],
    benThanhToan: form.benThanhToan, phuongThucThanhToan: form.phuongThucThanhToan,
  };

  async function requestQuote() {
    setError("");
    if (dispatcher && !form.maKh) { setError("Hãy chọn khách hàng từ DB."); return; }
    if (!form.diemLayHang.trim() || !form.diemGiaoHang.trim() || !form.loaiHangHoa.trim()
        || !/^\d+(\.\d{1,2})?$/.test(form.khoiLuongKg) || Number(form.khoiLuongKg) < 0.01) {
      setError("Hãy nhập hai địa chỉ đầy đủ, kiện hàng và kg hợp lệ."); return;
    }
    const at = revision.current;
    setBusy("quote");
    try {
      const result = await api<Quote>("/quotes", { method: "POST", body: JSON.stringify(quoteInput) });
      if (at === revision.current) { setQuote(result); key.current = null; }
    } catch (cause) { if (at === revision.current) setError((cause as Error).message); }
    finally { setBusy(null); }
  }

  async function createOrder() {
    if (!quote || busy) return;
    if (Date.now() >= Date.parse(quote.hetHanLuc)) { setQuote(null); setError("Báo giá đã hết hạn. Vui lòng báo giá lại."); return; }
    if (!form.tenNguoiNhan.trim() || !/^0\d{9}$/.test(form.sdtNguoiNhan)) {
      setError("Hãy nhập tên và SĐT người nhận hợp lệ."); return;
    }
    setError(""); setBusy("order");
    if (!key.current) key.current = crypto.randomUUID();
    try {
      const result = await api<Order>("/orders", {
        method: "POST", headers: { "Idempotency-Key": key.current },
        body: JSON.stringify({ ...quoteInput, maBaoGia: quote.maBaoGia,
          tenNguoiNhan: form.tenNguoiNhan.trim(), sdtNguoiNhan: form.sdtNguoiNhan,
          ghiChuGiaoHang: form.ghiChuGiaoHang.trim() }),
      });
      router.push(dispatcher ? `${dispatcherPath}/orders/${result.maDon}` : `/orders/${result.maDon}`);
    } catch (cause) {
      const code = (cause as { code?: string }).code;
      if (code === "QUOTE_CHANGED" || code === "QUOTE_EXPIRED") { setQuote(null); key.current = null; }
      setError((cause as Error).message);
    } finally { setBusy(null); }
  }

  const field = (label: string, name: keyof Form, placeholder = "", type = "text") => (
    <label className="block text-sm font-semibold text-stone-700">{label}
      <input type={type} value={form[name]} placeholder={placeholder} step={type === "number" ? "0.01" : undefined}
        onChange={event => change(name, event.target.value as Form[typeof name])}
        className="mt-1.5 w-full rounded-xl border border-stone-300 bg-white px-3 py-2.5 font-normal text-stone-900 outline-none focus:border-amber-600" />
    </label>
  );

  return <div className="min-h-screen bg-stone-50 text-stone-900">
    {dispatcher ? <DispatcherNavbar /> : <Navbar />}
    <main className="mx-auto max-w-4xl space-y-6 px-4 py-8">
      <div><p className="text-xs font-bold uppercase tracking-widest text-amber-700">{dispatcher ? "Tổng đài" : "Khách hàng"}</p>
        <h1 className="mt-1 text-3xl font-extrabold">Tạo đơn giao hàng</h1>
        <p className="mt-2 text-sm text-stone-600">Địa chỉ được backend kiểm tra qua Goong. Khi chạy fixture, kết quả sẽ hiện nhãn DEMO.</p></div>
      {!ready && !error && <p>Đang kiểm tra phiên đăng nhập...</p>}
      {error && <div role="alert" className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">{error}</div>}
      {ready && <>
        {dispatcher && <section className="rounded-2xl border border-stone-200 bg-white p-5">
          <h2 className="mb-3 text-lg font-bold">Khách hàng đặt đơn</h2>
          <div className="flex gap-2"><input value={customerQuery} onChange={event => setCustomerQuery(event.target.value)}
            placeholder="Tên hoặc SĐT khách" className="min-w-0 flex-1 rounded-xl border border-stone-300 px-3 py-2" />
            <button type="button" onClick={searchCustomer} className="rounded-xl bg-stone-900 px-4 py-2 text-white">Tìm DB</button></div>
          {customers.length > 0 && <select value={form.maKh} onChange={event => change("maKh", event.target.value)}
            className="mt-3 w-full rounded-xl border border-stone-300 px-3 py-2">
            <option value="">Chọn khách hàng</option>{customers.map(c => <option key={c.maKh} value={c.maKh}>{c.hoTen} · {c.soDienThoai}</option>)}
          </select>}
        </section>}
        <section className="space-y-4 rounded-2xl border border-stone-200 bg-white p-5">
          <h2 className="text-lg font-bold">Địa chỉ và người nhận</h2>
          {field("Địa chỉ lấy hàng đầy đủ", "diemLayHang", "Số nhà, đường, phường, TP.HCM")}
          {field("Địa chỉ giao hàng đầy đủ", "diemGiaoHang", "Số nhà, đường, phường, TP.HCM")}
          <div className="grid gap-4 sm:grid-cols-2">{field("Tên người nhận", "tenNguoiNhan")}{field("SĐT người nhận", "sdtNguoiNhan", "09xxxxxxxx", "tel")}</div>
        </section>
        <section className="space-y-4 rounded-2xl border border-stone-200 bg-white p-5">
          <h2 className="text-lg font-bold">Kiện và thanh toán</h2>
          <div className="grid gap-4 sm:grid-cols-2">{field("Loại hàng hóa", "loaiHangHoa")}{field("Khối lượng (kg)", "khoiLuongKg", "1.00", "number")}</div>
          {field("Ghi chú bảo quản", "ghiChuBaoQuan")}{field("Ghi chú giao hàng", "ghiChuGiaoHang")}
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="text-sm font-semibold">Bên trả cước
              <select value={form.benThanhToan} onChange={event => change("benThanhToan", event.target.value as PaymentChoice)} className="mt-1.5 w-full rounded-xl border border-stone-300 px-3 py-2.5">
                <option value="NGUOI_DAT">Người đặt</option><option value="NGUOI_NHAN">Người nhận</option></select></label>
            <label className="text-sm font-semibold">Phương thức trả
              <select value={form.phuongThucThanhToan} onChange={event => change("phuongThucThanhToan", event.target.value as PaymentMethod)} className="mt-1.5 w-full rounded-xl border border-stone-300 px-3 py-2.5">
                <option value="TIEN_MAT">Tiền mặt</option><option value="VNPAY_QR">VNPay QR</option></select></label>
          </div>
          <button type="button" onClick={requestQuote} disabled={busy !== null} className="rounded-xl bg-stone-900 px-5 py-3 font-bold text-white disabled:opacity-50">{busy === "quote" ? "Đang báo giá..." : "Kiểm tra địa chỉ và báo giá"}</button>
        </section>
        {quote && <section className="space-y-3 rounded-2xl border border-amber-300 bg-amber-50 p-5">
          <div className="flex items-center justify-between gap-2"><h2 className="text-lg font-bold">Báo giá từ backend</h2>
            <span className="rounded-lg bg-amber-200 px-2 py-1 text-xs font-bold">{quote.loTrinh.nguon === "FAKE" ? "DEMO · fixture" : "GOONG"}</span></div>
          <p className="text-sm">{quote.loTrinh.diemLayHang} → {quote.loTrinh.diemGiaoHang}</p>
          <p className="text-sm">{quote.loTrinh.quangDuongKm} km · khoảng {Math.ceil(quote.loTrinh.thoiGianDuKienGiay / 60)} phút · {quote.cuoc.tongKhoiLuongKg} kg</p>
          <dl className="grid grid-cols-2 gap-2 text-sm">
            <dt>Cước km</dt><dd className="text-right">{vnd(quote.cuoc.cuocKm)}</dd>
            <dt>Cước kg</dt><dd className="text-right">{vnd(quote.cuoc.cuocKhoiLuong)}</dd>
            <dt>Phụ thu</dt><dd className="text-right">{vnd(quote.cuoc.tienPhuThu)}</dd>
            <dt>Giảm thành viên</dt><dd className="text-right">−{vnd(quote.cuoc.tienGiamGia)}</dd>
            <dt className="border-t pt-2 font-bold">Tổng cước</dt><dd className="border-t pt-2 text-right text-xl font-extrabold">{vnd(quote.cuoc.tongCuoc)}</dd>
          </dl>
          <p className="text-xs text-stone-600">Biểu phí {quote.cuoc.maBieuPhi} v{quote.cuoc.phienBanBieuPhi} · Hết hạn {new Date(quote.hetHanLuc).toLocaleString("vi-VN")}</p>
          {quote.hangThanhVien && <p className="text-xs">Hạng {quote.hangThanhVien.tenHang}: {quote.hangThanhVien.conHieuLuc ? `giảm ${quote.hangThanhVien.phanTramGiamGia}%` : "ưu đãi hết hạn"}</p>}
          <button type="button" onClick={createOrder} disabled={busy !== null} className="rounded-xl bg-amber-700 px-5 py-3 font-bold text-white disabled:opacity-50">{busy === "order" ? "Đang tạo đơn..." : "Xác nhận tạo đơn"}</button>
        </section>}
      </>}
    </main>
  </div>;
}
