/*
 * Navegación con el control remoto para ondamedia.cl (Fire TV).
 *
 * La app nativa llama a window.__tv.key('up'|'down'|'left'|'right'|'enter') en cada tecla.
 * key() devuelve true si la manejó; si devuelve false, la app le pasa la tecla al WebView
 * tal cual (por ejemplo, para mover el cursor de texto o controlar un reproductor en iframe).
 *
 * También fuerza la versión de escritorio del sitio (ancho CSS fijo) y agrega estilos para TV.
 */
(function () {
  'use strict';
  if (window.__tv) { window.__tv.init(); return; }

  var CSS_WIDTH = 1280;
  var FOCUS = 'tv-focus';
  var SEL = 'a[href],button:not([disabled]),input:not([type=hidden]):not([disabled]),select,textarea,' +
            '[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[tabindex]:not([tabindex="-1"])';
  // Flechas y puntos de los carruseles: el carrusel se mueve solo al llegar a su borde.
  var EXCLUDE = '.swiper-button-prev,.swiper-button-next,.swiper-pagination,.swiper-pagination-bullet,' +
                '.button-chevron,[aria-label$="carrusel"]';

  var current = null;
  var pointerEls = [];
  var pointerDirty = true;
  var lastUrl = location.href;
  var lastKeyTime = 0;

  // ---------- Viewport y estilos ----------

  function fixViewport() {
    var content = 'width=' + CSS_WIDTH;
    var m = document.querySelector('meta[name=viewport]');
    if (!m) {
      if (!document.head) return;
      m = document.createElement('meta');
      m.name = 'viewport';
      document.head.appendChild(m);
    }
    if (m.getAttribute('content') !== content) m.setAttribute('content', content);
  }

  function addStyle() {
    if (document.getElementById('tv-style') || !document.head) return;
    var s = document.createElement('style');
    s.id = 'tv-style';
    s.textContent =
      // Marco de selección: un div propio sobre la página (React no lo toca y no lo recortan
      // los carruseles). Se desliza de un elemento a otro.
      '#tv-ring{position:fixed;z-index:2147483647;pointer-events:none;display:none;' +
      'border:4px solid #fff;border-radius:8px;box-sizing:border-box;' +
      'box-shadow:0 0 0 2px rgba(0,0,0,.6),0 0 24px rgba(255,255,255,.35);' +
      'transition:left .14s ease-out,top .14s ease-out,width .14s ease-out,height .14s ease-out;}' +
      '.preview-container-inner{transition:filter .14s ease-out;}' +
      '.preview-container-inner.' + FOCUS + '{filter:brightness(1.15);}' +
      'a:focus,button:focus,iframe:focus,[tabindex]:focus{outline:none!important;}' +
      '.swiper-button-prev,.swiper-button-next,.button-chevron{display:none!important;}' +
      'iframe.tv-player{position:fixed!important;left:0!important;top:0!important;width:100vw!important;' +
      'height:100vh!important;max-width:none!important;max-height:none!important;' +
      'z-index:2147483646!important;background:#000!important;border:0!important;}' +
      'html,body{scrollbar-width:none;}::-webkit-scrollbar{display:none;}';
    document.head.appendChild(s);
  }

  // ---------- Candidatos ----------

  function scanPointerEls() {
    // Elementos que el sitio hace clicables solo con CSS (cursor:pointer), sin ser botones.
    var out = [];
    var all = document.body ? document.body.getElementsByTagName('*') : [];
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (e.matches(SEL) || e.closest(SEL)) continue;
      if (getComputedStyle(e).cursor !== 'pointer') continue;
      var p = e.parentElement;
      if (p && getComputedStyle(p).cursor === 'pointer') continue;
      out.push(e);
    }
    pointerEls = out;
    pointerDirty = false;
  }

  // El escaneo es caro en el Fire TV, así que solo se hace al presionar una tecla
  // y como mucho cada 1,5 s, y solo si el DOM cambió.
  var lastScan = 0;
  function scheduleScan() { pointerDirty = true; }

  function hiddenSlide(e, r) {
    var slide = e.closest('.swiper-slide');
    if (!slide) return false;
    if (getComputedStyle(slide).opacity === '0') return true;
    var box = slide.closest('.swiper-container,.swiper');
    if (!box) return false;
    var b = box.getBoundingClientRect();
    // La tarjeta tiene que asomarse al menos un poco dentro de su carrusel.
    return r.right <= b.left + 8 || r.left >= b.right - 8;
  }

  function usable(e) {
    if (!e.isConnected || e.matches(EXCLUDE) || e.closest(EXCLUDE)) return null;
    var r = e.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return null;
    if (r.right <= 0 || r.left >= innerWidth) return null;
    var cs = getComputedStyle(e);
    if (cs.visibility === 'hidden' || cs.pointerEvents === 'none') return null;
    if (e.closest('[aria-hidden=true],[inert]')) return null;
    if (hiddenSlide(e, r)) return null;
    return r;
  }

  function candidates() {
    if (pointerDirty && Date.now() - lastScan > 1500) { lastScan = Date.now(); scanPointerEls(); }
    var list = Array.prototype.slice.call(document.querySelectorAll(SEL)).concat(pointerEls);
    var out = [];
    var seen = new Set();
    for (var i = 0; i < list.length; i++) {
      var e = list[i];
      if (seen.has(e)) continue;
      seen.add(e);
      var r = usable(e);
      if (!r) continue;
      // Si un candidato contiene a otro (p. ej. un <a> que envuelve un <button>), nos quedamos con el interno.
      out.push({ el: e, r: r });
    }
    return out.filter(function (c) {
      for (var j = 0; j < out.length; j++) {
        if (out[j].el !== c.el && c.el.contains(out[j].el)) return false;
      }
      return true;
    });
  }

  // ---------- Foco ----------

  function inFixed(e) {
    for (var p = e; p && p !== document.body; p = p.parentElement) {
      var pos = getComputedStyle(p).position;
      if (pos === 'fixed' || pos === 'sticky') return true;
    }
    return false;
  }

  function label(e) {
    var t = ((e.getAttribute('aria-label') || '') + ' ' + (e.innerText || '')).trim();
    // Botones que son solo un ícono: el texto ("Ver ahora") suele estar al lado, en el contenedor.
    if (!t && e.parentElement) t = (e.parentElement.innerText || '').trim();
    return t.toLowerCase();
  }

  // Última selección de cada página, para dejarla igual al volver con Atrás.
  var memory = {};
  function keyOf(e) {
    return e.tagName + '|' + (e.getAttribute('aria-label') || (e.innerText || '').slice(0, 60));
  }

  function restoreFocus() {
    var saved = memory[location.pathname];
    if (!saved) return null;
    var cs = candidates();
    for (var i = 0; i < cs.length; i++) {
      if (keyOf(cs[i].el) === saved) return cs[i].el;
    }
    return null;
  }

  // ---------- Marco de selección ----------

  var ring = null;
  var ringBox = '';
  function updateRing() {
    if (!ring || !ring.isConnected) {
      if (!document.body) return;
      ring = document.createElement('div');
      ring.id = 'tv-ring';
      document.body.appendChild(ring);
      ringBox = '';
    }
    var e = current;
    if (!e || !e.isConnected) {
      if (ringBox !== 'none') { ring.style.display = 'none'; ringBox = 'none'; }
      return;
    }
    if (!e.classList.contains(FOCUS)) e.classList.add(FOCUS);  // por si React la borró
    var r = e.getBoundingClientRect();
    // Las tarjetas se marcan por dentro; lo demás (botones, enlaces) con un margen alrededor.
    var pad = e.classList.contains('preview-container-inner') ? 0 : 6;
    var radius = parseFloat(getComputedStyle(e).borderTopLeftRadius) || 0;
    var box = Math.round(r.left - pad) + ',' + Math.round(r.top - pad) + ',' +
              Math.round(r.width + pad * 2) + ',' + Math.round(r.height + pad * 2) + ',' + radius;
    if (box === ringBox) return;
    var first = ringBox === '' || ringBox === 'none';
    ringBox = box;
    var st = ring.style;
    // Solo se anima al cambiar de elemento; si el mismo elemento se mueve (scroll, carrusel),
    // el marco lo sigue sin retraso.
    var glide = !first && ring._el !== e;
    ring._el = e;
    st.transition = glide ? '' : 'none';
    st.display = 'block';
    st.left = Math.round(r.left - pad) + 'px';
    st.top = Math.round(r.top - pad) + 'px';
    st.width = Math.round(r.width + pad * 2) + 'px';
    st.height = Math.round(r.height + pad * 2) + 'px';
    st.borderRadius = (radius >= r.height / 2 - 1 ? '9999px' : Math.max(radius + pad, 6) + 'px');
  }

  (function loop() { updateRing(); requestAnimationFrame(loop); })();

  function setFocus(e, dir, smooth) {
    if (current && current !== e) current.classList.remove(FOCUS);
    current = e;
    memory[location.pathname] = keyOf(e);
    e.classList.add(FOCUS);
    var active = document.activeElement;
    if (isTextField(e)) {
      // Un campo de texto solo se activa con OK (si no, las flechas moverían el cursor de texto).
      if (active && active !== document.body && active !== e) active.blur();
    } else {
      try { e.focus({ preventScroll: true }); } catch (err) {}
    }
    reveal(e, dir, smooth);
  }

  function reveal(e, dir, smooth) {
    var box = e.closest('.swiper-container,.swiper');
    var sw = box && box.swiper;
    if (sw) {
      // Si el carrusel avanza solo (el banner de arriba), lo detenemos para que no se lleve la selección.
      try { if (sw.autoplay && sw.autoplay.running) sw.autoplay.stop(); } catch (err) {}
      var b = box.getBoundingClientRect();
      var r = e.getBoundingClientRect();
      if (r.right > b.right + 2) sw.slideNext();
      else if (r.left < b.left - 2) sw.slidePrev();
    } else {
      // Contenedores con scroll horizontal nativo (no Swiper).
      for (var p = e.parentElement; p && p !== document.body; p = p.parentElement) {
        if (p.scrollWidth > p.clientWidth + 4 && /(auto|scroll)/.test(getComputedStyle(p).overflowX)) {
          var pr = p.getBoundingClientRect(), er = e.getBoundingClientRect();
          if (er.left < pr.left) p.scrollBy({ left: er.left - pr.left - 40, behavior: smooth ? 'smooth' : 'auto' });
          else if (er.right > pr.right) p.scrollBy({ left: er.right - pr.right + 40, behavior: smooth ? 'smooth' : 'auto' });
          break;
        }
      }
    }
    if (inFixed(e)) return;
    var rr = e.getBoundingClientRect();
    var top = scrollY + rr.top + rr.height / 2 - innerHeight * 0.5;
    // Cerca del inicio de la página volvemos arriba del todo, para ver el banner completo.
    if (scrollY + rr.top < innerHeight * 0.75) top = 0;
    top = Math.max(0, top);
    if (Math.abs(top - scrollY) > 4) window.scrollTo({ top: top, behavior: smooth ? 'smooth' : 'auto' });
  }

  function pickInitial(lastResort) {
    var cs = candidates();
    var cookie = cs.filter(function (c) { return /rechazar cookies/.test(label(c.el)); })[0];
    if (cookie) return cookie.el;
    var onScreen = cs.filter(function (c) {
      return c.r.bottom > 0 && c.r.top < innerHeight && !inFixed(c.el) && !c.el.closest('header,footer') &&
             !/mdstrm/.test(String(c.el.className));
    });
    onScreen.sort(function (a, b) { return (a.r.top - b.r.top) || (a.r.left - b.r.left); });
    var play = onScreen.filter(function (c) {
      return c.el.querySelector('[data-icon=play]') || /ver ahora|reproducir|continuar viendo|^play$/.test(label(c.el));
    })[0];
    if (play) return play.el;
    var input = onScreen.filter(function (c) { return /^(INPUT|TEXTAREA)$/.test(c.el.tagName); })[0];
    if (input) return input.el;
    if (onScreen.length) return onScreen[0].el;
    if (!lastResort) return null;
    // Solo el encabezado a la vista: lo usamos únicamente si no aparece otra cosa.
    var any = cs.filter(function (c) { return c.r.bottom > 0 && c.r.top < innerHeight; });
    return any.length ? any[0].el : null;
  }

  function onScreenNow(e) {
    var r = e.getBoundingClientRect();
    return r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;
  }

  /** Marca el primer elemento útil; si la página aún no carga su contenido, reintenta. */
  function focusInitialSoon(tries) {
    if ((current && current.isConnected) || tries < 0) return;
    // Si había una selección guardada, le damos ~2 s a la página para que vuelva a dibujarla.
    var f = restoreFocus() || (memory[location.pathname] && tries > 5 ? null : pickInitial(tries <= 0));
    if (f) { setFocus(f, null, false); return; }
    setTimeout(function () { focusInitialSoon(tries - 1); }, 700);
  }

  // ---------- Navegación espacial ----------

  function overlapV(a, b) {
    return Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
  }

  function findNext(dir) {
    var cur = current.getBoundingClientRect();
    var cx = cur.left + cur.width / 2, cy = cur.top + cur.height / 2;
    var cs = candidates().filter(function (c) { return c.el !== current; });
    var best = null, bestScore = Infinity, i, c;

    if (dir === 'left' || dir === 'right') {
      // Misma fila: tiene que haber traslape vertical.
      for (i = 0; i < cs.length; i++) {
        c = cs[i];
        var ccx = c.r.left + c.r.width / 2;
        if (dir === 'right' ? ccx <= cx + 2 : ccx >= cx - 2) continue;
        var ov = overlapV(cur, c.r);
        if (ov < Math.min(cur.height, c.r.height) * 0.4) continue;
        var score = Math.abs(ccx - cx) + Math.abs((c.r.top + c.r.height / 2) - cy) * 2;
        if (score < bestScore) { bestScore = score; best = c.el; }
      }
      return best;
    }

    // Arriba/abajo: distancia vertical + desvío horizontal (que pesa el doble), para que al bajar
    // desde el banner se vaya a la fila de películas y no a un botón chico en la otra esquina.
    for (i = 0; i < cs.length; i++) {
      c = cs[i];
      var ccy = c.r.top + c.r.height / 2;
      if (dir === 'down' ? (c.r.top < cur.top + cur.height * 0.5 || ccy <= cy + 4)
                         : (c.r.bottom > cur.bottom - cur.height * 0.5 || ccy >= cy - 4)) continue;
      var gap = dir === 'down' ? Math.max(0, c.r.top - cur.bottom) : Math.max(0, cur.top - c.r.bottom);
      var l = Math.max(0, c.r.left), rgt = Math.min(innerWidth, c.r.right);
      var dx = cx < l ? l - cx : (cx > rgt ? cx - rgt : 0);
      var s = gap + dx * 2 + Math.abs((l + rgt) / 2 - cx) * 0.1;
      // Los elementos bajitos (como el título "RECIÉN LLEGADAS · Ver todo") se saltan al bajar entre
      // filas de películas, salvo que no haya otra cosa en esa dirección.
      if (c.r.height < 32 && cur.height >= 32) s += 320;
      if (s < bestScore) { bestScore = s; best = c.el; }
    }
    return best;
  }

  function move(dir, repeat) {
    if (!current || !current.isConnected || !usable(current) || !onScreenNow(current)) {
      var first = pickInitial(true);
      if (first) setFocus(first, dir, !repeat);
      return true;
    }
    var next = findNext(dir);
    if (next) { setFocus(next, dir, !repeat); return true; }

    // Final de un carrusel: lo avanzamos y reintentamos cuando termine la animación.
    var box = current.closest('.swiper-container,.swiper');
    if (box && box.swiper && (dir === 'left' || dir === 'right')) {
      if (dir === 'right') box.swiper.slideNext(); else box.swiper.slidePrev();
      setTimeout(function () { var n = findNext(dir); if (n) setFocus(n, dir, true); }, 380);
      return true;
    }
    // Nada más abajo/arriba: desplazamos la página para descubrir contenido cargado de forma diferida.
    if (dir === 'down') window.scrollBy({ top: innerHeight * 0.6, behavior: 'smooth' });
    if (dir === 'up') window.scrollTo({ top: 0, behavior: 'smooth' });
    return true;
  }

  function isTextField(e) {
    if (!e) return false;
    if (e.tagName === 'TEXTAREA' || e.isContentEditable) return true;
    if (e.tagName !== 'INPUT') return false;
    return !/^(button|submit|reset|checkbox|radio|range|color|file|image)$/i.test(e.type || 'text');
  }

  /**
   * OK sobre el elemento marcado. Devuelve "tap:fx,fy" (posición como fracción de la ventana)
   * para que la app haga un toque real ahí: así el sitio lo recibe como un gesto del usuario,
   * que el navegador exige para guardar el historial (botón Atrás), abrir el teclado o poner
   * el video en pantalla completa.
   */
  // ---------- Reproductor ----------

  var playerWantedUntil = 0;
  var cssPlayer = null;

  function findPlayer() {
    var fs = document.getElementsByTagName('iframe');
    for (var i = 0; i < fs.length; i++) {
      var r = fs[i].getBoundingClientRect();
      if (r.width >= 300 && r.height >= 160 && /mdstrm|mediastre|embed|player/i.test(fs[i].src)) return fs[i];
    }
    return null;
  }

  /** Pone el reproductor a pantalla completa y le da el foco para que reciba las teclas. */
  function enterPlayer(f) {
    try { f.focus(); } catch (e) {}
    var req = f.requestFullscreen || f.webkitRequestFullscreen;
    var p = null;
    try { p = req ? req.call(f) : null; } catch (e) { p = null; }
    if (p && p.catch) p.catch(function () { maximize(f); });
    else if (!req) maximize(f);
  }

  /** Alternativa si el navegador no deja usar pantalla completa: lo agrandamos con CSS. */
  function maximize(f) {
    cssPlayer = f;
    f.classList.add('tv-player');
    try { f.focus(); } catch (e) {}
  }

  function inPlayer() {
    if (cssPlayer && !cssPlayer.isConnected) cssPlayer = null;
    return !!cssPlayer || !!document.fullscreenElement;
  }

  /** Cierra el reproductor del sitio (también el flotante) con su propio botón ×. */
  function closePlayer() {
    var fs = document.querySelectorAll('.next-player iframe');
    for (var i = 0; i < fs.length; i++) {
      var wrap = fs[i].closest('.next-player');
      var x = wrap && wrap.parentElement && wrap.parentElement.querySelector('[data-icon=times]');
      if (x) {
        x.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
        return true;
      }
    }
    return false;
  }

  function back() {
    if (cssPlayer) {
      cssPlayer.classList.remove('tv-player');
      cssPlayer = null;
      closePlayer();
      return true;
    }
    return closePlayer();
  }

  function activate() {
    playerWantedUntil = Date.now() + 6000;
    if (!current || !current.isConnected || !onScreenNow(current)) { move('down'); return true; }
    var e = current;
    var r = e.getBoundingClientRect();
    if (r.top < 0 || r.bottom > innerHeight) {
      // A medio salir de la pantalla: primero lo mostramos entero; el siguiente OK lo abre.
      reveal(e, null, true);
      return true;
    }
    var x = Math.min(Math.max(r.left + r.width / 2, 1), innerWidth - 1);
    var y = Math.min(Math.max(r.top + r.height / 2, 1), innerHeight - 1);
    var hit = document.elementFromPoint(x, y);
    if (hit && (hit === e || e.contains(hit) || hit.contains(e))) {
      return 'tap:' + (x / innerWidth) + ',' + (y / innerHeight);
    }
    // Algo tapa el centro del elemento: hacemos clic por JS.
    if (isTextField(e)) {
      e.focus();
      if (window.TVBridge) TVBridge.showKeyboard();
    } else {
      e.click();
    }
    return true;
  }

  // ---------- API para la app ----------

  function key(k, repeat) {
    var now = Date.now();
    if (repeat && now - lastKeyTime < 110) return true;  // limita la velocidad al mantener presionado
    lastKeyTime = now;
    var active = document.activeElement;

    // Reproductor a pantalla completa: las teclas van a él (la app traduce OK a espacio).
    if (inPlayer()) return 'player';

    // Dentro de un iframe (p. ej. el reproductor) dejamos que el iframe reciba las teclas.
    if (active && active.tagName === 'IFRAME') {
      if (k === 'up' || k === 'down') { active.blur(); return move(k, repeat); }
      return false;
    }
    // En un campo de texto, izquierda/derecha mueven el cursor de texto.
    if (isTextField(active) && (k === 'left' || k === 'right')) return false;
    if (isTextField(active) && k === 'enter') return false;
    if (isTextField(active) && (k === 'up' || k === 'down')) active.blur();

    if (k === 'enter') return activate();
    return move(k, repeat);
  }

  function checkUrl() {
    // Tras un OK, si aparece un reproductor, lo pasamos a pantalla completa.
    if (playerWantedUntil && Date.now() < playerWantedUntil && !inPlayer()) {
      var pl = findPlayer();
      if (pl) { playerWantedUntil = 0; enterPlayer(pl); }
    }
    if (location.href === lastUrl) return;
    lastUrl = location.href;
    fixViewport();
    if (current) current.classList.remove(FOCUS);
    current = null;
    scheduleScan();
    // Tras cambiar de página, marcamos el primer elemento útil cuando el contenido termine de cargar.
    setTimeout(function () { focusInitialSoon(8); }, 600);
  }

  function init() {
    fixViewport();
    addStyle();
  }

  window.__tv = { key: key, init: init, back: back, closePlayer: closePlayer,
                  focusInitial: function () { focusInitialSoon(8); },
                  current: function () { return current; } };

  init();
  if (document.documentElement) {
    new MutationObserver(function () { pointerDirty = true; if (!document.getElementById('tv-style')) init(); })
      .observe(document.documentElement, { childList: true, subtree: true });
  }
  setInterval(checkUrl, 400);
})();
