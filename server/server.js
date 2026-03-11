const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 3000;
const CHANNEL_TTL_MS = 10 * 60 * 1000; // 10 minutes

// ---------------------------------------------------------------------------
// In-memory store: Map<channelId, { data: {...} | null, createdAt: number }>
// ---------------------------------------------------------------------------
const channels = new Map();

function getChannel(channelId) {
  const entry = channels.get(channelId);
  if (!entry) return null;
  if (Date.now() - entry.createdAt > CHANNEL_TTL_MS) {
    channels.delete(channelId);
    return null;
  }
  return entry;
}

function ensureChannel(channelId) {
  let entry = getChannel(channelId);
  if (!entry) {
    entry = { data: null, createdAt: Date.now() };
    channels.set(channelId, entry);
  }
  return entry;
}

// Periodic cleanup of expired channels (every 60 s)
setInterval(() => {
  const now = Date.now();
  for (const [id, entry] of channels) {
    if (now - entry.createdAt > CHANNEL_TTL_MS) {
      channels.delete(id);
    }
  }
}, 60_000);

// ---------------------------------------------------------------------------
// HTML: root page (no channel)
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
  h1 {
    font-size: 22px;
    color: #90caf9;
    margin-bottom: 16px;
  }
  p {
    font-size: 15px;
    color: #90a4ae;
    line-height: 1.7;
  }
</style>
</head>
<body>
<div class="container">
  <h1>Control ADB Pairing</h1>
  <p>请从 Control App 获取配对链接</p>
</div>
</body>
</html>`;

// ---------------------------------------------------------------------------
// HTML: channel page
// ---------------------------------------------------------------------------
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
  h1 {
    font-size: 20px;
    text-align: center;
    margin-bottom: 4px;
    color: #90caf9;
  }
  .channel-id {
    text-align: center;
    font-size: 12px;
    color: #546e7a;
    margin-bottom: 20px;
    font-family: monospace;
  }
  .steps {
    background: #0f1923;
    border-radius: 10px;
    padding: 16px 18px;
    margin-bottom: 24px;
    font-size: 13px;
    line-height: 1.8;
    color: #90a4ae;
  }
  .steps ol {
    padding-left: 18px;
  }
  .steps li {
    margin-bottom: 2px;
  }
  .field {
    margin-bottom: 16px;
  }
  label {
    display: block;
    font-size: 13px;
    color: #90a4ae;
    margin-bottom: 6px;
  }
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
  input::placeholder {
    letter-spacing: 0;
    font-size: 14px;
    color: #546e7a;
  }
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
  button:disabled {
    background: #37474f;
    color: #78909c;
    cursor: not-allowed;
  }
  .status {
    text-align: center;
    margin-top: 16px;
    padding: 10px;
    border-radius: 8px;
    font-size: 14px;
    display: none;
  }
  .status.success {
    display: block;
    background: rgba(76, 175, 80, 0.15);
    color: #81c784;
  }
  .status.error {
    display: block;
    background: rgba(244, 67, 54, 0.15);
    color: #ef5350;
  }
  .status.waiting {
    display: block;
    background: rgba(255, 183, 77, 0.15);
    color: #ffb74d;
  }
  .current {
    margin-top: 20px;
    padding: 12px;
    background: #0f1923;
    border-radius: 10px;
    font-size: 13px;
    color: #78909c;
    text-align: center;
    display: none;
  }
  .current.show { display: block; }
  .clear-btn {
    background: transparent;
    border: 1px solid #37474f;
    color: #90a4ae;
    font-size: 13px;
    padding: 8px;
    margin-top: 12px;
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
  .then(data => {
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
// Route helpers
// ---------------------------------------------------------------------------
function parseBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try { resolve(JSON.parse(body)); }
      catch (e) { reject(e); }
    });
  });
}

function json(res, statusCode, obj) {
  res.writeHead(statusCode, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(obj));
}

// ---------------------------------------------------------------------------
// Pairing API handlers (operate on a given channelId)
// ---------------------------------------------------------------------------
function handleGetPairing(res, channelId) {
  const ch = getChannel(channelId);
  json(res, 200, (ch && ch.data) || {});
}

function handlePostPairing(req, res, channelId) {
  parseBody(req).then(data => {
    const ch = ensureChannel(channelId);
    ch.data = {
      code: String(data.code || ''),
      pairingPort: String(data.pairingPort || ''),
      connectPort: String(data.connectPort || ''),
      timestamp: Date.now(),
      consumed: false,
    };
    console.log(`[${new Date().toISOString()}] [ch:${channelId}] Pairing info submitted: code=${ch.data.code} pairingPort=${ch.data.pairingPort} connectPort=${ch.data.connectPort}`);
    json(res, 200, { ok: true });
  }).catch(() => {
    json(res, 400, { error: 'Invalid JSON' });
  });
}

function handleConsumePairing(res, channelId) {
  const ch = getChannel(channelId);
  if (ch && ch.data) {
    ch.data.consumed = true;
    console.log(`[${new Date().toISOString()}] [ch:${channelId}] Pairing info consumed by app`);
  }
  json(res, 200, { ok: true });
}

function handleDeletePairing(res, channelId) {
  const ch = getChannel(channelId);
  if (ch) ch.data = null;
  console.log(`[${new Date().toISOString()}] [ch:${channelId}] Pairing info cleared`);
  json(res, 200, { ok: true });
}

// ---------------------------------------------------------------------------
// HTTP server
// ---------------------------------------------------------------------------
const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const pathname = url.pathname;

  // CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // ------------------------------------------------------------------
  // Channel web page: GET /ch/:channelId
  // ------------------------------------------------------------------
  const chPageMatch = pathname.match(/^\/ch\/([A-Za-z0-9_-]+)$/);
  if (chPageMatch && req.method === 'GET') {
    const channelId = chPageMatch[1];
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(channelHtml(channelId));
    return;
  }

  // ------------------------------------------------------------------
  // Channel API: /api/channel/:channelId/pairing[/consume]
  // ------------------------------------------------------------------
  const chApiMatch = pathname.match(/^\/api\/channel\/([A-Za-z0-9_-]+)\/pairing(\/consume)?$/);
  if (chApiMatch) {
    const channelId = chApiMatch[1];
    const isConsume = chApiMatch[2] === '/consume';

    if (isConsume && req.method === 'POST') {
      handleConsumePairing(res, channelId);
      return;
    }
    if (req.method === 'GET') { handleGetPairing(res, channelId); return; }
    if (req.method === 'POST') { handlePostPairing(req, res, channelId); return; }
    if (req.method === 'DELETE') { handleDeletePairing(res, channelId); return; }
  }

  // ------------------------------------------------------------------
  // Backward-compatible routes -> "default" channel
  // ------------------------------------------------------------------
  const DEFAULT_CH = 'default';

  if (pathname === '/api/pairing/consume' && req.method === 'POST') {
    handleConsumePairing(res, DEFAULT_CH);
    return;
  }
  if (pathname === '/api/pairing') {
    if (req.method === 'GET') { handleGetPairing(res, DEFAULT_CH); return; }
    if (req.method === 'POST') { handlePostPairing(req, res, DEFAULT_CH); return; }
    if (req.method === 'DELETE') { handleDeletePairing(res, DEFAULT_CH); return; }
  }

  // ------------------------------------------------------------------
  // Root page
  // ------------------------------------------------------------------
  if (pathname === '/' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(rootHtml);
    return;
  }

  // ------------------------------------------------------------------
  // Static file downloads (unchanged)
  // ------------------------------------------------------------------
  if (pathname === '/translive' && req.method === 'GET') {
    const tlApkPath = path.join(__dirname, 'translive.apk');
    if (fs.existsSync(tlApkPath)) {
      const stat = fs.statSync(tlApkPath);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': 'attachment; filename="TransLive.apk"',
      });
      fs.createReadStream(tlApkPath).pipe(res);
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('TransLive APK not found');
    }
    return;
  }

  if (pathname === '/apk' && req.method === 'GET') {
    const apkPath = path.join(__dirname, 'app-debug.apk');
    if (fs.existsSync(apkPath)) {
      const stat = fs.statSync(apkPath);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': 'attachment; filename="control-debug.apk"',
      });
      fs.createReadStream(apkPath).pipe(res);
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('APK not found');
    }
    return;
  }

  // ------------------------------------------------------------------
  // 404
  // ------------------------------------------------------------------
  res.writeHead(404);
  res.end('Not Found');
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Pairing relay server running on http://0.0.0.0:${PORT}`);
});
