(function () {
  'use strict';
  const A = window.Android;
  const $ = (id) => document.getElementById(id);
  let S = null;            // повний стан з нативу
  let scanList = [];
  let sheetKind = null;
  let lastAlertsSig = '';

  const TIME_COLORS = ['#ffffff', '#111111', '#FFD338', '#7CF06A', '#9AA0AA'];
  const STATE_TEXT = {
    CONNECTED: 'На зв\'язку', PRE_CONNECTED: 'Налаштовую…', CONNECTING: 'Підключаюсь…',
    PRE_CONNECTING: 'Чекаю на годинник…', DISCONNECTED: 'Не на зв\'язку'
  };

  function plural(n, a, b, c) { const m10 = n % 10, m100 = n % 100; if (m10 === 1 && m100 !== 11) return a; if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return b; return c; }
  function hhmm(t) { const d = new Date(t); return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); }
  function ago(t) {
    if (!t) return '';
    const s = Math.round((Date.now() - t) / 1000);
    if (s < 60) return 'щойно';
    const m = Math.round(s / 60);
    if (m < 60) return m + ' хв тому';
    const d = new Date(t), now = new Date();
    return (d.toDateString() === now.toDateString() ? 'сьогодні о ' : d.toLocaleDateString('uk-UA') + ' о ') + hhmm(t);
  }
  function toast(msg) {
    const t = $('toast'); t.textContent = msg; t.classList.add('show');
    clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('show'), 2600);
  }
  function esc(s) { return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

  function load() {
    S = JSON.parse(A.getState());
    renderAll();
    refreshPreview();
  }

  function refreshPreview() {
    try { $('preview').src = A.getPreview(); } catch (e) { /* ignore */ }
  }

  function renderAll() { renderWatch(); renderAlerts(); renderPrefs(); renderPerm(); $('ver').textContent = 'Небо ' + (S.version || ''); }

  function renderWatch() {
    const w = S.watch;
    const chip = $('chip');
    const has = !!w.address;
    chip.className = 'chip ' + (!has ? 'off' : w.state === 'CONNECTED' ? 'on' : 'wait');
    chip.querySelector('b').textContent = !has ? 'Немає годинника' : (w.state === 'CONNECTED' ? 'Підключено' : STATE_TEXT[w.state] || '…');
    $('devNone').classList.toggle('hidden', has);
    $('devSome').classList.toggle('hidden', !has);
    if (has) {
      $('devName').textContent = w.name || 'Годинник';
      $('devState').textContent = (STATE_TEXT[w.state] || w.state) + ' · ' + w.address;
    }
    $('authBox').classList.toggle('hidden', !w.authFailed);
    $('errBox').classList.toggle('hidden', !w.error || w.pushing);
    if (w.error) $('errBox').textContent = w.error;

    const btn = $('pushBtn');
    btn.disabled = !has || w.state !== 'CONNECTED' || w.pushing;
    btn.textContent = !has ? 'Спершу підключіть годинник' : w.state !== 'CONNECTED' ? 'Годинник не на зв\'язку' : w.pushing ? 'Надсилаю…' : 'Надіслати на годинник';
    $('pushBox').classList.toggle('hidden', !w.pushing);
    $('pushBar').style.width = (w.progress || 0) + '%';
    $('pushText').textContent = (w.stage || 'Готую…') + (w.progress ? ' ' + w.progress + '%' : '');
    $('lastPush').textContent = w.lastPush ? 'Оновлено на годиннику ' + ago(w.lastPush) : '';
  }

  function renderAlerts() {
    const a = S.alerts;
    const hl = $('homeLine');
    if (!a.fetchedAt) {
      hl.className = 'home-line'; hl.textContent = a.homeShort;
      $('countLine').textContent = a.error ? a.error : 'Отримую дані про тривоги…';
    } else if (a.homeActive) {
      hl.className = 'home-line alert';
      hl.textContent = 'Тривога · ' + a.homeShort;
      $('countLine').textContent = (a.homeSince ? 'з ' + hhmm(a.homeSince) + '. ' : '') + 'Всього ' + a.count + ' ' + plural(a.count, 'область', 'області', 'областей');
    } else {
      hl.className = 'home-line calm';
      hl.textContent = a.homeShort + ' — тихо';
      $('countLine').textContent = a.count ? 'Тривога в ' + a.count + ' ' + plural(a.count, 'області', 'областях', 'областях') : 'Тривог в Україні немає';
    }
    $('homeName').textContent = a.homeShort;
    $('srcLine').textContent = a.error ? 'Помилка: ' + a.error : (a.fetchedAt ? 'Дані оновлено ' + ago(a.fetchedAt) + ' · перевірка кожні 15 с' : '');
  }

  function setSeg(id, v) { $(id).querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.v === String(v))); }

  function renderPrefs() {
    const p = S.prefs;
    setSeg('faceSeg', p.faceStyle);
    setSeg('policySeg', p.pushPolicy);
    $('timeSw').querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.v === String(p.timeStyle)));
    $('alertNotify').checked = p.alertNotify;
    $('autoPush').checked = p.autoPush;
    $('policySeg').style.opacity = p.autoPush ? 1 : 0.4;
    $('forward').checked = p.forward;
    const tc = TIME_COLORS[p.timeStyle] || '#fff';
    document.querySelector('.time').style.color = tc;
    const d = new Date();
    $('fakeTime').textContent = hhmm(d.getTime());
    $('fakeDate').textContent = ['НД', 'ПН', 'ВТ', 'СР', 'ЧТ', 'ПТ', 'СБ'][d.getDay()] + ' ' + d.getDate();
  }

  function renderPerm() {
    const p = S.perm;
    $('accessBox').classList.toggle('hidden', !(S.prefs.forward && !p.notificationAccess));
    $('callsState').textContent = p.calls ? 'Увімкнено' : 'Показувати вхідні й відхиляти з годинника';
    $('batteryState').textContent = p.battery ? 'Увімкнено' : 'Щоб тривоги приходили вчасно';
  }

  // ---------- аркуші ----------
  function openSheet(kind, html) {
    sheetKind = kind;
    $('sheetBody').innerHTML = html;
    $('sheet').classList.remove('hidden');
  }
  function closeSheet() {
    if (sheetKind === 'scan') A.scan(false);
    sheetKind = null;
    $('sheet').classList.add('hidden');
  }
  $('sheet').addEventListener('click', (e) => { if (e.target.hasAttribute('data-close')) closeSheet(); });

  function scanSheet() {
    scanList = [];
    openSheet('scan', '<h3>Пошук годинника</h3><p class="muted small">Тримайте годинник поруч і ввімкненим. Якщо він підключений до FitCloudPro — спершу відв\'яжіть його там або закрийте FitCloudPro.</p><div id="scanStatus" class="muted small"><span class="spin"></span>Шукаю…</div><div id="scanList" class="list"></div>');
    A.scan(true);
  }
  function renderScan(done, error) {
    if (sheetKind !== 'scan') return;
    const st = $('scanStatus');
    st.innerHTML = error ? esc(error) : done ? (scanList.length ? 'Оберіть свій годинник' : 'Нічого не знайдено. <button class="link" id="rescan">Шукати ще раз</button>') : '<span class="spin"></span>Шукаю…';
    const r = $('rescan'); if (r) r.onclick = () => { st.innerHTML = '<span class="spin"></span>Шукаю…'; A.scan(true); };
    $('scanList').innerHTML = scanList.map((d, i) => {
      const lvl = d.rssi > -60 ? 4 : d.rssi > -70 ? 3 : d.rssi > -80 ? 2 : 1;
      const bars = [1, 2, 3, 4].map((k) => '<i class="' + (k <= lvl ? 'on' : '') + '" style="height:' + (k * 4) + 'px"></i>').join('');
      return '<button class="item" data-i="' + i + '"><span class="grow"><b>' + esc(d.name) + '</b><div class="muted small">' + esc(d.address) + '</div></span><span class="sig">' + bars + '</span></button>';
    }).join('');
    $('scanList').querySelectorAll('.item').forEach((b) => b.onclick = () => confirmBind(scanList[+b.dataset.i]));
  }
  function confirmBind(d) {
    A.scan(false);
    openSheet('confirm', '<h3>Прив\'язати «' + esc(d.name) + '»?</h3>' +
      '<p class="muted">Годинник перейде від FitCloudPro до «Неба». Кроки, сон і пульс, збережені на ньому, буде очищено. Якщо годинник попросить підтвердження — натисніть «так» на його екрані.</p>' +
      '<div class="btns"><button class="btn ghost" data-close>Скасувати</button><button class="btn primary" id="doBind">Прив\'язати</button></div>');
    $('doBind').onclick = () => { A.bind(d.address, d.name); closeSheet(); toast('Підключаюсь до годинника…'); };
  }
  function regionSheet() {
    const cur = S.prefs.home;
    openSheet('region', '<h3>Моя область</h3><input class="search" id="rq" placeholder="Пошук"><div class="list" id="rl"></div>');
    const draw = (q) => {
      q = (q || '').toLowerCase();
      $('rl').innerHTML = S.regions.filter((r) => !q || r.short.toLowerCase().includes(q) || r.name.toLowerCase().includes(q))
        .map((r) => '<button class="item' + (r.name === cur ? ' on' : '') + '" data-n="' + esc(r.name) + '"><span class="grow"><b>' + esc(r.short) + '</b><div class="muted small">' + esc(r.name) + '</div></span></button>').join('');
      $('rl').querySelectorAll('.item').forEach((b) => b.onclick = () => {
        A.setPref('home', b.dataset.n); closeSheet(); load();
        toast('Область змінено');
      });
    };
    draw('');
    $('rq').oninput = (e) => draw(e.target.value);
  }

  // ---------- події інтерфейсу ----------
  $('findBtn').onclick = scanSheet;
  $('changeBtn').onclick = scanSheet;
  $('rebindBtn').onclick = () => { A.bind(S.watch.address, S.watch.name || 'Годинник'); toast('Прив\'язую знову…'); };
  $('unbindBtn').onclick = () => {
    openSheet('confirm', '<h3>Відв\'язати годинник?</h3><p class="muted">«Небо» перестане оновлювати циферблат. Після цього годинник можна знову підключити до FitCloudPro.</p><div class="btns"><button class="btn ghost" data-close>Скасувати</button><button class="btn primary" id="doUnbind">Відв\'язати</button></div>');
    $('doUnbind').onclick = () => { A.unbind(); closeSheet(); };
  };
  $('pushBtn').onclick = () => { A.pushNow(); toast('Готую циферблат…'); };
  $('homeBtn').onclick = regionSheet;
  $('faceSeg').onclick = (e) => { const v = e.target.dataset && e.target.dataset.v; if (!v) return; A.setPref('faceStyle', v); S.prefs.faceStyle = v; renderPrefs(); refreshPreview(); };
  $('policySeg').onclick = (e) => { const v = e.target.dataset && e.target.dataset.v; if (!v) return; A.setPref('pushPolicy', v); S.prefs.pushPolicy = v; renderPrefs(); };
  $('timeSw').onclick = (e) => { const b = e.target.closest('button'); if (!b) return; A.setPref('timeStyle', b.dataset.v); S.prefs.timeStyle = +b.dataset.v; renderPrefs(); };
  $('alertNotify').onchange = (e) => { A.setPref('alertNotify', String(e.target.checked)); S.prefs.alertNotify = e.target.checked; };
  $('autoPush').onchange = (e) => { A.setPref('autoPush', String(e.target.checked)); S.prefs.autoPush = e.target.checked; renderPrefs(); };
  $('forward').onchange = (e) => {
    A.setPref('forward', String(e.target.checked)); S.prefs.forward = e.target.checked; renderPerm();
    if (e.target.checked && !S.perm.notificationAccess) A.openNotificationAccess();
  };
  $('accessBtn').onclick = () => A.openNotificationAccess();
  $('callsBtn').onclick = () => A.requestCalls();
  $('batteryBtn').onclick = () => A.requestBattery();

  // ---------- події з нативу ----------
  window.Nebo = {
    onEvent(type, data) {
      if (!S) return;
      switch (type) {
        case 'state': case 'push':
          S.watch = data; renderWatch();
          if (type === 'push' && !data.pushing && data.progress === 100) toast('Циферблат оновлено');
          break;
        case 'error':
          S.watch.error = data.message; renderWatch(); toast(data.message);
          break;
        case 'alerts': {
          S.alerts = data; renderAlerts();
          const sig = (data.active || []).slice().sort().join('|') + data.home;
          if (sig !== lastAlertsSig) { lastAlertsSig = sig; refreshPreview(); }
          break;
        }
        case 'scan':
          scanList = data.devices || []; renderScan(data.done, data.error);
          break;
        case 'perm':
          S.perm = data; renderPerm();
          break;
        case 'resume':
          load();
          break;
      }
    },
    back() {
      if (sheetKind) { closeSheet(); return true; }
      return false;
    }
  };

  setInterval(() => { if (S) { renderPrefs(); renderAlerts(); renderWatch(); } }, 30000);
  load();
})();
