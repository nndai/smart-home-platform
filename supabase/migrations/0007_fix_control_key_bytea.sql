-- ============================================================
-- 0007_fix_control_key_bytea.sql
-- Sửa lỗi dữ liệu control_key bị lưu nhầm dưới dạng chuỗi Base64
-- thay vì mảng byte nhị phân 32-byte.
-- ============================================================

-- Nếu control_key có độ dài 44 bytes (chiều dài của chuỗi Base64)
-- thì ta dịch ngược nó từ UTF8 -> Base64 -> Bytea (32 bytes)
UPDATE public.devices
SET control_key = decode(convert_from(control_key, 'utf8'), 'base64')
WHERE length(control_key) = 44;
