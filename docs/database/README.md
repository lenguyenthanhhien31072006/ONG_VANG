# Cơ sở dữ liệu designV1

> Nhánh database: chức năng nộp COD đã bỏ bởi V13; xem [phạm vi thay đổi](remove-cod-settlement.md).

Mô tả schema hiện hành ở [Chương 3](chuong3.md): Flyway V1–V12 tạo **26 bảng ứng dụng**; `demo_seed_manifest` và `flyway_schema_history` phục vụ vận hành, còn `sysdiagrams` có thể do SSMS tạo. [designV1.md](designV1.md) và [designV1-constraint-matrix.md](designV1-constraint-matrix.md) là tài liệu của baseline ban đầu, trước các phần mở rộng TX và auth. Không chạy các migration V1–V7 của mô hình cũ trên DB này.

## Khởi động local

Điền mật khẩu riêng vào `.env`; đặt `MSSQL_DATABASE=mini_ong_vang_design_v1`. Sau đó chạy:

```powershell
docker compose up -d --build backend
```

`db-init` chỉ tạo database khi chưa có; ứng dụng chạy Flyway, JPA `validate`, rồi seed nếu `DEMO_SEED=true`. Mỗi thay đổi schema đã áp dụng phải thêm migration V2+; không sửa V1 sau khi đã chạy. Database cũ `mini_ong_vang` được giữ để đối chiếu, không phải nguồn dữ liệu của backend mới. Không dùng `down --volumes` nếu còn muốn giữ dữ liệu local.

Kiểm tra `http://localhost:8081/api/health`: trường `databaseName` phải là `mini_ong_vang_design_v1`. Trong SSMS, kết nối SQL Server ở `localhost,1433` bằng tài khoản local trong `.env`, rồi chọn DB mới.

## Mô hình dữ liệu và transaction

`tai_khoan` chứa tên, số điện thoại, vai trò và trạng thái. `khach_hang` chứa hạng enum cùng khoảng hiệu lực; hạng không còn là bảng riêng. Chính sách giảm giá, biểu phí, phụ thu được giữ theo phiên bản và tham chiếu từ đơn để giá cũ không đổi theo chính sách mới. Tài xế của đơn suy từ `phan_cong_don_hang`, không lưu thêm `don_hang.ma_tx`. Cước gốc và giảm giá ở `cuoc_don_hang`; tổng cước được tính từ cước, phụ thu đã chọn và giảm giá, không lưu một cột tổng dư.

Tạo đơn ghi `don_hang`, `cuoc_don_hang`, `chi_tiet_kien_hang`, `phu_thu_don_hang`, `nhat_ky_don_hang` và `yeu_cau_tao_don` trong cùng transaction. Điều phối khóa đơn/tài xế và dùng unique index có điều kiện để chống hai phân công hoạt động. `PolicyPublicationService` là đường quản trị nội bộ: xác thực `CHU_DOI_XE`, khóa phạm vi bằng SQL Server application lock kể cả khi chưa có policy công bố, kiểm tra chồng hiệu lực và ghi audit cùng transaction. Trigger V2 chặn sửa/xóa nội dung đã công bố; chỉ cho đóng mốc kết thúc tương lai một lần. Luồng này chưa có endpoint công khai. Các bất biến liên bảng như một đơn có đủ kiện, giá đúng phiên bản và trạng thái khớp phân công được kiểm tra thêm bằng `scripts/check-design-v1-invariants.sql`. T14–T15 đã có service/endpoint tiền mặt, VNPay/IPN/Return/query; T16 có lịch sử và T17 có báo cáo/Excel.

## Seed và DB test

Muốn nạp tài khoản demo, đặt `DEMO_SEED=true` và `DEMO_PASSWORD` trong `.env` rồi recreate backend. Seed `DesignV1DemoSeeder` ghi marker `design-v1`, chạy lại không nhân đôi và không ghi đè đơn mới. Nếu DB đã có seed cũ, lần chạy tiếp theo bổ sung các hồ sơ TX2 còn thiếu và đồng bộ mật khẩu demo khi biến môi trường thay đổi. Tài khoản đăng nhập: `demo_admin` (chủ đội xe), `demo_dispatcher` (điều phối), `0800000001`–`0800000003` (tài xế có hồ sơ và xe), `0900000001` (khách thường), `0900000002` (hạng VÀNG còn hiệu lực), `0900000003` (hạng VÀNG hết hiệu lực). Tất cả dùng cùng mật khẩu từ `DEMO_PASSWORD`; mật khẩu chỉ lưu dạng bcrypt hash. Không có đơn demo dựng sẵn; smoke tạo đơn qua API.

DB test là `mini_ong_vang_test` ở cổng 15433, độc lập DB dev. `scripts/reset-test-db.sql` từ chối mọi DB khác và xóa dữ liệu theo thứ tự FK; chỉ trong transaction reset nó tạm tắt ba trigger policy rồi bật lại, không xóa schema hay Flyway history. `SeedCommand test` xác minh URL, tên DB thực và `REQUIRE_TEST_DB=true` trước khi nạp lại fixture. CI chạy `clean verify`, reset/seed, rồi HTTP smoke. Trước smoke tiến trình giao hàng, CI reset/seed lần nữa vì smoke điều phối làm tài xế bận.

```powershell
$env:TEST_DB_URL='jdbc:sqlserver://127.0.0.1:15433;databaseName=mini_ong_vang_test;encrypt=true;trustServerCertificate=true'
$env:TEST_DB_USER='sa'
$env:TEST_DB_PASSWORD='<mật khẩu DB test>'
$env:REQUIRE_TEST_DB='true'
docker compose -f docker-compose.ci.yml up --wait db-test
docker compose -f docker-compose.ci.yml up --no-deps --exit-code-from db-init-test db-init-test
mvn.cmd -f src/backend/pom.xml clean verify
```

Để reset và seed test sau khi set thêm `DEMO_PASSWORD`, chạy `scripts/reset-test-db.ps1`. Script dừng backend-test trước khi xóa. Không dùng script này cho DB dev. Sau test, chạy `scripts/check-design-v1-invariants.sql` bằng `sqlcmd -b -d mini_ong_vang_test`; script chỉ đọc, trả lỗi khi V2/trigger thiếu, policy chồng hoặc dữ liệu liên bảng sai.

## Giới hạn đang theo dõi

M1 đã chạy các luồng T06–T12 trên schema mới và HTTP smoke đạt. Đã có đường quản trị policy nội bộ, trigger bất biến và truy vấn đối chiếu; vẫn cần rà các ca lỗi/rollback còn thiếu trước khi đóng gate M1. T13–T17 đã có implementation; T18 hiện đồng bộ tài liệu, chưa bổ sung seed đơn/thanh toán. Hồi quy và nghiệm thu M2 chưa được coi là hoàn tất; xem [phạm vi T18](../T18-sandbox-api.md). Trạng thái chi tiết nằm trong [kế hoạch triển khai](designV1-implementation-plan.md).


T19 bổ sung V4 bảo vệ role/profile và vai trò tài khoản bất biến. Script invariant hiện kiểm tra cả xác nhận thanh toán, tổng tiền, payment chưa giải quyết và điều kiện khởi tạo. Chạy script trước reset dữ liệu của nhóm thanh toán/báo cáo và sau progress; kết quả V4 và các truy vấn mới chờ retest trong [báo cáo T19](../../tests/T19-validation.md).
