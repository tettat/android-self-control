// Cloudflare Worker — Control ADB Pairing Relay
// Uses KV namespace "CHANNELS" for channel state (10-min TTL)

const CHANNEL_TTL_SECONDS = 600; // 10 minutes

// ---------------------------------------------------------------------------
// KV helpers
// ---------------------------------------------------------------------------
async function getChannel(env, channelId) {
  const raw = await env.CHANNELS.get(`ch:${channelId}`);
  if (!raw) return null;
  return JSON.parse(raw);
}

async function putChannel(env, channelId, data) {
  await env.CHANNELS.put(`ch:${channelId}`, JSON.stringify(data), {
    expirationTtl: CHANNEL_TTL_SECONDS,
  });
}

// ---------------------------------------------------------------------------
// Response helpers
// ---------------------------------------------------------------------------
function jsonResponse(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    },
  });
}

function htmlResponse(html) {
  return new Response(html, {
    headers: {
      'Content-Type': 'text/html; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
    },
  });
}

function corsHeaders() {
  return new Response(null, {
    status: 204,
    headers: {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    },
  });
}

// ---------------------------------------------------------------------------
// HTML pages
// ---------------------------------------------------------------------------
const rootHtml = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Control</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: #0f1923;
    color: #e0e0e0;
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .container {
    background: #1a2733;
    border-radius: 16px;
    padding: 40px 32px;
    width: 90%;
    max-width: 420px;
    box-shadow: 0 8px 32px rgba(0,0,0,0.3);
    text-align: center;
  }
  h1 { font-size: 22px; color: #90caf9; margin-bottom: 16px; }
  p { font-size: 15px; color: #90a4ae; line-height: 1.7; }
</style>
</head>
<body>
<div class="container">
  <h1>Control ADB Pairing</h1>
  <p>请从 Control App 获取配对链接</p>
</div>
</body>
</html>`;

function channelHtml(channelId) {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Control - ADB 配对</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: #0f1923;
    color: #e0e0e0;
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px 0;
  }
  .container {
    background: #1a2733;
    border-radius: 16px;
    padding: 32px;
    width: 90%;
    max-width: 420px;
    box-shadow: 0 8px 32px rgba(0,0,0,0.3);
  }
  h1 { font-size: 20px; text-align: center; margin-bottom: 4px; color: #90caf9; }
  .channel-id { text-align: center; font-size: 12px; color: #546e7a; margin-bottom: 20px; font-family: monospace; }
  .steps {
    background: #0f1923;
    border-radius: 10px;
    padding: 16px 18px;
    margin-bottom: 24px;
    font-size: 13px;
    line-height: 1.8;
    color: #90a4ae;
  }
  .steps ol { padding-left: 18px; }
  .steps li { margin-bottom: 2px; }
  .field { margin-bottom: 16px; }
  label { display: block; font-size: 13px; color: #90a4ae; margin-bottom: 6px; }
  input {
    width: 100%;
    padding: 12px 16px;
    border: 1px solid #37474f;
    border-radius: 10px;
    background: #0f1923;
    color: #e0e0e0;
    font-size: 18px;
    letter-spacing: 4px;
    text-align: center;
    outline: none;
    transition: border-color 0.2s;
  }
  input:focus { border-color: #42a5f5; }
  input::placeholder { letter-spacing: 0; font-size: 14px; color: #546e7a; }
  button {
    width: 100%;
    padding: 14px;
    border: none;
    border-radius: 10px;
    background: #1e88e5;
    color: white;
    font-size: 16px;
    font-weight: 600;
    cursor: pointer;
    transition: background 0.2s;
    margin-top: 8px;
  }
  button:hover { background: #1565c0; }
  button:active { background: #0d47a1; }
  button:disabled { background: #37474f; color: #78909c; cursor: not-allowed; }
  .status {
    text-align: center; margin-top: 16px; padding: 10px;
    border-radius: 8px; font-size: 14px; display: none;
  }
  .status.success { display: block; background: rgba(76,175,80,0.15); color: #81c784; }
  .status.error { display: block; background: rgba(244,67,54,0.15); color: #ef5350; }
  .current {
    margin-top: 20px; padding: 12px; background: #0f1923;
    border-radius: 10px; font-size: 13px; color: #78909c;
    text-align: center; display: none;
  }
  .current.show { display: block; }
  .clear-btn {
    background: transparent; border: 1px solid #37474f;
    color: #90a4ae; font-size: 13px; padding: 8px; margin-top: 12px;
  }
  .clear-btn:hover { border-color: #ef5350; color: #ef5350; }
</style>
</head>
<body>
<div class="container">
  <h1>Control ADB 配对</h1>
  <div class="channel-id">Channel: ${channelId}</div>

  <div class="steps">
    <ol>
      <li>打开手机「设置」&gt;「开发者选项」&gt;「无线调试」</li>
      <li>点击「使用配对码配对设备」</li>
      <li>将弹窗中的配对码和端口填入下方</li>
      <li>无线调试页面上方的端口填入「连接端口」</li>
      <li>点击提交，App 将自动完成配对</li>
    </ol>
  </div>

  <div class="field">
    <label>配对码 (6位数字)</label>
    <input type="text" id="code" maxlength="6" inputmode="numeric" pattern="[0-9]*" placeholder="配对码">
  </div>
  <div class="field">
    <label>配对端口</label>
    <input type="text" id="pairingPort" maxlength="5" inputmode="numeric" pattern="[0-9]*" placeholder="配对端口号">
  </div>
  <div class="field">
    <label>连接端口 (无线调试页面主端口)</label>
    <input type="text" id="connectPort" maxlength="5" inputmode="numeric" pattern="[0-9]*" placeholder="连接端口号">
  </div>

  <button id="submitBtn" onclick="submit()">提交配对信息</button>
  <div id="status" class="status"></div>
  <div id="current" class="current"></div>
  <button class="clear-btn" onclick="clearData()">清除已提交的信息</button>
</div>

<script>
const CHANNEL = '${channelId}';
const API = '/api/channel/' + CHANNEL + '/pairing';

function submit() {
  const code = document.getElementById('code').value.trim();
  const pairingPort = document.getElementById('pairingPort').value.trim();
  const connectPort = document.getElementById('connectPort').value.trim();
  const btn = document.getElementById('submitBtn');
  const status = document.getElementById('status');

  if (!code || code.length !== 6) {
    status.className = 'status error';
    status.textContent = '请输入6位配对码';
    return;
  }
  if (!pairingPort) {
    status.className = 'status error';
    status.textContent = '请输入配对端口';
    return;
  }

  btn.disabled = true;
  fetch(API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, pairingPort, connectPort })
  })
  .then(r => r.json())
  .then(() => {
    status.className = 'status success';
    status.textContent = '已提交！App 将自动获取并配对';
    btn.disabled = false;
    refreshCurrent();
  })
  .catch(e => {
    status.className = 'status error';
    status.textContent = '提交失败: ' + e.message;
    btn.disabled = false;
  });
}

function clearData() {
  fetch(API, { method: 'DELETE' })
  .then(() => {
    document.getElementById('status').className = 'status';
    document.getElementById('current').className = 'current';
  });
}

function refreshCurrent() {
  fetch(API)
  .then(r => r.json())
  .then(data => {
    const el = document.getElementById('current');
    if (data && data.code) {
      el.className = 'current show';
      el.textContent = '当前: 配对码 ' + data.code +
        ' | 配对端口 ' + data.pairingPort +
        (data.connectPort ? ' | 连接端口 ' + data.connectPort : '') +
        (data.consumed ? ' (已被App获取)' : ' (等待App获取)');
    } else {
      el.className = 'current';
    }
  });
}

setInterval(refreshCurrent, 2000);
refreshCurrent();

document.querySelectorAll('input').forEach(input => {
  input.addEventListener('input', function() {
    this.value = this.value.replace(/\\D/g, '');
  });
});
</script>
</body>
</html>`;
}

// ---------------------------------------------------------------------------
// Request handler
// ---------------------------------------------------------------------------
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const pathname = url.pathname;

    // CORS preflight
    if (request.method === 'OPTIONS') {
      return corsHeaders();
    }

    // GET / — root page
    if (pathname === '/' && request.method === 'GET') {
      return htmlResponse(rootHtml);
    }

    // GET /ch/:channelId — channel web page
    const chPageMatch = pathname.match(/^\/ch\/([A-Za-z0-9_-]+)$/);
    if (chPageMatch && request.method === 'GET') {
      return htmlResponse(channelHtml(chPageMatch[1]));
    }

    // /api/channel/:channelId/pairing[/consume]
    const chApiMatch = pathname.match(/^\/api\/channel\/([A-Za-z0-9_-]+)\/pairing(\/consume)?$/);
    if (chApiMatch) {
      const channelId = chApiMatch[1];
      const isConsume = chApiMatch[2] === '/consume';

      if (isConsume && request.method === 'POST') {
        const data = await getChannel(env, channelId);
        if (data) {
          data.consumed = true;
          await putChannel(env, channelId, data);
        }
        return jsonResponse({ ok: true });
      }

      if (request.method === 'GET') {
        const data = await getChannel(env, channelId);
        return jsonResponse(data || {});
      }

      if (request.method === 'POST') {
        try {
          const body = await request.json();
          const data = {
            code: String(body.code || ''),
            pairingPort: String(body.pairingPort || ''),
            connectPort: String(body.connectPort || ''),
            timestamp: Date.now(),
            consumed: false,
          };
          await putChannel(env, channelId, data);
          return jsonResponse({ ok: true });
        } catch {
          return jsonResponse({ error: 'Invalid JSON' }, 400);
        }
      }

      if (request.method === 'DELETE') {
        await env.CHANNELS.delete(`ch:${channelId}`);
        return jsonResponse({ ok: true });
      }
    }

    // Backward-compatible routes -> "default" channel
    if (pathname === '/api/pairing/consume' && request.method === 'POST') {
      const data = await getChannel(env, 'default');
      if (data) {
        data.consumed = true;
        await putChannel(env, 'default', data);
      }
      return jsonResponse({ ok: true });
    }

    if (pathname === '/api/pairing') {
      if (request.method === 'GET') {
        const data = await getChannel(env, 'default');
        return jsonResponse(data || {});
      }
      if (request.method === 'POST') {
        try {
          const body = await request.json();
          const data = {
            code: String(body.code || ''),
            pairingPort: String(body.pairingPort || ''),
            connectPort: String(body.connectPort || ''),
            timestamp: Date.now(),
            consumed: false,
          };
          await putChannel(env, 'default', data);
          return jsonResponse({ ok: true });
        } catch {
          return jsonResponse({ error: 'Invalid JSON' }, 400);
        }
      }
      if (request.method === 'DELETE') {
        await env.CHANNELS.delete('ch:default');
        return jsonResponse({ ok: true });
      }
    }

    return new Response('Not Found', { status: 404 });
  },
};
