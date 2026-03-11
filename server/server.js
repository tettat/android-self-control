const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 3000;
const CHANNEL_TTL_MS = 10 * 60 * 1000; // 10 minutes
const DEVICE_LOG_TTL_MS = 60 * 60 * 1000; // 1 hour

// ---------------------------------------------------------------------------
// In-memory store: Map<channelId, { data: {...} | null, createdAt: number }>
// ---------------------------------------------------------------------------
const channels = new Map();
const deviceLogs = new Map();

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

function getDeviceLog(deviceId) {
  const entry = deviceLogs.get(deviceId);
  if (!entry) return null;
  if (Date.now() - entry.updatedAt > DEVICE_LOG_TTL_MS) {
    deviceLogs.delete(deviceId);
    return null;
  }
  return entry;
}

function setDeviceLog(deviceId, snapshot) {
  deviceLogs.set(deviceId, {
    snapshot,
    updatedAt: Date.now(),
  });
}

// Periodic cleanup of expired channels (every 60 s)
setInterval(() => {
  const now = Date.now();
  for (const [id, entry] of channels) {
    if (now - entry.createdAt > CHANNEL_TTL_MS) {
      channels.delete(id);
    }
  }
  for (const [id, entry] of deviceLogs) {
    if (now - entry.updatedAt > DEVICE_LOG_TTL_MS) {
      deviceLogs.delete(id);
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
  <p style="margin-top: 12px;"><a href="/logs" style="color: #90caf9;">查看设备执行日志</a></p>
</div>
</body>
</html>`;

const logsHtml = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Control Logs</title>
<style>
  * { box-sizing: border-box; }
  body {
    margin: 0;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: linear-gradient(180deg, #0e1720 0%, #13212e 100%);
    color: #e8eef3;
  }
  .page {
    max-width: 1200px;
    margin: 0 auto;
    padding: 24px 16px 48px;
  }
  .hero, .panel, .entry {
    background: rgba(18, 29, 40, 0.9);
    border: 1px solid rgba(144, 202, 249, 0.16);
    border-radius: 18px;
  }
  .hero, .panel {
    padding: 20px;
    margin-bottom: 16px;
  }
  .grid {
    display: grid;
    grid-template-columns: 280px minmax(0, 1fr);
    gap: 16px;
  }
  .devices {
    display: grid;
    gap: 10px;
  }
  .device {
    width: 100%;
    padding: 12px;
    border-radius: 14px;
    background: rgba(255,255,255,0.03);
    border: 1px solid transparent;
    color: inherit;
    text-align: left;
    cursor: pointer;
  }
  .device.active {
    border-color: #42a5f5;
    background: rgba(66,165,245,0.14);
  }
  .meta {
    color: #90a4ae;
    font-size: 13px;
    line-height: 1.6;
  }
  .entries {
    display: grid;
    gap: 12px;
  }
  .entry {
    padding: 14px;
  }
  .entry h3 {
    margin: 0 0 8px;
    font-size: 15px;
  }
  .entry pre {
    margin: 0;
    white-space: pre-wrap;
    word-break: break-word;
    font-family: inherit;
    line-height: 1.55;
  }
  .pill {
    display: inline-block;
    padding: 4px 8px;
    border-radius: 999px;
    background: rgba(255,255,255,0.08);
    font-size: 12px;
    margin-right: 8px;
  }
  a { color: #90caf9; }
  @media (max-width: 900px) {
    .grid { grid-template-columns: 1fr; }
  }
</style>
</head>
<body>
<div class="page">
  <div class="hero">
    <h1 style="margin: 0 0 8px;">Control Relay Logs</h1>
    <div class="meta">这里展示通过 App 主动同步到 relay server 的最新执行日志。页面每 5 秒自动刷新一次。</div>
  </div>

  <div class="grid">
    <section class="panel">
      <h2 style="margin-top: 0;">设备</h2>
      <div id="devices" class="devices"></div>
    </section>

    <section class="panel">
      <div id="summary" class="meta">正在加载...</div>
      <div id="entries" class="entries" style="margin-top: 16px;"></div>
    </section>
  </div>
</div>

<script>
const state = { selectedId: null, timer: null };

function escapeHtml(value) {
  return String(value || '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

async function loadDevices() {
  const res = await fetch('/api/device-logs');
  const data = await res.json();
  const devices = data.devices || [];
  if (!state.selectedId && devices.length > 0) {
    state.selectedId = devices[0].deviceId;
  }
  renderDevices(devices);
  if (state.selectedId) {
    await loadDevice(state.selectedId);
  } else {
    document.getElementById('summary').textContent = '暂无设备日志';
    document.getElementById('entries').innerHTML = '';
  }
}

function renderDevices(devices) {
  const container = document.getElementById('devices');
  if (!devices.length) {
    container.innerHTML = '<div class="meta">暂无设备同步日志</div>';
    return;
  }
  container.innerHTML = devices.map(device => {
    const active = device.deviceId === state.selectedId ? ' active' : '';
    return '<button class="device' + active + '" onclick="selectDevice(\\'' + escapeHtml(device.deviceId) + '\\')">' +
      '<div><strong>' + escapeHtml(device.deviceName || device.deviceId) + '</strong></div>' +
      '<div class="meta">' + escapeHtml(device.statusMessage || '无状态') + '</div>' +
      '<div class="meta">' + escapeHtml(device.updatedAtFormatted || '') + ' · ' + (device.entryCount || 0) + ' 条日志</div>' +
      '</button>';
  }).join('');
}

async function loadDevice(deviceId) {
  const res = await fetch('/api/device-logs/' + encodeURIComponent(deviceId));
  if (!res.ok) {
    document.getElementById('summary').textContent = '设备日志不存在或已过期';
    document.getElementById('entries').innerHTML = '';
    return;
  }
  const snapshot = await res.json();
  renderDevice(snapshot);
}

function renderDevice(snapshot) {
  const stateInfo = snapshot.agentState || {};
  const urls = ((snapshot.logServer && snapshot.logServer.accessUrls) || [])
    .map(url => '<a href="' + escapeHtml(url) + '">' + escapeHtml(url) + '</a>')
    .join(' , ');
  document.getElementById('summary').innerHTML =
    '<div><strong>' + escapeHtml(snapshot.deviceName || snapshot.deviceId || '未知设备') + '</strong></div>' +
    '<div class="meta">状态: ' + escapeHtml(stateInfo.statusMessage || '无') + '</div>' +
    '<div class="meta">最后同步: ' + escapeHtml(snapshot.relaySyncedAtFormatted || snapshot.exportTime || '') + '</div>' +
    '<div class="meta">手机日志地址: ' + (urls || '无') + '</div>';

  const entries = snapshot.entries || [];
  document.getElementById('entries').innerHTML = entries.slice().reverse().map(entry => (
    '<article class="entry">' +
      '<div><span class="pill">' + escapeHtml(entry.type) + '</span><span class="meta">' + escapeHtml(entry.timestampFormatted) + '</span></div>' +
      '<h3>' + escapeHtml(entry.title) + '</h3>' +
      '<pre>' + escapeHtml(entry.content) + '</pre>' +
    '</article>'
  )).join('');
}

async function selectDevice(deviceId) {
  state.selectedId = deviceId;
  await loadDevices();
}

loadDevices();
state.timer = setInterval(loadDevices, 5000);
</script>
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

function handlePostDeviceLogs(req, res, deviceId) {
  parseBody(req).then(snapshot => {
    const safeSnapshot = {
      ...snapshot,
      deviceId,
      deviceName: snapshot.deviceName || deviceId,
      relaySyncedAt: Date.now(),
      relaySyncedAtFormatted: new Date().toISOString(),
    };
    setDeviceLog(deviceId, safeSnapshot);
    console.log(`[${new Date().toISOString()}] [device:${deviceId}] Log snapshot updated (${(safeSnapshot.entries || []).length} entries)`);
    json(res, 200, { ok: true });
  }).catch(() => {
    json(res, 400, { error: 'Invalid JSON' });
  });
}

function handleGetDeviceLog(res, deviceId) {
  const entry = getDeviceLog(deviceId);
  if (!entry) {
    json(res, 404, { error: 'Device log not found' });
    return;
  }
  json(res, 200, entry.snapshot);
}

function handleGetDeviceLogList(res) {
  const devices = [];
  for (const [deviceId, entry] of deviceLogs) {
    const snapshot = getDeviceLog(deviceId)?.snapshot;
    if (!snapshot) continue;
    devices.push({
      deviceId,
      deviceName: snapshot.deviceName || deviceId,
      updatedAt: entry.updatedAt,
      updatedAtFormatted: new Date(entry.updatedAt).toISOString(),
      entryCount: Array.isArray(snapshot.entries) ? snapshot.entries.length : 0,
      statusMessage: snapshot.agentState?.statusMessage || '',
    });
  }
  devices.sort((a, b) => b.updatedAt - a.updatedAt);
  json(res, 200, { devices });
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

  if (pathname === '/logs' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(logsHtml);
    return;
  }

  if (pathname === '/api/device-logs' && req.method === 'GET') {
    handleGetDeviceLogList(res);
    return;
  }

  const deviceLogsMatch = pathname.match(/^\/api\/device-logs\/([A-Za-z0-9_-]+)$/);
  if (deviceLogsMatch) {
    const deviceId = deviceLogsMatch[1];
    if (req.method === 'GET') {
      handleGetDeviceLog(res, deviceId);
      return;
    }
    if (req.method === 'POST') {
      handlePostDeviceLogs(req, res, deviceId);
      return;
    }
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
