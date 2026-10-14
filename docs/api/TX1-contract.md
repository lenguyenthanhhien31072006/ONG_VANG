> **Tiến độ TX4:** contract chạy thật v0.7.0 đã triển khai vị trí tài xế và gợi ý/gán TX4 cùng TX3; phần TX5–TX7 bên dưới vẫn là thiết kế đích. Xem [TX4](../TX4-implementation.md) và OpenAPI để biết các field/endpoint hiện chạy.

# TX1 — Hợp đồng API đích cho các task TX

**Trạng thái:** bản thiết kế ngày 14/10/2026, tất cả thay đổi dưới đây **chưa được triển khai**. [`openapi.json`](../../src/backend/src/main/webapp/openapi.json) vẫn là nguồn chính xác cho API đang chạy (v0.5.0); không thêm field/endpoint đích vào đó trước code vì Swagger/Postman và test sẽ mô tả sai runtime. Khi TX3–TX7 triển khai từng nhóm, cập nhật OpenAPI chạy thật, examples, Postman và test trong cùng PR; lúc đó chuyển từng operation sang `implemented`. Nghiệp vụ/ERD đích ở [designTX](../database/designTX.md).

## Quy ước chung

- JSON camelCase tiếng Việt không dấu, ID opaque, số tiền/khối lượng/km là **chuỗi thập phân**, giờ UTC ISO 8601. Thành công trả DTO trực tiếp; lỗi theo envelope `code`, `message`, `fieldErrors`, `traceId` của [API conventions hiện tại](conventions.md). Cookie session + `X-CSRF-Token` cho thao tác ghi. `Idempotency-Key` bắt buộc cho tạo đơn, mở yêu cầu thu cước, tạo batch COD và lần thử VNPay; retry sau timeout dùng **cùng key**.
- `benThanhToan`: `NGUOI_DAT` hoặc `NGUOI_NHAN`. `NGUOI_DAT` nghĩa là **khách sở hữu đơn**, kể cả tổng đài bấm tạo hộ. `phuongThucThanhToan`: `TIEN_MAT` hoặc `VNPAY_QR`. Hai field được lưu bất biến trên đơn, không tin FE gửi một bên/phương thức khác khi thu.
- Quyền của API chỉ là lớp đầu: BE còn phải kiểm tra chủ đơn, đúng tài xế đang phụ trách, đúng mốc thu, đúng batch/tài xế và trạng thái dưới khóa DB. Tên quyền `TONG_DAI` bao gồm điều phối viên; `CHU_DOI_XE` là quản trị báo cáo. Không cho trình duyệt gửi cờ `daThanhToan`, phần trăm chia, `maTx` tự nhận hoặc số tiền do FE tự tính.

## Contract cho địa chỉ, báo giá và đơn

| Operation | Thay đổi đích và quyền |
| --- | --- |
| `POST /api/routes/estimate` (`estimateRoute`) | Giữ hai chuỗi địa chỉ đầy đủ `diemLayHang`, `diemGiaoHang`, không nhận quận riêng. BE trả địa chỉ chuẩn, km, thời gian, nguồn và tọa độ hai điểm để vẽ map; `ADDRESS_NOT_FOUND`, `OUT_OF_SERVICE_AREA`, provider 502/504. Goong key vẫn ở BE. |
| `POST /api/quotes` (`createQuote`) | Request giữ `maKh` tùy vai trò, hai địa chỉ và `kienHang[]` với `khoiLuongKg`, thêm `benThanhToan`, `phuongThucThanhToan` để khóa snapshot quote. Tổng đài bắt buộc chọn `maKh`; khách không được chọn người khác. Response thêm `tongKhoiLuongKg`, `cuocKhoiLuong` và breakdown km/kg/phụ thu/giảm/tổng; TTL 300 giây giữ nguyên. Sửa bất kỳ đầu vào nào phải tạo quote mới. |
| `POST /api/orders` (`createOrder`) | Request thêm hai field bên/phương thức, phải khớp quote cùng khách/tuyến/kiện/kg; `Idempotency-Key` giữ nguyên. BE chốt cước và trả `Order` với hai lựa chọn này, trạng thái `CHO_GAN`, tọa độ và payment summary `MIEN_CUOC` hoặc `CHUA_THANH_TOAN`. Không nhận `tongCuoc` từ FE. |
| `GET /api/orders`, `GET /api/orders/{maDon}`, `GET /api/orders/{maDon}/events` | Giữ phân quyền hiện có; Order trả `benThanhToan`, `phuongThucThanhToan`, breakdown có cước kg, `maTx` suy từ phân công và trạng thái mới `DA_DEN_DIEM_GIAO`. Lịch sử event theo `thuTu`. `thanhToan.trangThai=DA_THANH_TOAN` có thể xuất hiện **trước** `HOAN_TAT`; không suy trạng thái giao hàng từ payment. |

```json
{
  "maBaoGia": "<id-tu-BE>",
  "diemLayHang": "<dia-chi-day-du>",
  "diemGiaoHang": "<dia-chi-day-du>",
  "sdtNguoiNhan": "0901234567",
  "kienHang": [{ "loaiHangHoa": "Tài liệu", "khoiLuongKg": "2.50" }],
  "benThanhToan": "NGUOI_NHAN",
  "phuongThucThanhToan": "VNPAY_QR"
}
```

Ví dụ là payload `createOrder` đích, chưa gửi được tới API hiện tại. Field `maKh` chỉ thêm khi tổng đài tạo hộ.

## Contract cho gợi ý và tiến trình tài xế

| Operation | Thay đổi đích và quyền |
| --- | --- |
| `GET /api/drivers`, `GET /api/orders/{maDon}/driver-suggestions` | Chỉ `TONG_DAI`. Driver/đề xuất trả địa chỉ vị trí demo, `nguonViTri=DEMO_CO_DINH`, `viTriCapNhatLuc`, `quangDuongDenDiemLayKm`, `thoiGianDenDiemLayGiay`, `nguonLoTrinh`. Đề xuất lọc tài xế đủ điều kiện trước rồi xếp theo **km đường đi đến điểm lấy**, hòa điểm theo `maTx`; nếu provider lỗi, trả lỗi hoặc fallback được gắn nhãn, không gắn nhãn GOONG giả. |
| `POST /api/orders/{maDon}/assignments`, `POST /api/orders/{maDon}/reject` | Giữ quyền/chống tranh chấp hiện có. Sau gán chỉ tài xế được gán thấy đơn; từ chối có lý do chỉ ở `DA_GAN`. |
| `POST /api/orders/{maDon}/transitions` | Giữ body `{ "trangThai": "..." }`, thêm `DA_DEN_DIEM_GIAO`. BE chỉ cho cạnh trong [bảng trạng thái](../database/designTX.md#2-trạng-thái-đơn-quyền-và-điều-kiện-chuyển); khi người đặt trả, chặn `DANG_GIAO` nếu chưa thu đủ; khi người nhận trả, chặn `HOAN_TAT` nếu chưa thu đủ. Lỗi 409 `ORDER_STATE_CONFLICT` hoặc `PAYMENT_NOT_CONFIRMED`; 403 sai vai trò, 404 sai tài xế. |
| `GET /api/orders/{maDon}/navigation` **mới** | `TAI_XE` đang/đã thực hiện đơn hoặc `TONG_DAI`. Trả vị trí demo tài xế, điểm lấy/giao, hai polyline/route với km/thời gian và nguồn; không trả REST Goong key. Nếu không có vị trí/map, trả lỗi rõ hoặc dữ liệu không có tuyến, không nói đang dùng GPS. |

## Contract thu cước của khách ở điểm lấy/điểm giao

Các operation payment hiện tại chỉ cho **khách khởi tạo sau `HOAN_TAT`**. TX6 phải thay quyền và precondition; đây là thay đổi hành vi, không chỉ thêm field. `POST /api/orders/{maDon}/payments` tiếp tục là payment **cước của đơn**, không dùng cho nộp COD.

| Operation | Request, quyền và response đích |
| --- | --- |
| `POST /api/orders/{maDon}/payments` (`createPayment`) | `TAI_XE` đang được gán, tại `DA_LAY_HANG` nếu `NGUOI_DAT`, tại `DA_DEN_DIEM_GIAO` nếu `NGUOI_NHAN`; `Idempotency-Key`. Body rỗng `{}`; BE lấy phương thức và số tiền từ đơn. Response `Payment` giữ `maGiaoDich`, `soTien`, `trangThai`, `paymentUrl` cho VNPay hoặc NULL cho tiền mặt, thêm `mocThu`/`benThanhToan`. Không cho tạo nếu miễn cước, đã thu đủ hoặc có attempt chưa rõ/chờ. |
| `POST /api/payments/{maGiaoDich}/confirm-cash` | Chỉ tài xế đang phụ trách đúng đơn và đúng mốc thu, payment `TIEN_MAT`; body `soTienDaThu`. Đúng đủ thì payment `THANH_CONG`, ghi người/tgian thu, tạo xác nhận thu đủ và nghĩa vụ giữ hộ. Sai tiền giữ bằng chứng cần đối soát, **không mở khóa bước giao tiếp theo**. Không cho tổng đài tự giả làm tài xế ở luồng bình thường; nếu cần sửa ngoại lệ, dùng nghiệp vụ đối soát riêng có audit. |
| `GET /api/payments/{maGiaoDich}`, `GET /api/orders/{maDon}/payments` | Chủ đơn, tài xế có quan hệ hợp lệ, tổng đài được đọc. Trả `soTienThucNhan`, `daXacNhanDu`, `canDoiSoat` để FE tài xế làm mới sau VNPay Return/IPN; không tin thông báo Return của trình duyệt. |
| `POST /api/payments/{maGiaoDich}/reconcile` | Tài xế phụ trách, chủ đơn hoặc tổng đài theo quyền; chỉ attempt VNPay chưa xác định. Gọi QueryDr, ghi event xác thực; chưa rõ thì giữ `CHUA_RO_KET_QUA`, không tạo attempt mới. |
| `GET /api/payments/vnpay/ipn`, `GET /api/payments/vnpay/return` | Giữ một IPN URL công khai đã đăng ký với VNPay. BE định tuyến reference tiền cước (`FREIGHT-` hoặc namespace legacy) và nộp COD (`COD-`) tới đúng attempt. IPN/Query có chữ ký, merchant, tham chiếu, số tiền, trạng thái hợp lệ mới đổi DB; Return chỉ hiển thị/đưa về màn phù hợp. Không yêu cầu session ở callback. |

## Contract nộp COD từ giao diện điều phối

| Operation mới | Request, quyền và response đích |
| --- | --- |
| `GET /api/cod/drivers` | `TONG_DAI`, phân trang/lọc tài xế. Mỗi dòng: `maTx`, tên, `soTienDangGiu`, `soTienDuDieuKienNop`, `soTienCanDoiSoat`, số đơn; không lấy tổng từ FE. Khoản trên đơn chưa hoàn tất/có sự cố hiển thị riêng, chưa vào batch chính. |
| `POST /api/cod/drivers/{maTx}/settlements` | `TONG_DAI` đang đăng nhập bấm trong tab đối soát; `Idempotency-Key`, body `{}`. BE khóa/snapshot **toàn bộ khoản tiền mặt đủ điều kiện đang giữ** của tài xế được chọn, tạo `dot_nop_cod` và lần thử VNPay; response `{maDot, maTx, soTien, trangThai, paymentUrl}`. FE điều hướng **ngay** đến `paymentUrl`. Không có bước đăng nhập/thao tác trên FE tài xế. Batch rỗng trả 409 `COD_EMPTY`; batch đang chờ trả lại attempt hiện tại hoặc 409 `SETTLEMENT_PENDING`, không tạo batch trùng khoản. |
| `GET /api/cod/settlements/{maDot}` | `TONG_DAI` và `CHU_DOI_XE` đọc; trả batch, khoản thành phần, attempt, trạng thái `CHO_XU_LY`/`CHUA_RO_KET_QUA`/`THANH_CONG`/`THAT_BAI`/`CAN_DOI_SOAT`. Sau Return, FE điều phối đọc endpoint này; chỉ BE xác minh IPN/Query mới trả đã nộp. |
| `POST /api/cod/settlements/{maDot}/reconcile` | `TONG_DAI`, CSRF; QueryDr cho attempt chưa rõ, idempotent. Nếu xác minh đủ thì đánh dấu batch đã nộp và giải phóng nghĩa vụ tiền mặt của đúng các khoản snapshot; không tăng lại `tienThucThuCuaKhach`. |

Để Return quay lại đúng màn, BE chỉ redirect tới URL FE điều phối cố định được cấu hình, kèm **mã batch opaque**; không nhận URL tùy ý từ request/Return để tránh open redirect. FE đọc lại batch sau khi trở về. Vì giao dịch mở từ phiên điều phối, trạng thái thành công chỉ chứng minh **khoản COD gắn tài xế đó được thanh toán trong Sandbox**, không chứng minh chính tài xế thao tác hoặc có chuyển tiền thật. VNPay yêu cầu IPN URL công khai qua HTTPS; localhost không nhận IPN từ cổng. [Tài liệu VNPay PAY/IPN/Return](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html).

## Contract phần chia và báo cáo

| Operation | Dữ liệu đích |
| --- | --- |
| `GET /api/reports/revenue`, `/export` | Giữ `tuNgay`, `denNgay`, `nhomTheo`. Bổ sung `phanCongTy`, `phanTaiXe`, `tienMatTaiXeDangGiu`, `tienNopCod`/`tienNopCodTrongKy` và giải thích mốc tính; `tienThucThu` chỉ là payment cước khách trả, không cộng batch COD. Excel dùng đúng query/định nghĩa của JSON. |
| `GET /api/reports/drivers` **mới** | `CHU_DOI_XE`, cùng bộ lọc ngày/kỳ; mỗi tài xế có `tongCuocDaHoanTat`, `phanTaiXePhatSinh`, `phanTaiXeDuDieuKienChiTra`, `tienMatDangGiu`, `tienCodDaNop`. `phanTaiXeDuDieuKienChiTra` không có nghĩa đã chuyển khoản. |
| `GET /api/drivers/me/earnings` **mới** | `TAI_XE`, chỉ chính mình; trả các chỉ tiêu thu nhập theo kỳ từ BE để thay `walletBalance`/“tổng cước hôm nay” demo. Không có API rút tiền ở phạm vi TX. |

**Mốc báo cáo:** tiền khách trả theo `thuLuc` của payment cước; tiền nộp COD theo IPN xác nhận batch; giá trị đơn theo `hoanTatLuc`; 20/80 theo `ghiNhanLuc = max(hoanTatLuc, thuLucDuocXacNhan)` khi cả hai điều kiện có thật. Công nợ cuối kỳ chỉ tính đơn đã hoàn tất trước cuối mốc và chưa được xác nhận thu đủ trước mốc. Không lấy trạng thái hiện tại để viết lại lịch sử kỳ trước.

## Ma trận lỗi và kiểm thử contract

| Tình huống | Phản hồi/điều kiện đích |
| --- | --- |
| Địa chỉ không nhận diện, ngoài vùng, Goong timeout | 422 `ADDRESS_NOT_FOUND`/`OUT_OF_SERVICE_AREA`, 504 `ROUTE_PROVIDER_TIMEOUT`; không tự thay bằng khoảng cách FE cứng. |
| Quote khác địa chỉ/kg/bên trả/phương thức hoặc hết hạn | 409 `QUOTE_CHANGED`/`QUOTE_EXPIRED`, không tạo đơn nửa chừng. |
| Tài xế khác hoặc tài xế chỉ từng từ chối truy cập đơn | 404 theo quy tắc che tồn tại; 403 cho vai trò sai. |
| Thu cước sai mốc/phương thức hoặc đơn miễn cước | 409 `PAYMENT_NOT_DUE`/`PAYMENT_METHOD_CONFLICT`, 422 `PAYMENT_NOT_REQUIRED`; không có giao dịch giả. |
| Chuyển `DANG_GIAO`/`HOAN_TAT` khi payment còn chờ/chưa rõ | 409 `PAYMENT_NOT_CONFIRMED`; trạng thái và event không đổi. |
| VNPay Return báo thành công nhưng IPN chưa đến | Vẫn `CHO_XU_LY` hoặc `CHUA_RO_KET_QUA`; FE chờ/đối soát, không mở khóa. |
| IPN lặp, khác tiền, đến muộn hoặc hai khoản thu thành công | Lưu bằng chứng; tối đa một xác nhận đủ/batch thành công/phần chia; khoản bất thường `CAN_DOI_SOAT`. |
| Tạo COD batch trùng khoản hoặc khi không có tiền đủ điều kiện | 409 `SETTLEMENT_PENDING`/`COD_EMPTY`; retry cùng key trả batch gốc. |
| Batch COD thành công | Chỉ giảm nghĩa vụ tiền mặt và mở điều kiện chi trả 80%; **không** tăng lại tiền cước khách trả. |

Các mã lỗi mới ở đây là contract đích. Người triển khai phải thêm schema/examples và `x-error-codes` tương ứng vào OpenAPI thật cùng test, không chỉ trả HTTP 409 chung chung.
