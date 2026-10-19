# XP Tool

Ứng dụng Android hỗ trợ kỹ thuật viên kiểm tra mạng, kết nối và cấu hình máy in Xprinter. Ứng dụng dùng thư viện `printer-lib-3.2.0.aar` có sẵn trong `app/libs/`.

## Tính năng

### Kết nối và kiểm tra mạng

- Tìm máy in trong mạng LAN/Wi-Fi bằng UDP broadcast; hiển thị IP, subnet mask, gateway, MAC và DHCP.
- Quét nhiều dải IP để tìm lại máy in đã lưu và thử đối chiếu địa chỉ MAC qua SNMP.
- Kiểm tra thông tin mạng trên điện thoại, thử kết nối máy in và hỗ trợ kết nối qua LAN/Wi-Fi, Bluetooth Classic hoặc USB OTG.
- Quét thiết bị Bluetooth Classic ở gần. Máy in cần bật chế độ ghép đôi hoặc cho phép thiết bị khác tìm thấy; thiết bị Bluetooth đang ẩn sẽ không hiện trong kết quả.
- Đổi IP, subnet mask, gateway, DHCP theo MAC và gửi cấu hình Wi-Fi cho máy in có hỗ trợ.

### Thiết lập và in

- Thiết lập mẫu hóa đơn và tem, rồi in thử.
- Mở PDF hoặc ảnh đơn hàng để xem trước và in. Có thể chọn vùng giấy trước khi chọn tệp: cuộn K58/K80, tem 40×30 mm, 30×40 mm, tem vận đơn 100×150 mm hoặc tự nhập kích thước.
- Nội dung PDF/ảnh được thu phóng, căn giữa và giữ nguyên tỉ lệ trong vùng đã chọn. Mật độ khoảng 8 dot/mm chỉ là ước lượng; khổ in thực tế phụ thuộc đầu in. Máy cần hỗ trợ kích thước tem đã chọn.
- In mẫu hóa đơn, mã vạch 1D và QR; hỗ trợ lệnh in tem TSPL trên máy tương thích.
- Cấu hình một số thông số máy in như mật độ in, độ rộng, tiếng bíp và cảnh báo.

### Tiện ích

- Cầu nối in qua mạng cho máy in đang kết nối điện thoại bằng Bluetooth hoặc USB OTG.
- Tự động in từ một số thông báo được hỗ trợ.
- Đọc và chia sẻ bản ghi chấm công qua Wi-Fi/LAN trên thiết bị tương thích.
- Gửi gói HEX/TCP/UDP và nghe dữ liệu UDP để hỗ trợ chẩn đoán.

## Bluetooth

Mở **Chọn giao tiếp kết nối** và chọn **Bluetooth** để ứng dụng quét thiết bị ở gần trong tối đa 15 giây. Cấp quyền Bluetooth khi Android yêu cầu. Nếu không thấy máy in, bật chế độ ghép đôi/có thể tìm thấy trên máy in rồi nhấn **Quét lại thiết bị Bluetooth gần đây**.

Quét Bluetooth Classic chỉ tìm thấy thiết bị đang cho phép phát hiện. Một số máy in yêu cầu ghép đôi trong Cài đặt Android trước khi kết nối; việc hiện trong kết quả quét không đảm bảo máy in tương thích với giao thức của ứng dụng.

## In PDF và ảnh

Vào **In PDF / ảnh**, chọn khổ giấy hoặc vùng in trước, sau đó chọn PDF hay ảnh. Với tem vận đơn từ Shopee/TikTok, chọn **Tem vận đơn 100 × 150 mm** nếu máy in hỗ trợ. Có thể xem trước kết quả và chọn trang cần in trong PDF nhiều trang.

Kích thước dot được quy đổi theo ước lượng 8 dot/mm (đầu in nhiệt phổ biến 203 dpi). Lề không in được và độ rộng thực tế khác nhau tùy mẫu máy; hãy kiểm tra bản xem trước và in thử trước khi in số lượng lớn. Giấy K58/K80 là cuộn liên tục, không tương đương với máy in tem khổ 100×150 mm.

## Build

### Android Studio

1. Mở thư mục dự án trong Android Studio.
2. Đồng bộ Gradle rồi chọn **Run** hoặc chạy tác vụ `assembleDebug`.

Yêu cầu: JDK 17, Gradle 8.7 và Android SDK theo cấu hình trong `app/build.gradle.kts`.

### GitHub Actions

Workflow `.github/workflows/build.yml` chạy khi push hoặc khi được kích hoạt thủ công. Kết quả được tải lên dưới dạng artifact `xptool-debug-apk` và `xptool-playstore-aab`.

## Giao thức tìm máy in UDP

- Tìm: gửi ASCII `XP0001FIND` broadcast tới UDP 9000. Máy in trả lời bằng chuỗi bắt đầu `XP0001FOUND`, sau đó là MAC (offset 11), IP (19), subnet mask (23), gateway (27) và cờ DHCP (offset 33).
- Đổi IP: gửi broadcast UDP 9000 với dữ liệu `XP0001SAVE` + MAC (6 byte) + `22 00` + IP (4 byte) + subnet mask (4 byte) + gateway (4 byte) + `8C` + `17` + DHCP (`01` hoặc `00`).

Các lệnh và tính năng chỉ hoạt động với máy in hỗ trợ giao thức tương ứng. Thư viện trong `app/libs/` do Xprinter cung cấp; hãy xem điều khoản của hãng nếu phát hành ứng dụng cho người khác.
