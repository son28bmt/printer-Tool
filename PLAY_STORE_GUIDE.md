# HƯỚNG DẪN ĐĂNG ỨNG DỤNG LÊN GOOGLE PLAY CONSOLE
**Ứng dụng: XP Tool by QuangSonAIBAT**

---

### 1. Thông tin trang cửa hàng (Store Listing)

- **Tên ứng dụng (App Title - Tối đa 30 ký tự):**
  `XP Tool by QuangSonAIBAT`

- **Mô tả ngắn (Short Description - Tối đa 80 ký tự):**
  `Công cụ cài đặt IP, WiFi, kết nối Bluetooth, USB OTG và test in máy in Xprinter & Zywell`

- **Mô tả chi tiết (Full Description):**
  ```text
  XP Tool by QuangSonAIBAT là ứng dụng công cụ chuyên nghiệp hỗ trợ chẩn đoán, cấu hình và kiểm tra hoạt động cho các dòng máy in nhiệt hóa đơn (ESC/POS) và máy in tem nhãn mã vạch (TSPL) như Xprinter, Zywell, Rongta, Bixolon...

  CÁC TÍNH NĂNG NỔI BẬT:
  1. Dò tìm máy in LAN & Đổi IP 1 chạm: Broadcast UDP cổng 9000, hỗ trợ đổi IP tĩnh, Netmask, Gateway và bật DHCP tự động.
  2. Quét dải IP mạng LAN siêu tốc: Tự động quét 254 IP cổng 9100 để phát hiện mọi máy in nhiệt trong mạng LAN (Zywell, Epson, Xprinter...).
  3. Cấu hình WiFi không dây: Tự động đọc tên WiFi điện thoại đang nối hoặc quét các sóng WiFi xung quanh để nạp SSID/Password vào máy in.
  4. Hỗ trợ đa giao tiếp: Kết nối linh hoạt qua Mạng LAN/WiFi, Bluetooth Classic và cổng USB OTG trực tiếp từ điện thoại.
  5. In mẫu Hóa đơn & Mã vạch QR: In phiếu tính tiền mẫu, mã vạch 1D CODE128, QR Code thanh toán, lệnh cắt giấy và mở két thu ngân.
  6. Máy in Tem nhãn TSPL: In tem nhãn mã vạch sản phẩm chuẩn kích thước dành cho máy in tem nhiệt (XP-350B, XP-365B, XP-420B...).
  7. Chuyển chế độ máy in (Dual-Mode): Chuyển đổi linh hoạt giữa chế độ In Tem (TSPL) và In Hóa Đơn (ESC/POS) bằng phần mềm.
  8. Khôi phục cài đặt gốc: Gửi lệnh Reset Factory về cấu hình mặc định nhà sản xuất.

  CAM KẾT BẢO MẬT:
  Ứng dụng hoạt động độc lập trên thiết bị của bạn, không thu thập, lưu trữ hay gửi bất kỳ dữ liệu cá nhân nào lên máy chủ bên thứ ba.
  ```

---

### 2. URL Chính sách bảo mật (Privacy Policy URL)

Khi đăng trên Google Play Console, điền liên kết sau vào ô **Privacy Policy URL**:
`https://raw.githubusercontent.com/son28bmt/printer-Tool/main/PRIVACY_POLICY.md`

*(Hoặc link GitHub Pages nếu bạn bật GitHub Pages trên Repo)*

---

### 3. An toàn dữ liệu (Data Safety Form)

- **Ứng dụng của bạn có thu thập hoặc chia sẻ bất kỳ loại dữ liệu người dùng nào được hỗ trợ không?**
  ➔ Chọn **Không (No)**.

- **Mục đích sử dụng các quyền:**
  - *Location (Vị trí):* Chọn "Chức năng ứng dụng (App Functionality)" - Dùng để quét các sóng WiFi & Bluetooth xung quanh. Không thu thập hay theo dõi vị trí.

---

### 4. File cài đặt để tải lên Google Play (App Bundle .aab)

Sau khi GitHub Actions build xong, truy cập tab **Actions** trên GitHub Repo:
Tải file `xptool-playstore-aab` ➔ Giải nén lấy file **`app-debug.aab`** (hoặc `.aab` release) để tải thẳng lên Google Play Console!
