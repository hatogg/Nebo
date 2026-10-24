// Заглушка нативного мосту, щоб інтерфейс можна було відкрити у звичайному браузері.
(function () {
  const xhr = new XMLHttpRequest(); xhr.open('GET', '../map.json', false); xhr.send();
  const MAP = JSON.parse(xhr.responseText);
  const LV = {
    'Синельниківський район': 4, 'Павлоградський район': 4, 'Нікопольський район': 3, 'Краматорський район': 4, 'Покровський район': 4,
    'Бахмутський район': 4, 'Донецький район': 4, 'Запорізький район': 4, 'Пологівський район': 4, 'Харківський район': 4,
    'Куп\'янський район': 2, 'Чугуївський район': 2, 'Сумський район': 2, 'Чернігівський район': 2, 'Корюківський район': 2,
    'Вишгородський район': 2, 'Миколаївський район': 2, 'Полтавський район': 1
  };
  const lv = {};
  MAP.raions.forEach((r, i) => {
    const k = r.name.replace('’', '\'');
    if (LV[k]) lv[i] = LV[k];
    if (r.oblast === 'Луганська область' || r.oblast === 'Автономна Республіка Крим') lv[i] = 4;
  });
  const prefs = { home: 'Вишгородський район', faceStyle: 'map', timeStyle: 0, pushPolicy: 'all', autoPush: true, alertNotify: true, forward: false, yellowNotify: true, showRecent: true, pushInterval: 30 };
  const shortOf = (n) => { const r = MAP.regions.find((x) => x.name === n); if (r) return r.short; return n.replace(' район', ' р-н'); };
  const homeLevel = () => {
    const i = MAP.raions.findIndex((r) => r.name === prefs.home); if (i >= 0) return lv[i] || 0;
    let m = 0; MAP.raions.forEach((r, j) => { if (r.oblast === prefs.home) m = Math.max(m, lv[j] || 0); }); return m;
  };
  const counts = () => {
    const top = {}; MAP.raions.forEach((r, i) => { top[r.oblast] = Math.max(top[r.oblast] || 0, lv[i] || 0); });
    let red = 0, yellow = 0; Object.values(top).forEach((t) => { if (t >= 3) red++; else if (t === 2) yellow++; }); return [red, yellow];
  };
  const watch = { state: 'CONNECTED', address: 'AA:AC:BC:DF:15:6D', name: 'K61Pro', bound: true, authFailed: false, pushing: false, progress: 0, stage: '', lastPush: Date.now() - 6 * 60000, error: null, diag: 'формат GUI · lcd 31 · tool 1.6 · чип IC_8773 · слот 3/4 · стиль 1/5 · 182 КБ' };
  const emit = (t, d) => setTimeout(() => window.Nebo && Nebo.onEvent(t, d), 0);
  const alertsJson = () => { const [red, yellow] = counts(); return { fetchedAt: Date.now() - 8000, source: 'alerts.in.ua', red, yellow, home: prefs.home, homeShort: shortOf(prefs.home), homeLevel: homeLevel(), homePartial: false, homeSince: Date.now() - 23 * 60000, sig: JSON.stringify(lv) + prefs.home, error: null }; };
  const COL = { 4: '#E5383B', 3: '#A9282C', 2: '#F2B51E', 1: '#4A2A30', 0: '#252833' };

  function face() {
    const W = 410, H = 502, c = document.createElement('canvas'); c.width = W; c.height = H;
    const x = c.getContext('2d'); x.fillStyle = '#000'; x.fillRect(0, 0, W, H);
    const path = (rings) => { x.beginPath(); rings.forEach((ring) => { ring.split(' ').forEach((p, i) => { const [a, b] = p.split(',').map(Number); i ? x.lineTo(a, b) : x.moveTo(a, b); }); x.closePath(); }); };
    function map(ox, oy, w) {
      const s = w / MAP.w; x.save(); x.translate(ox, oy); x.scale(s, s);
      MAP.raions.forEach((r, i) => { path(r.rings); x.fillStyle = COL[prefs.showRecent ? (lv[i] || 0) : (lv[i] === 1 ? 0 : (lv[i] || 0))]; x.fill('evenodd'); x.lineWidth = 0.6 / s; x.strokeStyle = '#000'; x.stroke(); });
      MAP.regions.forEach((r) => { if (!r.city) { path(r.rings); x.lineWidth = 1.3 / s; x.strokeStyle = '#000'; x.stroke(); } });
      const hr = MAP.raions.find((r) => r.name === prefs.home) || MAP.regions.find((r) => r.name === prefs.home);
      if (hr && hr.rings && hr.rings.length) { path(hr.rings); x.lineWidth = 2.4 / s; x.strokeStyle = '#fff'; x.stroke(); }
      x.restore(); return MAP.h * s;
    }
    x.textAlign = 'center';
    const L = homeLevel(), [red, yellow] = counts();
    if (prefs.faceStyle === 'home') {
      const bg = L >= 3 ? '#3B0E12' : L === 2 ? '#3A2D05' : '#0F2A1B', fg = L >= 3 ? '#FF5A5F' : L === 2 ? '#FFC93C' : '#3DDC84';
      x.fillStyle = bg; x.beginPath(); x.roundRect(16, 166, 378, 160, 28); x.fill();
      x.fillStyle = fg; x.font = '46px Unb'; x.fillText(L >= 3 ? 'ТРИВОГА' : L === 2 ? 'ЗАГРОЗА' : 'ТИХО', 205, 234);
      x.fillStyle = '#fff'; x.font = '700 27px Man'; x.fillText(shortOf(prefs.home), 205, 272);
      x.fillStyle = '#ccc'; x.font = '600 20px Man'; x.fillText(L === 2 ? 'дрони · з 01:40' : L >= 3 ? 'з 01:40' : 'тривоги немає', 205, 306);
      map(16, 344, 200); x.textAlign = 'left';
      x.fillStyle = red ? '#E5383B' : '#3DDC84'; x.font = '50px Unb'; x.fillText(String(red), 234, 402);
      x.fillStyle = '#8A8F9C'; x.font = '600 17px Man'; x.fillText('областей', 234, 430); x.fillText('у тривозі', 234, 452);
      if (yellow) { x.fillStyle = '#F2B51E'; x.font = '700 16px Man'; x.fillText('+' + yellow + ' жовтий рівень', 234, 478); }
    } else {
      const mh = map(14, 172, 382), y = 172 + mh + 16;
      if (L >= 2) {
        const t = (L >= 3 ? 'ТРИВОГА · ' : 'ЗАГРОЗА · ') + shortOf(prefs.home).toUpperCase(); x.font = '19px Unb'; const tw = x.measureText(t).width;
        x.fillStyle = L >= 3 ? '#E5383B' : '#F2B51E'; x.beginPath(); x.roundRect(205 - tw / 2 - 18, y, tw + 36, 42, 21); x.fill();
        x.fillStyle = L >= 3 ? '#fff' : '#1A1300'; x.textBaseline = 'middle'; x.fillText(t, 205, y + 22);
      } else {
        x.font = '700 21px Man'; x.fillStyle = '#fff'; x.fillText('Тривога в ' + red + ' областях', 205, y + 22);
        x.font = '600 17px Man'; x.fillStyle = '#8A8F9C'; x.fillText('загроза ще в ' + yellow + ' · ' + shortOf(prefs.home) + ' — тихо', 205, y + 48);
      }
    }
    return c.toDataURL();
  }

  window.Android = {
    hello: () => true,
    getState: () => JSON.stringify({
      watch, alerts: alertsJson(), prefs,
      perm: { ble: true, notifications: true, notificationAccess: false, battery: false, calls: false },
      regions: MAP.regions.slice().sort((a, b) => a.short.localeCompare(b.short, 'uk')).map((r) => ({
        name: r.name, short: r.short,
        raions: MAP.raions.filter((d) => d.oblast === r.name).map((d) => ({ name: d.name, short: d.name.replace(' район', ' р-н') })).sort((a, b) => a.name.localeCompare(b.name, 'uk'))
      })),
      version: '1.1 (браузер)'
    }),
    getPreview: face,
    setPref: (k, v) => { prefs[k] = v === 'true' ? true : v === 'false' ? false : (k === 'timeStyle' || k === 'pushInterval' ? +v : v); },
    scan: (start) => {
      if (!start) return;
      setTimeout(() => emit('scan', { devices: [{ name: 'K61Pro', address: 'AA:AC:BC:DF:15:6D', rssi: -58 }], done: false }), 600);
      setTimeout(() => emit('scan', { devices: [{ name: 'K61Pro', address: 'AA:AC:BC:DF:15:6D', rssi: -58 }, { name: 'Watch 8', address: 'D0:11:22:33:44:55', rssi: -81 }], done: true }), 1600);
    },
    bind: (a, n) => { watch.address = a; watch.name = n; watch.state = 'CONNECTING'; emit('state', watch); setTimeout(() => { watch.state = 'CONNECTED'; emit('state', watch); }, 1500); },
    unbind: () => { watch.address = null; watch.state = 'DISCONNECTED'; emit('state', watch); },
    pushNow: () => {
      watch.pushing = true; let p = 0;
      const t = setInterval(() => { p += 10; watch.progress = p; watch.stage = p < 20 ? 'Готую циферблат…' : 'Надсилаю на годинник…'; emit('push', watch); if (p >= 100) { clearInterval(t); watch.pushing = false; watch.lastPush = Date.now(); watch.stage = 'Готово'; emit('push', watch); } }, 300);
    },
    refresh: () => emit('alerts', alertsJson()),
    getLog: () => '', copyLog: () => {}, shareLog: () => {},
    openNotificationAccess: () => {}, requestBattery: () => {}, requestCalls: () => {}, openUrl: () => {}
  };
})();
