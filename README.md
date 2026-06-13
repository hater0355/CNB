# CHAT_NOI_BO

Ung dung chat noi bo JavaFX dung chung database `quanlyluong` voi app quan ly luong.

## Chay trong VS Code

1. Mo thu muc `C:\Users\Admin\OneDrive\Desktop\CHAT_NOI_BO`.
2. Chay cau hinh `Run CHAT_NOI_BO`.

Hoac compile thu cong:

```powershell
powershell -ExecutionPolicy Bypass -File .\compile.ps1
java --module-path ..\QUAN_LY_LUONG\javafx-sdk-26.0.1\lib --add-modules javafx.controls,javafx.fxml -cp "out;src;lib/*" chatapp.ChatApp
```

Chay nhanh bang script:

```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1
```

Hoac double-click `run.bat`.

## Dang xuat va cai dat

- Trong app, bam nut `...` tren the tai khoan o sidebar de mo menu tai khoan.
- Menu gom `Cai dat`, `Thong tin tai khoan`, `Dang xuat`.
- `Dang xuat` se hoi xac nhan va thoat app.
- Cai dat ca nhan duoc luu tai `%APPDATA%\CHAT_NOI_BO\user-settings.properties`.
- Cac cai dat hien co: bat/tat am bao, bat/tat thong bao noi, chon mau chu dao bang bang mau tron, chon nen khung chat, xem thu muc file va thong tin he thong.
- Co the chon avatar ca nhan trong `Cai dat`; avatar duoc luu cuc bo theo tung tai khoan.
- Trong khung soan tin, cac nut duoi cung la icon-only; dua chuot vao nut de xem tac dung.
- Nut `+` trong khung soan tin gom: gui file, gui anh/video, tao vote.
- Nut icon mat cuoi mo bang emoji theo nhom: cam xuc, phan hoi nhanh, cong viec, khong khi.
- Moi tin nhan co nut `...` de tra loi, sua, thu hoi, ghim/bo ghim hoac chuyen tiep.
- Khi co tin moi tu hoi thoai khac, app hien avatar noi dang chat head o goc duoi phai; bam vao avatar de mo hoi thoai do.

## Cau hinh

File cau hinh nam o `src\chatapp\chat.properties`.

- Neu MySQL `root` co mat khau, cap nhat `db.password`.
- Khi co thu muc chia se LAN, doi:

```properties
chat.files.root=\\\\SERVER\\CompanyChatFiles
```

Mac dinh hien tai luu file demo vao `chat-files` trong thu muc app.

## Quyen truy cap

- Admin dang nhap duoc bang tai khoan cong ty.
- Nhan vien chi dang nhap chat khi ho so trong `employees` co `status = APPROVED`.
- Admin va Truong phong duoc tao/quan ly nhom.
- Nhom `Toan cong ty` duoc tao/dong bo tu dong theo cong ty.

## File dinh kem

- Anh toi da 10MB.
- Video toi da 100MB.
- Tai lieu/file nen toi da 50MB.
- Chan cac file nguy hiem: `.exe`, `.bat`, `.cmd`, `.com`, `.scr`, `.ps1`, `.vbs`, `.js`, `.jar`, `.msi`.
