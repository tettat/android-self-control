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
  .section-head {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    align-items: baseline;
    margin: 18px 0 12px;
  }
  .section-head h2 {
    margin: 0;
    font-size: 18px;
  }
  .overview {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
    gap: 12px;
    margin-top: 16px;
  }
  .stat-card, .insight-card, .raw-panel, .session-shell, .session-event-card {
    background: rgba(255,255,255,0.03);
    border: 1px solid rgba(144, 202, 249, 0.12);
    border-radius: 16px;
  }
  .stat-card {
    padding: 14px;
  }
  .stat-label {
    color: #90a4ae;
    font-size: 12px;
    margin-bottom: 8px;
  }
  .stat-value {
    font-size: 28px;
    line-height: 1;
    font-weight: 700;
    margin-bottom: 6px;
  }
  .stat-note {
    color: #90a4ae;
    font-size: 12px;
  }
  .insight-grid {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 16px;
    margin-top: 16px;
  }
  .insight-card {
    padding: 16px;
  }
  .insight-card h2 {
    margin: 0 0 6px;
    font-size: 16px;
  }
  .session-shell {
    padding: 18px;
    margin-top: 16px;
    background:
      radial-gradient(circle at top right, rgba(66,165,245,0.14), transparent 34%),
      rgba(255,255,255,0.03);
  }
  .session-topline {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    align-items: center;
    margin-bottom: 14px;
  }
  .session-title {
    font-size: 20px;
    font-weight: 700;
  }
  .session-status {
    padding: 6px 10px;
    border-radius: 999px;
    font-size: 12px;
    background: rgba(129, 199, 132, 0.16);
    color: #81c784;
  }
  .session-status.running { animation: pulseGlow 1.8s ease-in-out infinite; }
  .session-meta-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
    gap: 10px;
    margin-bottom: 18px;
  }
  .session-meta-card {
    padding: 12px;
    border-radius: 12px;
    background: rgba(255,255,255,0.04);
    border: 1px solid rgba(144, 202, 249, 0.08);
  }
  .session-meta-card strong {
    display: block;
    margin-top: 6px;
    font-size: 18px;
  }
  .flow-strip {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
    gap: 12px;
  }
  .flow-stage {
    position: relative;
    overflow: hidden;
    padding: 14px;
    border-radius: 14px;
    border: 1px solid rgba(144, 202, 249, 0.14);
    background: rgba(12, 22, 31, 0.72);
  }
  .flow-stage::before {
    content: '';
    position: absolute;
    inset: 0;
    opacity: 0;
    background: linear-gradient(120deg, transparent 0%, rgba(66,165,245,0.2) 48%, transparent 100%);
    transform: translateX(-120%);
  }
  .flow-stage.running::before {
    opacity: 1;
    animation: stageSweep 2.4s linear infinite;
  }
  .flow-stage.done { border-color: rgba(129, 199, 132, 0.25); }
  .flow-stage.pending { opacity: 0.72; }
  .flow-index {
    display: inline-flex;
    width: 28px;
    height: 28px;
    align-items: center;
    justify-content: center;
    border-radius: 999px;
    background: #607d8b;
    color: #fff;
    font-weight: 700;
    font-size: 12px;
    margin-bottom: 10px;
  }
  .flow-stage.done .flow-index,
  .flow-stage.running .flow-index { background: #42a5f5; }
  .flow-stage h3 {
    margin: 0 0 6px;
    font-size: 15px;
  }
  .flow-caption {
    font-size: 13px;
    color: #b0bec5;
    line-height: 1.5;
  }
  .flow-tag {
    display: inline-block;
    margin-top: 10px;
    padding: 4px 8px;
    border-radius: 999px;
    font-size: 12px;
    background: rgba(255,255,255,0.08);
  }
  .session-event-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: 12px;
    margin-top: 18px;
  }
  .session-event-card {
    padding: 14px;
    background: rgba(12, 22, 31, 0.72);
  }
  .session-event-card h3 {
    margin: 0 0 8px;
    font-size: 14px;
  }
  .chart, .timeline {
    display: grid;
    gap: 10px;
    margin-top: 14px;
  }
  .chart-row {
    display: grid;
    grid-template-columns: minmax(88px, 120px) minmax(0, 1fr) auto;
    gap: 10px;
    align-items: center;
    font-size: 13px;
  }
  .chart-track {
    height: 10px;
    border-radius: 999px;
    background: rgba(255,255,255,0.08);
    overflow: hidden;
  }
  .chart-fill {
    height: 100%;
    border-radius: 999px;
    background: linear-gradient(90deg, #42a5f5 0%, #26c6da 100%);
  }
  .timeline-item {
    padding: 12px;
    border-radius: 12px;
    background: rgba(255,255,255,0.03);
    border: 1px solid rgba(144, 202, 249, 0.1);
  }
  .timeline-top {
    display: flex;
    justify-content: space-between;
    gap: 10px;
    align-items: baseline;
    margin-bottom: 6px;
  }
  .timeline-title {
    font-size: 14px;
    font-weight: 600;
  }
  .timeline-snippet {
    font-size: 13px;
    color: #b0bec5;
  }
  .timeline-empty {
    color: #90a4ae;
    font-size: 13px;
  }
  .control-panel {
    margin-top: 16px;
    padding: 16px;
    border-radius: 16px;
    background: rgba(255,255,255,0.03);
    border: 1px solid rgba(144, 202, 249, 0.12);
  }
  .control-head {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 8px 12px;
    margin-bottom: 10px;
  }
  .control-row {
    display: flex;
    gap: 10px;
    align-items: stretch;
  }
  .control-row textarea {
    flex: 1;
    min-height: 84px;
    resize: vertical;
    border: 1px solid rgba(255,255,255,0.12);
    border-radius: 12px;
    background: rgba(10, 18, 24, 0.92);
    color: #e8eef3;
    padding: 12px 14px;
    font: inherit;
  }
  .control-row textarea:focus {
    outline: 2px solid rgba(66,165,245,0.22);
    border-color: rgba(66,165,245,0.45);
  }
  .control-button {
    min-width: 120px;
    padding: 0 18px;
    border: none;
    border-radius: 12px;
    background: #1e88e5;
    color: #fff;
    font-size: 15px;
    font-weight: 600;
    cursor: pointer;
  }
  .control-button.stop {
    background: #e65132;
  }
  .control-button:disabled {
    background: #37474f;
    color: #90a4ae;
    cursor: not-allowed;
  }
  .control-feedback {
    min-height: 20px;
    margin-top: 8px;
  }
  .control-feedback.success {
    color: #81c784;
  }
  .control-feedback.error {
    color: #ef9a9a;
  }
  .entry {
    padding: 0;
    overflow: hidden;
  }
  .entry summary {
    list-style: none;
    cursor: pointer;
    padding: 14px;
  }
  .entry summary::-webkit-details-marker {
    display: none;
  }
  .entry-body {
    padding: 0 14px 14px;
    border-top: 1px solid rgba(144, 202, 249, 0.1);
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
  .raw-panel {
    margin-top: 16px;
    overflow: hidden;
  }
  .raw-panel summary {
    cursor: pointer;
    list-style: none;
    padding: 16px;
    font-weight: 600;
  }
  .raw-panel summary::-webkit-details-marker {
    display: none;
  }
  .raw-content {
    padding: 0 16px 16px;
    border-top: 1px solid rgba(144, 202, 249, 0.12);
  }
  @keyframes stageSweep {
    from { transform: translateX(-120%); }
    to { transform: translateX(120%); }
  }
  @keyframes pulseGlow {
    0%, 100% { box-shadow: 0 0 0 0 rgba(66,165,245,0.18); }
    50% { box-shadow: 0 0 0 8px rgba(66,165,245,0); }
  }
  .auto-refresh-row {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 12px;
  }
  .toggle {
    position: relative;
    width: 44px;
    height: 24px;
    background: rgba(255,255,255,0.12);
    border-radius: 12px;
    cursor: pointer;
    transition: background 0.2s;
  }
  .toggle.on { background: #42a5f5; }
  .toggle::after {
    content: '';
    position: absolute;
    top: 2px;
    left: 2px;
    width: 20px;
    height: 20px;
    background: #fff;
    border-radius: 50%;
    transition: transform 0.2s;
  }
  .toggle.on::after { transform: translateX(20px); }
  a { color: #90caf9; }
  @media (max-width: 900px) {
    .grid { grid-template-columns: 1fr; }
    .insight-grid { grid-template-columns: 1fr; }
    .control-row { flex-direction: column; }
    .control-button { min-height: 48px; width: 100%; }
  }
</style>
</head>
<body>
<div class="page">
  <div class="hero">
    <h1 style="margin: 0 0 8px;">Control Relay Logs</h1>
    <div class="meta">这里展示通过 App 主动同步到 relay server 的最新执行日志。</div>
    <div class="auto-refresh-row">
      <span class="meta">自动刷新 (每 5 秒)</span>
      <div id="autoRefreshToggle" class="toggle" onclick="toggleAutoRefresh()" title="开启/关闭自动刷新"></div>
    </div>
  </div>

  <div class="grid">
    <section class="panel">
      <h2 style="margin-top: 0;">设备</h2>
      <div id="devices" class="devices"></div>
    </section>

    <section class="panel">
      <div id="summary" class="meta">正在加载...</div>
      <div class="control-panel">
        <div class="control-head">
          <strong>手动输入指令</strong>
          <span class="meta">这里提交的文本会按语音指令同样的流程交给 Agent。</span>
        </div>
        <div class="control-row">
          <textarea id="commandInput" placeholder="例如：打开微信，给张三发消息说我十分钟后到"></textarea>
          <button id="commandButton" class="control-button" type="button" onclick="handleCommandButton()">发送指令</button>
        </div>
        <div id="commandFeedback" class="meta control-feedback"></div>
        <div id="controlHint" class="meta"></div>
      </div>
      <div class="section-head">
        <h2>数据大盘</h2>
        <div class="meta">聚合统计，用来看整体运行健康度。</div>
      </div>
      <div id="overview" class="overview"></div>
      <div class="insight-grid">
        <section class="insight-card">
          <h2>日志分布</h2>
          <div class="meta">优先展示结构化摘要，不默认摊开原始内容。</div>
          <div id="distribution" class="chart"></div>
        </section>
        <section class="insight-card">
          <h2>最近动态</h2>
          <div class="meta">最近几条关键日志的压缩时间线。</div>
          <div id="timeline" class="timeline"></div>
        </section>
      </div>
      <div class="section-head">
        <h2>当前会话详情</h2>
        <div class="meta">把当前执行情况拆成有语义的阶段流程。</div>
      </div>
      <section id="sessionBoard" class="session-shell"></section>
      <details class="raw-panel">
        <summary>查看原始日志</summary>
        <div class="raw-content">
          <div id="entries" class="entries"></div>
        </div>
      </details>
    </section>
  </div>
</div>

<script>
const AUTO_REFRESH_KEY = 'control-logs-autoRefresh';
const REFRESH_INTERVAL_MS = 5000;
const TYPE_LABELS = {
  ACTION_EXECUTED: '执行动作',
  API_REQUEST: '请求模型',
  API_RESPONSE: '模型响应',
  SCREENSHOT: '截图',
  ERROR: '异常',
  INFO: '信息',
  VOICE_INPUT: '语音输入'
};

const state = {
  selectedId: null,
  timer: null,
  autoRefresh: false,
  selectedSnapshot: null,
  controlBaseUrl: '',
  commandInFlight: false,
};

function getStoredAutoRefresh() {
  try {
    const v = localStorage.getItem(AUTO_REFRESH_KEY);
    return v === null ? false : v === 'true';
  } catch (_) { return false; }
}

function setStoredAutoRefresh(on) {
  try { localStorage.setItem(AUTO_REFRESH_KEY, String(on)); } catch (_) {}
}

function applyAutoRefresh(on) {
  state.autoRefresh = on;
  setStoredAutoRefresh(on);
  const el = document.getElementById('autoRefreshToggle');
  if (el) el.classList.toggle('on', on);
  if (state.timer) {
    clearInterval(state.timer);
    state.timer = null;
  }
  if (on) state.timer = setInterval(loadDevices, REFRESH_INTERVAL_MS);
}

function toggleAutoRefresh() {
  applyAutoRefresh(!state.autoRefresh);
}

function escapeHtml(value) {
  return String(value || '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function normalizeBaseUrl(url) {
  return String(url || '').replace(/\\/+$/, '');
}

function summarizeText(value, maxLength) {
  const compact = String(value || '').replace(/\\s+/g, ' ').trim();
  if (!compact) return '无附加内容';
  return compact.length > maxLength ? compact.slice(0, maxLength - 1) + '…' : compact;
}

function formatType(type) {
  return TYPE_LABELS[type] || type || '未知';
}

function buildTypeCounts(entries) {
  const counts = {};
  (entries || []).forEach(entry => {
    const key = String(entry.type || 'UNKNOWN');
    counts[key] = (counts[key] || 0) + 1;
  });
  return counts;
}

function findLatestEntry(entries, matcher) {
  const reversed = (entries || []).slice().reverse();
  return reversed.find(matcher) || null;
}

function setFeedback(message, tone) {
  const el = document.getElementById('commandFeedback');
  el.textContent = message || '';
  el.className = 'meta control-feedback' + (tone ? ' ' + tone : '');
}

function getControlAvailability() {
  if (!state.selectedSnapshot) {
    return { ok: false, reason: '选择设备后即可在这里发送指令。' };
  }
  if (!state.controlBaseUrl) {
    return { ok: false, reason: '当前设备没有可用的手机日志地址。' };
  }
  if (window.location.protocol === 'https:' && state.controlBaseUrl.startsWith('http://')) {
    return {
      ok: false,
      reason: '当前页面使用 HTTPS，浏览器通常会拦截对手机 http 地址的控制请求。请打开手机日志地址操作。'
    };
  }
  return { ok: true, reason: '' };
}

function updateControlUi() {
  const snapshot = state.selectedSnapshot || {};
  const stateInfo = snapshot.agentState || {};
  const isRunning = !!stateInfo.isRunning;
  const available = getControlAvailability();
  const button = document.getElementById('commandButton');
  button.textContent = isRunning ? '停止运行' : '发送指令';
  button.classList.toggle('stop', isRunning);
  button.disabled = state.commandInFlight || !available.ok;

  let hintHtml = available.reason ? escapeHtml(available.reason) : '控制通道可用';
  if (state.controlBaseUrl) {
    const link = '<a href="' + escapeHtml(state.controlBaseUrl) + '" target="_blank" rel="noreferrer">打开手机日志页</a>';
    hintHtml = available.ok ? ('控制通道: ' + link) : (hintHtml + ' · ' + link);
  }
  document.getElementById('controlHint').innerHTML = hintHtml;
}

function applyOptimisticAgentState(isRunning, statusMessage) {
  if (!state.selectedSnapshot) return;
  const stateInfo = state.selectedSnapshot.agentState || {};
  state.selectedSnapshot.agentState = Object.assign({}, stateInfo, {
    isRunning,
    statusMessage: statusMessage || stateInfo.statusMessage || '',
  });
  renderDevice(state.selectedSnapshot);
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
    document.getElementById('overview').innerHTML = '';
    document.getElementById('distribution').innerHTML = '';
    document.getElementById('timeline').innerHTML = '';
    document.getElementById('entries').innerHTML = '';
    state.selectedSnapshot = null;
    state.controlBaseUrl = '';
    updateControlUi();
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
    document.getElementById('overview').innerHTML = '';
    document.getElementById('distribution').innerHTML = '';
    document.getElementById('timeline').innerHTML = '';
    document.getElementById('entries').innerHTML = '';
    state.selectedSnapshot = null;
    state.controlBaseUrl = '';
    updateControlUi();
    return;
  }
  const snapshot = await res.json();
  renderDevice(snapshot);
}

function renderDevice(snapshot) {
  state.selectedSnapshot = snapshot;
  state.controlBaseUrl = normalizeBaseUrl(((snapshot.logServer && snapshot.logServer.accessUrls) || [])[0] || '');
  const stateInfo = snapshot.agentState || {};
  const urls = ((snapshot.logServer && snapshot.logServer.accessUrls) || [])
    .map(url => '<a href="' + escapeHtml(url) + '" target="_blank" rel="noreferrer">' + escapeHtml(url) + '</a>')
    .join(' , ');
  document.getElementById('summary').innerHTML =
    '<div><strong>' + escapeHtml(snapshot.deviceName || snapshot.deviceId || '未知设备') + '</strong></div>' +
    '<div class="meta">状态: ' + escapeHtml(stateInfo.statusMessage || '无') + '</div>' +
    '<div class="meta">最后同步: ' + escapeHtml(snapshot.relaySyncedAtFormatted || snapshot.exportTime || '') + '</div>' +
    '<div class="meta">手机日志地址: ' + (urls || '无') + '</div>';
  updateControlUi();

  const entries = snapshot.entries || [];
  renderOverview(snapshot, entries);
  renderDistribution(entries);
  renderTimeline(entries);
  renderSessionBoard(snapshot, entries);
  document.getElementById('entries').innerHTML = entries.slice().reverse().map(entry => (
    '<details class="entry">' +
      '<summary>' +
        '<div><span class="pill">' + escapeHtml(formatType(entry.type)) + '</span><span class="meta">' + escapeHtml(entry.timestampFormatted) + '</span></div>' +
        '<h3>' + escapeHtml(entry.title) + '</h3>' +
        '<div class="meta">' + escapeHtml(summarizeText(entry.content, 120)) + '</div>' +
      '</summary>' +
      '<div class="entry-body"><pre>' + escapeHtml(entry.content) + '</pre></div>' +
    '</details>'
  )).join('');
}

function renderOverview(snapshot, entries) {
  const stateInfo = snapshot.agentState || {};
  const counts = buildTypeCounts(entries);
  const cards = [
    {
      label: '当前状态',
      value: stateInfo.isRunning ? '执行中' : '空闲',
      note: stateInfo.statusMessage || '暂无状态信息'
    },
    {
      label: '同步日志',
      value: String(entries.length),
      note: '当前设备最近快照'
    },
    {
      label: '执行动作',
      value: String(counts.ACTION_EXECUTED || 0),
      note: '点击、输入、滚动等'
    },
    {
      label: '异常数量',
      value: String(counts.ERROR || 0),
      note: (counts.ERROR || 0) > 0 ? '建议优先排查' : '当前未发现异常'
    }
  ];
  document.getElementById('overview').innerHTML = cards.map(card => (
    '<article class="stat-card">' +
      '<div class="stat-label">' + escapeHtml(card.label) + '</div>' +
      '<div class="stat-value">' + escapeHtml(card.value) + '</div>' +
      '<div class="stat-note">' + escapeHtml(card.note) + '</div>' +
    '</article>'
  )).join('');
}

function renderDistribution(entries) {
  const counts = Object.entries(buildTypeCounts(entries)).sort((a, b) => b[1] - a[1]);
  const container = document.getElementById('distribution');
  if (!counts.length) {
    container.innerHTML = '<div class="timeline-empty">暂无可统计的日志类型</div>';
    return;
  }
  const max = counts[0][1] || 1;
  container.innerHTML = counts.map(([type, count]) => {
    const width = Math.max((count / max) * 100, count > 0 ? 8 : 0);
    return '<div class="chart-row">' +
      '<div>' + escapeHtml(formatType(type)) + '</div>' +
      '<div class="chart-track"><div class="chart-fill" style="width:' + width.toFixed(1) + '%"></div></div>' +
      '<div>' + count + '</div>' +
    '</div>';
  }).join('');
}

function renderTimeline(entries) {
  const container = document.getElementById('timeline');
  const items = (entries || []).slice().reverse().slice(0, 6);
  if (!items.length) {
    container.innerHTML = '<div class="timeline-empty">暂无最近动态</div>';
    return;
  }
  container.innerHTML = items.map(entry => (
    '<article class="timeline-item">' +
      '<div class="timeline-top">' +
        '<div class="timeline-title">' + escapeHtml(entry.title || formatType(entry.type)) + '</div>' +
        '<div class="meta">' + escapeHtml(entry.timestampFormatted) + '</div>' +
      '</div>' +
      '<div class="timeline-snippet">' + escapeHtml(summarizeText(entry.content, 110)) + '</div>' +
    '</article>'
  )).join('');
}

function buildSessionFlow(snapshot, entries) {
  const stateInfo = snapshot.agentState || {};
  const stepTimings = stateInfo.stepTimings || [];
  const voiceEntry = findLatestEntry(entries, entry => entry.type === 'VOICE_INPUT' || /手动指令|语音/i.test(String(entry.title || '')));
  const screenEntry = findLatestEntry(entries, entry => entry.type === 'SCREENSHOT');
  const aiEntry = findLatestEntry(entries, entry => entry.type === 'API_RESPONSE' || entry.type === 'API_REQUEST');
  const actionEntry = findLatestEntry(entries, entry => entry.type === 'ACTION_EXECUTED');
  const errorEntry = findLatestEntry(entries, entry => entry.type === 'ERROR');
  const stages = [
    {
      title: '接收指令',
      detail: summarizeText((voiceEntry && (voiceEntry.content || voiceEntry.title)) || stateInfo.lastAction || '等待新的输入', 72),
      completed: !!voiceEntry || !!stateInfo.taskStartedAtMs
    },
    {
      title: '理解界面',
      detail: summarizeText((screenEntry && (screenEntry.title || screenEntry.content)) || '尚未进入截图分析', 72),
      completed: !!screenEntry || stepTimings.some(step => /截图/.test(String(step.label || '')))
    },
    {
      title: '规划动作',
      detail: summarizeText((aiEntry && (aiEntry.title || aiEntry.content)) || stateInfo.lastThinking || '等待模型决策', 72),
      completed: !!aiEntry || stepTimings.some(step => /ai|模型/i.test(String(step.label || '') + ' ' + String(step.tool || '')))
    },
    {
      title: '执行操作',
      detail: summarizeText((actionEntry && (actionEntry.title || actionEntry.content)) || stateInfo.activeTool || '尚未执行具体动作', 72),
      completed: !!actionEntry || stepTimings.some(step => !!step.tool)
    },
    {
      title: errorEntry ? '异常处理' : '完成收尾',
      detail: summarizeText((errorEntry && errorEntry.content) || stateInfo.statusMessage || '等待最终状态', 72),
      completed: !stateInfo.isRunning && (!!stateInfo.lastProgressAtMs || !!errorEntry),
      forceRunning: !!errorEntry && stateInfo.isRunning
    }
  ];
  const activeIndex = stages.findIndex(stage => !stage.completed);
  return stages.map((stage, index) => Object.assign({}, stage, {
    running: stage.forceRunning || (stateInfo.isRunning && activeIndex === index),
    stateLabel: stage.completed ? '已完成' : ((stage.forceRunning || (stateInfo.isRunning && activeIndex === index)) ? '进行中' : '待开始')
  }));
}

function renderSessionBoard(snapshot, entries) {
  const container = document.getElementById('sessionBoard');
  const stateInfo = snapshot.agentState || {};
  const session = ((snapshot.sessions || []).find(session => session.isActive)) || null;
  const stages = buildSessionFlow(snapshot, entries);
  const counts = buildTypeCounts(entries);
  const durationMs = stateInfo.taskStartedAtMs && stateInfo.lastProgressAtMs
    ? Math.max(0, stateInfo.lastProgressAtMs - stateInfo.taskStartedAtMs)
    : 0;
  const metaCards = [
    { label: '当前会话', value: (session && (session.title || session.id)) || '当前设备全局流' },
    { label: '轮次', value: (stateInfo.currentRound || 0) + '/' + (stateInfo.maxRounds || 0) },
    { label: '步骤数', value: String((stateInfo.stepTimings || []).length) },
    { label: '执行时长', value: durationMs > 0 ? formatDurationMs(durationMs) : '刚开始' },
    { label: '活跃工具', value: stateInfo.activeTool || '暂无' },
    { label: '动作数', value: String(counts.ACTION_EXECUTED || 0) }
  ];
  const recentEvents = (entries || []).slice().reverse().slice(0, 3);
  container.innerHTML =
    '<div class="session-topline">' +
      '<div>' +
        '<div class="session-title">' + escapeHtml((session && session.title) || '当前执行会话') + '</div>' +
        '<div class="meta">' + escapeHtml(stateInfo.statusMessage || '等待新的任务') + '</div>' +
      '</div>' +
      '<div class="session-status' + (stateInfo.isRunning ? ' running' : '') + '">' + escapeHtml(stateInfo.isRunning ? '运行中' : '空闲') + '</div>' +
    '</div>' +
    '<div class="session-meta-grid">' +
      metaCards.map(card => (
        '<div class="session-meta-card">' +
          '<div class="meta">' + escapeHtml(card.label) + '</div>' +
          '<strong>' + escapeHtml(card.value) + '</strong>' +
        '</div>'
      )).join('') +
    '</div>' +
    '<div class="section-head" style="margin-top:0;"><h2 style="font-size:16px;">执行流程</h2><div class="meta">阶段会根据实时状态自动点亮。</div></div>' +
    '<div class="flow-strip">' +
      stages.map((stage, index) => (
        '<article class="flow-stage ' + (stage.completed ? 'done' : (stage.running ? 'running' : 'pending')) + '">' +
          '<div class="flow-index">' + (index + 1) + '</div>' +
          '<h3>' + escapeHtml(stage.title) + '</h3>' +
          '<div class="flow-caption">' + escapeHtml(stage.detail) + '</div>' +
          '<div class="flow-tag">' + escapeHtml(stage.stateLabel) + '</div>' +
        '</article>'
      )).join('') +
    '</div>' +
    '<div class="session-event-grid">' +
      (recentEvents.length ? recentEvents.map(entry => (
        '<article class="session-event-card">' +
          '<h3>' + escapeHtml(entry.title || formatType(entry.type)) + '</h3>' +
          '<div class="meta">' + escapeHtml(entry.timestampFormatted || '') + '</div>' +
          '<div class="flow-caption" style="margin-top:8px;">' + escapeHtml(summarizeText(entry.content, 96)) + '</div>' +
        '</article>'
      )).join('') : '<div class="timeline-empty">当前会话还没有更多事件。</div>') +
    '</div>';
}

async function selectDevice(deviceId) {
  state.selectedId = deviceId;
  await loadDevices();
}

async function handleCommandButton() {
  if (!state.selectedSnapshot) {
    setFeedback('请先选择设备。', 'error');
    return;
  }

  const availability = getControlAvailability();
  if (!availability.ok) {
    setFeedback(availability.reason, 'error');
    updateControlUi();
    return;
  }

  const stateInfo = state.selectedSnapshot.agentState || {};
  const isRunning = !!stateInfo.isRunning;
  const input = document.getElementById('commandInput');
  const command = input.value.trim();

  if (!isRunning && !command) {
    setFeedback('请输入要执行的指令。', 'error');
    return;
  }

  state.commandInFlight = true;
  updateControlUi();

  try {
    if (isRunning) {
      const response = await fetch(state.controlBaseUrl + '/api/agent/cancel', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
      });
      const data = await response.json();
      if (!response.ok) {
        throw new Error(data.error || ('HTTP ' + response.status));
      }
      setFeedback(data.message || '已请求停止当前任务。', 'success');
      applyOptimisticAgentState(false, data.message || '已请求停止');
    } else {
      const response = await fetch(state.controlBaseUrl + '/api/agent/command', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ command }),
      });
      const data = await response.json();
      if (!response.ok) {
        throw new Error(data.error || ('HTTP ' + response.status));
      }
      input.value = '';
      setFeedback(data.message || '指令已发送。', 'success');
      applyOptimisticAgentState(true, data.message || '正在启动手动指令...');
    }
    await loadDevice(state.selectedId);
    window.setTimeout(function() {
      loadDevice(state.selectedId);
    }, 1200);
  } catch (error) {
    setFeedback(error.message || '操作失败', 'error');
    await loadDevice(state.selectedId);
  } finally {
    state.commandInFlight = false;
    updateControlUi();
  }
}

document.getElementById('commandInput').addEventListener('keydown', function(event) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault();
    handleCommandButton();
  }
});

state.autoRefresh = getStoredAutoRefresh();
applyAutoRefresh(state.autoRefresh);
updateControlUi();
loadDevices();
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
