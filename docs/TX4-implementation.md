# TX4 — Điều phối theo vị trí tài xế mô phỏng và gán đơn thật

Nhánh `feat/tx4-driver-location-dispatch` phát triển từ develop `1bccb67` sau TX3. Module bổ sung `/dispatcher/live` và `/driver/assigned` có hàng đợi, tạo đơn hộ, chi tiết và gán đọc API thật; không dùng danh sách mẫu trong bộ nhớ FE. Giữ nguyên giao diện các trang dispatcher/driver gốc, thêm lối vào và nhãn phân biệt dữ liệu mẫu. Giữ báo giá km/kg, lựa chọn người trả/phương thức trả và lịch sử TX3.

## Vị trí dùng chung và Goong

Migration **V6** chỉ bổ sung 5 cột nullable trên `tai_xe`: `dia_chi_vi_tri_demo`, `vi_do_demo`, `kinh_do_demo`, `nguon_vi_tri`, `vi_tri_cap_nhat_luc`. Không sửa V1–V5, không thêm bảng và không đổi quan hệ ERD. CHECK yêu cầu tất cả NULL hoặc toàn bộ hợp lệ, tọa độ trong giới hạn, nguồn `DEMO_CO_DINH`. Tài xế cũ giữ NULL: không tự gắn địa chỉ/tọa độ chưa kiểm chứng.

`POST /api/drivers/{maTx}/demo-location` chỉ `TONG_DAI`, cần session và CSRF; body chỉ `{ "diaChi": "địa chỉ đầy đủ" }`. BE geocode Goong bên ngoài transaction, sau đó kiểm tra lại quyền và khóa tài xế để lưu địa chỉ chuẩn hóa, tọa độ và thời điểm. Không nhận tọa độ từ FE. Lookup lỗi/thiếu key không ghi thay đổi. Nhiều phiên dùng cùng bản ghi SQL. Đây là vị trí mô phỏng cố định, **không phải GPS trực tiếp**.

`GET /api/orders/{maDon}/driver-suggestions` chỉ cho đơn `CHO_GAN`, lọc ONLINE, có thời gian rảnh, tài khoản TAI_XE hoạt động, xe hoạt động, GPLX chưa hết hạn và chưa từ chối đơn. Geocode điểm lấy một lần; Directions lấy tọa độ tài xế đã lưu làm origin, điểm lấy làm destination. Xếp toàn bộ ứng viên theo mét đường đi tăng dần, hòa theo `maTx`, rồi mới phân trang. Trả km 3 chữ số thập phân, giây, `nguonLoTrinh=GOONG`, `cachSapXep=GOONG_DEN_DIEM_LAY`.

Không giữ transaction SQL khi gọi bản đồ. Sau routing, BE đọc lại quyền, trạng thái đơn, điều kiện/vị trí ứng viên. Nếu snapshot đổi, trả danh sách đủ điều kiện mới với nhãn dự phòng. Gán vẫn kiểm tra lại trong transaction; gợi ý không phải giữ chỗ.

Thiếu key/vị trí, lỗi/quota, timeout, hơn 50 ứng viên hoặc snapshot đổi: **toàn bộ** danh sách dùng `THOI_GIAN_RANH_DU_PHONG`, có `lyDoDuPhong`, km/giây/nguồn tuyến NULL. Không trộn tuyến tính thành công với kết quả dự phòng và không gọi kết quả này “gần nhất theo Goong”. Giới hạn ngân sách routing 15 giây, kiểm tra giữa các lời gọi; một lời gọi đang chạy còn bị chặn bởi timeout Goong cấu hình. Không có ứng viên: `KHONG_CO_TAI_XE`.

Goong dùng `GOONG_VEHICLE` (mặc định `car`): tuyến ô tô là ước tính, chưa khẳng định tuyến xe máy. Không cộng đường từ tài xế tới điểm lấy vào cước giao đã chốt của khách.

## Gán và quyền xem

`POST /api/orders/{maDon}/assignments` giữ transaction khóa đơn → tài xế → tài khoản/xe, kiểm tra lại ONLINE/rảnh/tài khoản/GPLX/xe/lịch sử từ chối. Unique index designV1 chặn phân công đang mở trùng đơn hoặc tài xế. Tranh chấp trả 409; FE đọc lại đơn/gợi ý, không tự gửi lại lệnh gán. Hủy đồng thời dùng cùng khóa đơn, đóng phân công và trả tài xế về rảnh atomically.

Trang `/driver/assigned` chỉ lấy `DA_GAN` từ API theo session. Chi tiết/list API kiểm tra tài xế hiện phụ trách; người không được gán hoặc người đã từ chối không thấy đơn đó (404). Phần chuyển trạng thái/thu tiền của TX5 không nằm trong TX4 này.

## Khởi động trên VS Code (Git Bash, từ gốc repo)

```bash
git switch feat/tx4-driver-location-dispatch
git pull --ff-only
java -version
mvn -version
```

Dùng JDK 21. Nếu chưa có `.env`, sao chép `.env.example` và **Ctrl+S** sau khi sửa. Không ghi mật khẩu vào file theo dõi bởi Git. Trong `.env` gốc repo đặt:

```dotenv
MSSQL_DATABASE=mini_ong_vang_design_v1
MSSQL_SA_PASSWORD=<mat-khau-SQL-Server-dang-dung>
DEMO_SEED=true
DEMO_PASSWORD=<mat-khau-dang-nhap-demo-rieng>
ROUTE_PROVIDER=fake
GOONG_API_KEY=
GOONG_TIMEOUT_MS=5000
GOONG_VEHICLE=car
```

Dấu `<...>` là chỗ thay bằng giá trị riêng; mật khẩu SQL và đăng nhập ứng dụng là hai cấu hình khác nhau. DB đã có volume thì giữ mật khẩu SQL cũ. Seed có manifest; đổi DEMO_PASSWORD không tự đổi mật khẩu tài khoản đã seed.

Bạn chưa có key thì vẫn chạy được thứ tự dự phòng. Muốn xác minh vị trí: tạo **REST API key** tại https://account.goong.io, đặt riêng trong `.env` ở backend, không dùng `NEXT_PUBLIC_*`, không gửi key vào chat. Có thể giữ `ROUTE_PROVIDER=fake` để tạo đơn fixture; TX4 dùng Goong riêng khi có key. Muốn đơn có điểm lấy thật và tuyến giao thật thì đổi `ROUTE_PROVIDER=goong` sau khi có key.

Biến đã export trong terminal ưu tiên hơn `.env`. Nếu từng export giá trị cũ:

```bash
unset DEMO_SEED DEMO_PASSWORD GOONG_API_KEY ROUTE_PROVIDER
docker compose up --build -d backend
curl --fail http://127.0.0.1:8081/api/health
```

Health mong đợi `database=connected`, `databaseName=mini_ong_vang_design_v1`. Backend tự chạy Flyway V6. Không dùng `down -v` để sửa đăng nhập: lệnh đó xóa dữ liệu.

Terminal frontend:

```bash
npm ci --prefix src/frontend
npm run dev --prefix src/frontend
```

Mở URL được Next in ra (thường cổng 3000). Trang đăng nhập chung: username `demo_dispatcher`, mật khẩu là **giá trị DEMO_PASSWORD đã dùng seed**, không phải chữ `DEMO_PASSWORD`. Đăng nhập thành công đi `/dispatcher`. Chọn **Điều phối thật · TX4** để mở `/dispatcher/live`.

## Kiểm tra giao diện và dữ liệu

1. `/dispatcher/live/fleet`: mỗi tài xế chưa cấu hình có nhãn rõ. Bấm **Cấu hình vị trí**, nhập địa chỉ thực đầy đủ và **Xác minh Goong và lưu**. Chỉ HTTP 200 mới có tọa độ, `DEMO_CO_DINH`, thời điểm. Thử địa chỉ ứng viên như `1 Công xã Paris, Bến Nghé, Quận 1, Thành phố Hồ Chí Minh` hoặc `2 Hải Triều, Bến Nghé, Quận 1, Thành phố Hồ Chí Minh`; các địa chỉ này **chưa được xác minh trên môi trường hiện tại**, phải kiểm tra kết quả chuẩn hóa Goong trước khi dùng trình diễn. Cấu hình từng tài xế khả dụng; một người thiếu vị trí khiến cả danh sách dùng dự phòng.
2. Mở phiên tổng đài thứ hai: làm mới đội xe, cùng địa chỉ/tọa độ/thời điểm. Thiếu key trả 503; địa chỉ không tìm thấy 422; lỗi/quota 502; timeout 504. Không có thông báo đã lưu khi lỗi.
3. Tạo đơn hộ với khách thật trong DB; báo giá trước, kiểm tra bên/phương thức trả, rồi tạo đơn. Đơn mới xuất hiện trong **Chờ gán**. Khi dùng `fake`, chọn `Điểm mẫu A` → `Điểm mẫu B` hoặc `Quận 1` → `Quận 3`; không nhập địa chỉ bất kỳ rồi coi tuyến fixture là thật.
4. Mở chi tiết, **Tìm tài xế khả dụng**. Đủ vị trí/key và điểm lấy geocode được: nhãn Goong, km/giây tới điểm lấy. Thiếu key/lỗi: nhãn dự phòng và nguyên nhân. Chưa chọn radio thì **Xác nhận gán** bị khóa. Không có tài xế thì không có nút gán.
5. Chọn tài xế → gán: trạng thái `DA_GAN`, mã tài xế và lịch sử phân công từ BE. Tải lại vẫn giữ kết quả. Tài xế vừa bận/khóa/hết GPLX/từ chối bị BE chặn 409.
6. Hai phiên cùng gán một đơn hoặc hai đơn cùng một tài xế: một thành công, một 409, chỉ một phân công mở. Hủy có lý do trong 5 phút giải phóng tài xế; gán/hủy đồng thời không để phân công mở trên đơn đã hủy.
7. Đăng nhập `0800000001`/`0800000002` bằng mật khẩu demo: mở **Đơn được gán từ SQL Server** (`/driver/assigned`), chỉ người được gán thấy đơn `DA_GAN`; người kia gọi chi tiết trả 404. Không dùng các trang ví/lịch sử demo cũ làm bằng chứng TX4.

Đối chiếu vị trí (không sửa dữ liệu):

```bash
MSYS_NO_PATHCONV=1 docker compose exec -T sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -b -d mini_ong_vang_design_v1 -Q "SELECT ma_tx,dia_chi_vi_tri_demo,vi_do_demo,kinh_do_demo,nguon_vi_tri,vi_tri_cap_nhat_luc FROM dbo.tai_xe;"
```

Lệnh test và trạng thái bằng chứng tại [TX4-validation](../tests/TX4-validation.md).
