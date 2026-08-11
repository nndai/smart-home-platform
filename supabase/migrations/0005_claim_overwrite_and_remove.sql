-- ============================================================
-- 0005_claim_overwrite_and_remove.sql
-- IDEMPOTENT — an toàn chạy nhiều lần, không gây lỗi.
--
-- 1) claim_device: ghi đè quyền sở hữu khi thiết bị đã active.
--    - Cập nhật owner_id, control_key cho chủ mới.
--    - Tạo dòng device_members OWNER cho chủ mới.
--    - Cập nhật dòng device_members của chủ cũ thành 'TRANSFERRED'.
--
-- 2) remove_device: xóa dòng device_members của user hiện tại.
--    Nếu user đó là owner → xóa luôn thiết bị khỏi bảng devices.
--    Dùng khi chủ cũ (role=TRANSFERRED) bấm xóa để dọn rác DB.
--
-- Cách dùng: Supabase → SQL Editor → dán toàn bộ → Run.
-- ============================================================

-- ── Thêm 'TRANSFERRED' vào CHECK constraint của device_members.role ──
-- Luôn DROP rồi ADD lại — hành động giống hệt nhau dù chạy lần 1 hay lần 100.
ALTER TABLE public.device_members
    DROP CONSTRAINT IF EXISTS device_members_role_check;

ALTER TABLE public.device_members
    ADD CONSTRAINT device_members_role_check
    CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER', 'TRANSFERRED'));

-- ── 1) Sửa claim_device — ghi đè quyền sở hữu ──
-- CREATE OR REPLACE: idempotent (Postgres cho phép thay thế function cùng signature).
CREATE OR REPLACE FUNCTION public.claim_device(
    p_device_id   text,
    p_profile     text,
    p_name        text default null,
    p_control_key bytea default null
) RETURNS public.devices
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_dev      public.devices;
    v_old_owner uuid;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'not_authenticated';
    END IF;

    SELECT * INTO v_dev FROM public.devices WHERE device_id = p_device_id;

    IF NOT FOUND THEN
        -- Thiết bị mới hoàn toàn → INSERT
        INSERT INTO public.devices (device_id, profile, name, owner_id, control_key)
        VALUES (p_device_id, p_profile, coalesce(p_name, p_profile), auth.uid(), p_control_key)
        RETURNING * INTO v_dev;
    ELSE
        -- Thiết bị đã tồn tại → ghi đè owner + control_key
        v_old_owner := v_dev.owner_id;

        UPDATE public.devices
           SET owner_id    = auth.uid(),
               profile     = coalesce(p_profile, profile),
               name        = coalesce(p_name, name),
               control_key = coalesce(p_control_key, control_key)
         WHERE id = v_dev.id
        RETURNING * INTO v_dev;

        -- Nếu chủ cũ khác chủ mới → đánh dấu TRANSFERRED
        IF v_old_owner IS NOT NULL AND v_old_owner <> auth.uid() THEN
            UPDATE public.device_members
               SET role = 'TRANSFERRED'
             WHERE device_id = v_dev.id AND user_id = v_old_owner;
        END IF;
    END IF;

    -- Tạo/cập nhật dòng OWNER cho chủ mới
    INSERT INTO public.device_members (device_id, user_id, role)
    VALUES (v_dev.id, auth.uid(), 'OWNER')
    ON CONFLICT (device_id, user_id)
    DO UPDATE SET role = 'OWNER';

    RETURN v_dev;
END;
$$;

-- Quyền gọi: REVOKE + GRANT đều idempotent.
REVOKE ALL ON FUNCTION public.claim_device(text, text, text, bytea) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.claim_device(text, text, text, bytea) TO authenticated;

-- ── 2) Hàm remove_device — xóa dòng device_members của user hiện tại ──
-- DROP IF EXISTS trước để tránh lỗi khi đổi return type (Postgres không cho
-- CREATE OR REPLACE đổi return type — mã lỗi 42P13).
DROP FUNCTION IF EXISTS public.remove_device(text);

CREATE OR REPLACE FUNCTION public.remove_device(
    p_device_id text
) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_dev_uuid uuid;
    v_is_owner boolean;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'not_authenticated';
    END IF;

    -- Tìm UUID nội bộ của thiết bị từ device_id text
    SELECT id INTO v_dev_uuid FROM public.devices WHERE device_id = p_device_id;
    IF v_dev_uuid IS NULL THEN
        -- Thiết bị không tồn tại, không cần làm gì
        RETURN;
    END IF;

    -- Kiểm tra user hiện tại có phải owner không
    v_is_owner := EXISTS (
        SELECT 1 FROM public.devices
        WHERE id = v_dev_uuid AND owner_id = auth.uid()
    );

    -- Xóa dòng device_members của user hiện tại
    DELETE FROM public.device_members
    WHERE device_id = v_dev_uuid AND user_id = auth.uid();

    -- Nếu user là owner → xóa luôn thiết bị (cascade xóa hết device_members còn lại)
    IF v_is_owner THEN
        DELETE FROM public.devices WHERE id = v_dev_uuid;
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION public.remove_device(text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.remove_device(text) TO authenticated;

