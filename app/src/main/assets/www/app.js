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
  function since(t) {
    const d = new Date(t), n = new Date(), p = (x) => String(x).padStart(2, '0');
    if (d.toDateString() === n.toDateString()) return hhmm(t);
    if (d.getFullYear() === n.getFullYear()) return p(d.getDate()) + '.' + p(d.getMonth() + 1) + ' ' + hhmm(t);
    return p(d.getDate()) + '.' + p(d.getMonth() + 1) + '.' + d.getFullYear();
  }
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
    btn.textContent = !has ? 'Підключіть годинник' : w.state !== 'CONNECTED' ? 'Немає зв\'язку' : w.pushing ? 'Надсилаю…' : 'Надіслати на годинник';
    $('pushBox').classList.toggle('hidden', !w.pushing);
    $('pushBar').style.width = (w.progress || 0) + '%';
    $('pushText').textContent = (w.stage || 'Готую…') + (w.progress ? ' ' + w.progress + '%' : '');
    $('lastPush').textContent = w.lastPush ? 'Оновлено на годиннику ' + ago(w.lastPush) : '';
    $('diagLine').textContent = w.diag ? 'Циферблат: ' + w.diag : '';
    $('capsLine').textContent = w.caps ? 'Ваш годинник підтримує: ' + w.caps : (w.address ? 'Що підтримує годинник, стане видно після першого підключення.' : '');
  }

  function renderAlerts() {
    const a = S.alerts;
    const hl = $('homeLine');
    const obl = (n) => n + ' ' + plural(n, 'області', 'областях', 'областях');
    const summary = a.red && a.yellow ? 'Тривога в ' + obl(a.red) + ', жовтий рівень ще в ' + a.yellow
      : a.red ? 'Тривога в ' + obl(a.red) : a.yellow ? 'Жовтий рівень в ' + obl(a.yellow) : 'Тривог в Україні немає';
    if (!a.fetchedAt) {
      hl.className = 'home-line'; hl.textContent = a.homeShort;
      $('countLine').textContent = a.error ? a.error : 'Отримую дані про тривоги…';
    } else if (a.homeLevel >= 3) {
      hl.className = 'home-line alert';
      hl.innerHTML = 'Тривога<span>' + esc(a.homeShort) + '</span>';
      $('countLine').textContent = (a.homePartial || a.homeLevel === 3 ? 'У частині. ' : '') + (a.homeSince ? 'З ' + since(a.homeSince) + '. ' : '') + summary;
    } else if (a.homeLevel === 2) {
      hl.className = 'home-line yellow';
      hl.innerHTML = 'Жовтий рівень<span>' + esc(a.homeShort) + '</span>';
      $('countLine').textContent = 'Загроза дронів' + (a.homeSince ? ' з ' + since(a.homeSince) : '') + '. ' + summary;
    } else {
      hl.className = 'home-line calm';
      hl.innerHTML = 'Тихо<span>' + esc(a.homeShort) + '</span>';
      $('countLine').textContent = (a.homeLevel === 1 ? 'Щойно був відбій. ' : '') + summary;
    }
    $('homeName').textContent = a.homeShort;
    $('srcLine').textContent = a.error ? 'Помилка: ' + a.error : (a.fetchedAt ? 'Дані оновлено ' + ago(a.fetchedAt) + ' · ' + (a.source === 'alerts.in.ua' ? 'по районах' : 'лише по областях') : '');
  }

  function setSeg(id, v) { $(id).querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.v === String(v))); }

  function renderPrefs() {
    const p = S.prefs;
    setSeg('faceSeg', p.faceStyle);
    setSeg('policySeg', p.pushPolicy);
    setSeg('intervalSeg', p.pushInterval);
    $('yellowNotify').checked = p.yellowNotify;
    $('showRecent').checked = p.showRecent;
    $('statusCard').checked = p.statusCard;
    $('statusWeather').checked = p.statusWeather;
    $('intervalSeg').style.opacity = p.autoPush && p.pushPolicy === 'all' ? 1 : 0.4;
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
  function chooseHome(name) {
    A.setPref('home', name); closeSheet(); load(); toast('Збережено');
  }
  function regionSheet() {
    const cur = S.prefs.home;
    openSheet('region', '<h3>Мій район чи область</h3><p class="muted small">Тривоги тепер оголошують по районах, тож точніше обрати свій район.</p><input class="search" id="rq" placeholder="Пошук області чи району"><div class="list" id="rl"></div>');
    const draw = (q) => {
      q = (q || '').toLowerCase();
      let html = '';
      if (!q) {
        html = S.regions.map((r) => '<button class="item' + (r.name === cur ? ' on' : '') + '" data-o="' + esc(r.name) + '"><span class="grow"><b>' + esc(r.short) + '</b><div class="muted small">' + esc(r.name) + (r.raions.length ? ' · ' + r.raions.length + ' ' + plural(r.raions.length, 'район', 'райони', 'районів') : '') + '</div></span></button>').join('');
      } else {
        const hits = [];
        S.regions.forEach((r) => {
          if (r.short.toLowerCase().includes(q) || r.name.toLowerCase().includes(q)) hits.push({ n: r.name, t: r.short, s: r.name });
          r.raions.forEach((d) => { if (d.name.toLowerCase().includes(q)) hits.push({ n: d.name, t: d.short, s: r.short }); });
        });
        html = hits.slice(0, 40).map((h) => '<button class="item' + (h.n === cur ? ' on' : '') + '" data-n="' + esc(h.n) + '"><span class="grow"><b>' + esc(h.t) + '</b><div class="muted small">' + esc(h.s) + '</div></span></button>').join('') || '<p class="muted">Нічого не знайдено</p>';
      }
      $('rl').innerHTML = html;
      $('rl').querySelectorAll('.item[data-n]').forEach((b) => b.onclick = () => chooseHome(b.dataset.n));
      $('rl').querySelectorAll('.item[data-o]').forEach((b) => b.onclick = () => raionSheet(b.dataset.o));
    };
    draw('');
    $('rq').oninput = (e) => draw(e.target.value);
  }
  function raionSheet(oblast) {
    const r = S.regions.find((x) => x.name === oblast);
    if (!r || !r.raions.length) { chooseHome(oblast); return; }
    const cur = S.prefs.home;
    openSheet('raion', '<h3>' + esc(r.short) + '</h3><button class="link small" id="backReg">← Усі області</button>' +
      '<div class="list"><button class="item' + (cur === r.name ? ' on' : '') + '" data-n="' + esc(r.name) + '"><span class="grow"><b>Уся область</b><div class="muted small">Тривога, якщо вона хоч в одному районі</div></span></button>' +
      r.raions.map((d) => '<button class="item' + (d.name === cur ? ' on' : '') + '" data-n="' + esc(d.name) + '"><span class="grow"><b>' + esc(d.name) + '</b></span></button>').join('') + '</div>');
    $('sheetBody').querySelectorAll('.item[data-n]').forEach((b) => b.onclick = () => chooseHome(b.dataset.n));
    $('backReg').onclick = regionSheet;
  }
  function logSheet() {
    openSheet('log', '<h3>Журнал для розробника</h3><p class="muted small">Якщо щось не працює, надішліть цей журнал — там немає особистих даних, лише стан годинника й помилки.</p>' +
      '<button class="btn primary full" id="shareLog">Надіслати журнал</button><button class="btn ghost full" id="copyLog">Скопіювати</button>');
    $('shareLog').onclick = () => A.shareLog();
    $('copyLog').onclick = () => { A.copyLog(); toast('Журнал скопійовано'); };
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
  $('logBtn').onclick = logSheet;
  $('statusCard').onchange = (e) => { A.setPref('statusCard', String(e.target.checked)); S.prefs.statusCard = e.target.checked; };
  $('statusWeather').onchange = (e) => { A.setPref('statusWeather', String(e.target.checked)); S.prefs.statusWeather = e.target.checked; };
  $('intervalSeg').onclick = (e) => { const v = e.target.dataset && e.target.dataset.v; if (!v) return; A.setPref('pushInterval', v); S.prefs.pushInterval = +v; renderPrefs(); };
  $('yellowNotify').onchange = (e) => { A.setPref('yellowNotify', String(e.target.checked)); S.prefs.yellowNotify = e.target.checked; };
  $('showRecent').onchange = (e) => { A.setPref('showRecent', String(e.target.checked)); S.prefs.showRecent = e.target.checked; refreshPreview(); };
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
        case 'toast':
          toast(data.message);
          break;
        case 'alerts': {
          S.alerts = data; renderAlerts();
          const sig = (data.sig || '') + data.home;
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
