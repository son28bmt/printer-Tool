# XP Tool (Android) v0.2

Tool cấu hình máy in Xprinter từ điện thoại, dùng thư viện chính hãng `printer-lib-3.2.0.aar`
(đã để sẵn trong `app/libs/`). Lưu ý: thư viện thuộc về Xprinter, xem điều khoản của hãng
nếu định phát hành app cho người khác. Dùng cá nhân để thử thì không sao.

## Tính năng
1. **Tìm máy in** trong mạng: broadcast UDP cổng 9000, hiện IP, mask, gateway, MAC, DHCP (giống nút Refresh trên PC)
2. **Đổi IP/Netmask/Gateway/DHCP** theo MAC qua UDP (giống Set New IP trên PC). Máy in khác dải mạng vẫn đổi được vì dùng broadcast
3. **Cấu hình WiFi** (SSID, mật khẩu, kiểu mã hóa) cho máy in có module WiFi
4. In thử, cắt giấy, mở két (ESC/POS qua TCP 9100)
5. Gửi gói hex thô TCP/UDP, nghe UDP (để dò giao thức, ví dụ lệnh reset)

Chưa có: **Restore factory (reset)**. SDK không có hàm này, cần bắt gói tin từ tool PC.

## Build
**Android Studio:** File > Open thư mục này, đợi sync, Run.
**GitHub Actions:** đẩy cả thư mục (kể cả `.github` và `app/libs`) lên repo, vào Actions > Build APK,
tải artifact `xptool-debug-apk`.

## Quy trình cho máy in đang ở 192.168.4.2
1. Cắm máy in vào router bằng dây LAN, điện thoại nối WiFi của cùng router.
2. Mở app, bấm **Tìm máy in** -> chọn máy in trong danh sách.
3. Điền IP mới (cùng dải router, chưa ai dùng), netmask, gateway -> **Đổi IP**.
4. Đợi vài giây (hoặc tắt mở lại máy in), bấm **Tìm máy in** lại để kiểm tra.
5. Mục 1: điền IP mới, **Test kết nối** rồi **In thử**.
6. (Nếu máy có WiFi) Mục 3: điền SSID, mật khẩu, kiểu mã hóa, bấm Gửi cấu hình WiFi, tắt mở lại máy.

## Giao thức tìm được trong thư viện (để tham khảo)
- Tìm: gửi ASCII `XP0001FIND` broadcast tới UDP 9000. Máy in trả lời chuỗi bắt đầu bằng `XP0001FOUND`,
  sau đó MAC (offset 11), IP (19), mask (23), gateway (27), cờ DHCP (offset 33).
- Đổi IP: gửi broadcast tới UDP 9000: `XP0001SAVE` + MAC(6) + `22 00` + IP(4) + mask(4) + gateway(4) + `8C` + `17` + (DHCP ? 01 : 00).
