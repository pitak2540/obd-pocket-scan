# JAMES Diagnostic Solution

แอปสแกน OBD-II สำหรับ Android ใช้กับ ELM327 Bluetooth, ELM327 สาย USB ชิป FTDI และ Tactrix OpenPort 2.0

- `app/` โปรเจกต์ Android (WebView + สะพาน Bluetooth SPP และ USB host)
- `app/src/main/assets/index.html` ตัวแอปทั้งหมด (หน้าจอ ตัวขับ ELM327/OpenPort และตัวถอดรหัส OBD-II)
- `index.html` ไฟล์เดียวกัน สำหรับเปิดบนเว็บ (GitHub Pages) ด้วย Chrome

## บิลด์ APK

ทุกครั้งที่ push เข้า `main` GitHub Actions จะบิลด์ให้ ดาวน์โหลด `obd-pocket-scan.apk` ได้จากหน้า Releases

บิลด์เองบนเครื่อง: ติดตั้ง JDK 17, Android SDK และ Gradle 8.9 แล้วรัน `gradle assembleDebug`

## ข้อจำกัด

ยังไม่ได้ทดสอบกับหัวสแกนและรถจริง อ่านอย่างเดียวสำหรับระบบ immobilizer ไม่มี coding และการลงทะเบียนกุญแจ
