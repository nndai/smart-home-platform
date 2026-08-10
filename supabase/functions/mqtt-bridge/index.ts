// ─────────────────────────────────────────────────────────────
// MQTT Bridge — Supabase Edge Function (Deno)
//
// Vai trò (docs ECOSYSTEM_PLAN §3.3 / §5):
//   App KHÔNG kết nối MQTT trực tiếp (open-source, không credential
//   trong APK/repo). Mọi lệnh/trạng thái đi qua đây:
//     POST /functions/v1/mqtt-bridge
//       { action: "send",  deviceId, seq, ts, cmd, payload }
//       { action: "state", deviceId }
//   Authorization: Bearer <JWT của user đã đăng nhập>
//
// Secrets cần tạo (Supabase Dashboard → Settings → Functions → Secrets):
//   HIVEMQ_WS_URL  = wss://<cluster-id>.s1.eu.hivemq.cloud:8884/mqtt
//   HIVEMQ_USER    = app-family (tạo trong HiveMQ console, permission devices/+/#)
//   HIVEMQ_PASS    = mật khẩu app-family
//   SUPABASE_URL   = project URL
//   SUPABASE_ANON_KEY = publishable anon key
// ─────────────────────────────────────────────────────────────

import { createClient } from "jsr:@supabase/supabase-js@2";
import mqtt from "npm:mqtt@5.7.2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const HIVEMQ_WS_URL = Deno.env.get("HIVEMQ_WS_URL")!;
const HIVEMQ_USER = Deno.env.get("HIVEMQ_USER")!;
const HIVEMQ_PASS = Deno.env.get("HIVEMQ_PASS")!;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

// mqtt.js trên Deno dùng WebSocket global (browser build).
function connectBroker(): mqtt.MqttClient {
  return mqtt.connect(HIVEMQ_WS_URL, {
    username: HIVEMQ_USER,
    password: HIVEMQ_PASS,
    clientId: "bridge-" + crypto.randomUUID(),
    clean: true,
    reconnectPeriod: 0,
    connectTimeout: 10_000,
  });
}

function waitForConnect(client: mqtt.MqttClient, timeoutMs: number): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      client.end(true);
      reject(new Error("MQTT connect timeout"));
    }, timeoutMs);
    client.once("connect", () => {
      clearTimeout(timer);
      resolve();
    });
    client.once("error", (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });

  const authHeader = req.headers.get("Authorization");
  if (!authHeader) return json({ error: "missing Authorization header" }, 401);

  const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    global: { headers: { Authorization: authHeader } },
  });

  const { data: { user }, error: userError } = await supabase.auth.getUser();
  if (userError || !user) return json({ error: "unauthorized" }, 401);

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid JSON body" }, 400);
  }

  const action = body["action"];
  const deviceId = body["deviceId"];
  if (typeof deviceId !== "string" || deviceId.length === 0) {
    return json({ error: "deviceId required" }, 400);
  }

  if (action === "state") return handleGetState(supabase, user.id, deviceId);
  if (action === "send") return handleSend(supabase, user.id, deviceId, body);

  return json({ error: "unknown action" }, 400);
});

// ── action=state: đọc retained devices/{deviceId}/up ──
async function handleGetState(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  deviceId: string,
): Promise<Response> {
  const { data: member } = await supabase
    .from("devices")
    .select("id")
    .eq("device_id", deviceId)
    .maybeSingle();
  if (!member) return json({ error: "device_not_found" }, 404);

  const { data: membership } = await supabase
    .from("device_members")
    .select("role")
    .eq("device_id", member.id)
    .eq("user_id", userId)
    .maybeSingle();
  if (!membership) return json({ error: "permission_denied" }, 403);

  let client: mqtt.MqttClient | null = null;
  try {
    client = connectBroker();
    await waitForConnect(client, 10_000);

    const state = await new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        client!.end(true);
        resolve(null);
      }, 5_000);
      client!.on("message", (_topic, payload) => {
        clearTimeout(timer);
        client!.end(true);
        try {
          resolve(JSON.parse(payload.toString()));
        } catch {
          resolve(payload.toString());
        }
      });
      client!.subscribe(`devices/${deviceId}/up`, { qos: 1 });
    });
    return json({ deviceId, state });
  } catch (e) {
    return json({ error: "bridge_failed", detail: String(e) }, 502);
  } finally {
    client?.end(true);
  }
}

// ── action=send: ký envelope (HMAC-SHA256 controlKey) + publish cmd ──
async function handleSend(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  deviceId: string,
  body: Record<string, unknown>,
): Promise<Response> {
  const cmd = body["cmd"];
  const payload = body["payload"] ?? {};
  if (typeof cmd !== "string" || cmd.length === 0) {
    return json({ error: "cmd required" }, 400);
  }

  // Lấy control_key qua RPC (SECURITY DEFINER — chỉ OWNER/ADMIN; PostgREST trả bytea dạng base64)
  const { data: controlKeyB64, error: keyError } = await supabase.rpc("get_control_key", {
    p_device_id: deviceId,
  });
  if (keyError || !controlKeyB64) {
    return json({ error: "permission_denied" }, 403);
  }

  const controlKey = base64ToBytes(String(controlKeyB64));

  // Envelope (docs §3.2): hmac = HMAC-SHA256(controlKey, seq|ts|payload)
  // seq: phase này dùng epoch ms (firmware chưa verify seq — sẽ thắt chặt khi
  // firmware có envelope check). ts: epoch giây.
  const seq = Math.floor(Date.now());
  const ts = Math.floor(Date.now() / 1000);
  const envelope = {
    reqId: crypto.randomUUID().replace(/-/g, "").slice(0, 8),
    seq,
    ts,
    cmd,
    payload,
    hmac: await hmacSha256Hex(
      controlKey,
      `${seq}|${ts}|${JSON.stringify(payload)}`,
    ),
  };

  let client: mqtt.MqttClient | null = null;
  try {
    client = connectBroker();
    await waitForConnect(client, 10_000);
    await new Promise<void>((resolve, reject) => {
      client!.publish(`devices/${deviceId}/cmd`, JSON.stringify(envelope), { qos: 1 }, (err) => {
        if (err) reject(err);
        else resolve();
      });
    });
    return json({ status: "sent", seq, ts });
  } catch (e) {
    return json({ error: "bridge_failed", detail: String(e) }, 502);
  } finally {
    client?.end(true);
  }
}

function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

async function hmacSha256Hex(key: Uint8Array, data: string): Promise<string> {
  const keyBuf = await crypto.subtle.importKey("raw", key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", keyBuf, new TextEncoder().encode(data));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
