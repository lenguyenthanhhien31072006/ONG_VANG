# Bỏ chức năng nộp COD

Nhánh database bỏ quyết toán/nộp tiền mặt về công ty; vẫn giữ khách trả cước
bằng tiền mặt hoặc VNPay, chia cước 20/80 và thu nhập phát sinh của tài xế.

## CSDL

Flyway tự chạy `V13__remove_cod_settlement.sql` khi backend khởi động.
Migration xóa bốn bảng và dữ liệu nộp COD trong đó, theo thứ tự khóa ngoại:
`su_kien_nop_cod`, `lan_nop_cod`, `khoan_cod_trong_dot`, `dot_nop_cod`.
Sao lưu dữ liệu nộp COD nếu cần giữ lịch sử trước khi triển khai.
Không sửa V10 đã áp dụng: DB mới chạy V10 rồi V13; DB cũ chạy thêm V13.
`phan_chia_cuoc`, `thanh_toan`, xác nhận thanh toán, đơn và phân công được giữ.

## API và giao diện

- Bỏ các API `/api/cod/*` và nhánh callback VNPay dành cho nộp COD.
- Callback VNPay thanh toán cước tiếp tục hoạt động theo luồng cũ.
- Báo cáo/Excel giữ cước hoàn tất, thực thu, công nợ và phần công ty/tài xế;
  bỏ `tienMatTaiXeDangGiu`, `tienNopCod`.
- Thu nhập tài xế giữ `maTx`, `hoTen`, `tongCuocDaHoanTat`,
  `phanTaiXePhatSinh`; bỏ các chỉ tiêu chờ COD/nộp COD/đủ điều kiện chi trả.
  Thu nhập phát sinh không có nghĩa đã chuyển tiền về ngân hàng.
- Gỡ bảng/nút nộp COD ở báo cáo điều phối và các thẻ/cột COD liên quan.
  Các bố cục và thao tác ngoài phần này được giữ.
- Tài liệu TX7 trước đây mô tả lịch sử yêu cầu; phần nộp COD không còn áp dụng.

## Kiểm tra

```bash
npm run check --prefix tools/api
npm run lint --prefix src/frontend
npm run build --prefix src/frontend
mvn -f src/backend/pom.xml verify
```

Để thực sự chạy integration test, cần SQL Server test và các biến `TEST_DB_URL`,
`TEST_DB_USER`, `TEST_DB_PASSWORD`, `REQUIRE_TEST_DB=true` theo CI.
`DesignV1IntegrationTest.removedCodTablesDoNotBreakCashCompletionRevenueAndDriverEarnings`
kiểm tra bốn bảng đã biến mất, hoàn tất đơn tiền mặt, chia cước, báo cáo và thu nhập.
Các smoke test payment, reports, dispatch, cancellation, progress vẫn cần chạy
trên backend kết nối DB test; build/unit test không thay thế những bước này.
