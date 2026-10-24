// Заглушка нативного мосту, щоб інтерфейс можна було відкрити у звичайному браузері.
(function () {
  const xhr = new XMLHttpRequest(); xhr.open('GET', '../map.json', false); xhr.send();
  const MAP = JSON.parse(xhr.responseText);
  const active = ['Харківська область', 'Сумська область', 'Донецька область', 'Луганська область', 'Запорізька область', 'Дніпропетровська область', 'Херсонська область', 'Автономна Республіка Крим'];
  const prefs = { home: 'м. Київ', faceStyle: 'map', timeStyle: 0, pushPolicy: 'all', autoPush: true, alertNotify: true, forward: false };
  const short = (n) => (MAP.regions.find((r) => r.name === n) || {}).short || n;
  const oblasts = () => active.filter((n) => !(MAP.regions.find((r) => r.name === n) || {}).city).length;
  const watch = { state: 'CONNECTED', address: 'C3:1A:52:7E:90:44', name: 'K61 Pro', bound: true, authFailed: false, pushing: false, progress: 0, stage: '', lastPush: Date.now() - 6 * 60000, error: null };
  const emit = (t, d) => setTimeout(() => window.Nebo && Nebo.onEvent(t, d), 0);
  const alertsJson = () => ({ fetchedAt: Date.now() - 8000, active, count: oblasts(), home: prefs.home, homeShort: short(prefs.home), homeActive: active.includes(prefs.home), homeSince: Date.now() - 23 * 60000, error: null });

  function face() {
    const W = 410, H = 502, c = document.createElement('canvas'); c.width = W; c.height = H;
    const x = c.getContext('2d'); x.fillStyle = '#000'; x.fillRect(0, 0, W, H);
    const set = new Set(active);
    function map(ox, oy, w, bw, hw) {
      const s = w / MAP.w; x.save(); x.translate(ox, oy); x.scale(s, s);
      const path = (r) => { x.beginPath(); r.rings.forEach((ring) => { ring.split(' ').forEach((p, i) => { const [a, b] = p.split(',').map(Number); i ? x.lineTo(a, b) : x.moveTo(a, b); }); x.closePath(); }); };
      MAP.regions.forEach((r) => { if (r.city) return; path(r); x.fillStyle = set.has(r.name) ? '#E5383B' : '#252833'; x.fill('evenodd'); x.lineWidth = bw / s; x.strokeStyle = '#000'; x.stroke(); });
      const h = MAP.regions.find((r) => r.name === prefs.home); if (h && !h.city) { path(h); x.lineWidth = hw / s; x.strokeStyle = '#FFC857'; x.stroke(); }
      MAP.regions.forEach((r) => { if (!r.city) return; x.beginPath(); x.arc(r.c[0], r.c[1], (r.name === 'м. Київ' ? 4.2 : 3) / s, 0, 7); x.fillStyle = set.has(r.name) ? '#E5383B' : '#252833'; x.fill(); x.lineWidth = (r.name === prefs.home ? hw : 1.4) / s; x.strokeStyle = r.name === prefs.home ? '#FFC857' : '#000'; x.stroke(); });
      x.restore(); return MAP.h * s;
    }
    x.textAlign = 'center';
    if (prefs.faceStyle === 'home') {
      const on = set.has(prefs.home);
      x.fillStyle = on ? '#3B0E12' : '#0F2A1B'; x.beginPath(); x.roundRect(16, 166, 378, 160, 28); x.fill();
      x.fillStyle = on ? '#FF5A5F' : '#3DDC84'; x.font = '46px Unb'; x.fillText(on ? 'ТРИВОГА' : 'ТИХО', 205, 236);
      x.fillStyle = '#fff'; x.font = '700 28px Man'; x.fillText(short(prefs.home), 205, 274);
      x.fillStyle = on ? '#FFB3B5' : '#9FE3BD'; x.font = '600 20px Man'; x.fillText(on ? 'з 14:32' : 'тривоги немає', 205, 306);
      map(16, 344, 200, 0.9, 2); x.textAlign = 'left'; const n = oblasts();
      x.fillStyle = n ? '#E5383B' : '#3DDC84'; x.font = '52px Unb'; x.fillText(String(n), 234, 406);
      x.fillStyle = '#8A8F9C'; x.font = '600 18px Man'; x.fillText(n === 1 ? 'область' : n < 5 ? 'області' : 'областей', 234, 436); x.fillText('у тривозі', 234, 460);
    } else {
      const mh = map(14, 172, 382, 1.3, 2.6), y = 172 + mh + 16, n = oblasts();
      if (set.has(prefs.home)) {
        const t = 'ТРИВОГА · ' + short(prefs.home).toUpperCase(); x.font = '20px Unb'; const tw = x.measureText(t).width;
        x.fillStyle = '#E5383B'; x.beginPath(); x.roundRect(205 - tw / 2 - 18, y, tw + 36, 44, 22); x.fill(); x.fillStyle = '#fff'; x.textBaseline = 'middle'; x.fillText(t, 205, y + 23);
      } else {
        x.font = '700 22px Man'; x.fillStyle = n ? '#fff' : '#3DDC84'; x.fillText(n ? 'Тривога в ' + n + ' областях' : 'Тривог немає', 205, y + 22);
        x.font = '600 18px Man'; x.fillStyle = '#8A8F9C'; x.fillText(short(prefs.home) + ' — тихо', 205, y + 48);
      }
    }
    return c.toDataURL();
  }

  window.Android = {
    hello: () => true,
    getState: () => JSON.stringify({
      watch, alerts: alertsJson(), prefs,
      perm: { ble: true, notifications: true, notificationAccess: false, battery: false, calls: false },
      regions: MAP.regions.map((r) => ({ name: r.name, short: r.short })).sort((a, b) => a.short.localeCompare(b.short, 'uk')),
      version: '1.0 (браузер)'
    }),
    getPreview: face,
    setPref: (k, v) => { prefs[k] = v === 'true' ? true : v === 'false' ? false : (k === 'timeStyle' ? +v : v); },
    scan: (start) => {
      if (!start) return;
      setTimeout(() => emit('scan', { devices: [{ name: 'K61 Pro', address: 'C3:1A:52:7E:90:44', rssi: -58 }], done: false }), 600);
      setTimeout(() => emit('scan', { devices: [{ name: 'K61 Pro', address: 'C3:1A:52:7E:90:44', rssi: -58 }, { name: 'Watch 8', address: 'D0:11:22:33:44:55', rssi: -81 }], done: true }), 1600);
    },
    bind: (a, n) => { watch.address = a; watch.name = n; watch.state = 'CONNECTING'; emit('state', watch); setTimeout(() => { watch.state = 'CONNECTED'; emit('state', watch); }, 1500); },
    unbind: () => { watch.address = null; watch.state = 'DISCONNECTED'; emit('state', watch); },
    pushNow: () => {
      watch.pushing = true; let p = 0;
      const t = setInterval(() => { p += 10; watch.progress = p; watch.stage = p < 20 ? 'Готую циферблат…' : 'Надсилаю на годинник…'; emit('push', watch); if (p >= 100) { clearInterval(t); watch.pushing = false; watch.lastPush = Date.now(); watch.stage = 'Готово'; emit('push', watch); } }, 300);
    },
    refresh: () => emit('alerts', alertsJson()),
    openNotificationAccess: () => {}, requestBattery: () => {}, requestCalls: () => {}, openUrl: () => {}
  };
})();
