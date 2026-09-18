// OneCart popup blocker - injected once per document from onPageFinished.
// The token __ONECART_CONFIG__ is replaced at injection time with the JSON
// config built by PopupBlocker from assets/blocklist.json.
//
// Layers:
//   1. beforeinstallprompt / appinstalled suppression (install banner).
//   2. Cosmetic CSS selectors (generic + per-host) applied via a <style> tag.
//   3. Text rules: find elements whose subtree contains distinctive phrases,
//      then locate the containing modal/banner box and hide it.
//   4. Backdrop/overlay removal for modals.
// The script installs once per document (window.__oneCartBlockerInstalled)
// and keeps watching through a debounced MutationObserver.
(function () {
  'use strict';

  if (window.__oneCartBlockerInstalled) { return; }
  window.__oneCartBlockerInstalled = true;

  var CONF = __ONECART_CONFIG__;
  var DEBUG = !!CONF.debug;
  var GENERIC = CONF.generic || [];
  var HOSTKEY = (location.hostname || '').toLowerCase().replace(/^www\./, '');
  var SITE = (CONF.hosts || {})[HOSTKEY] || {};
  var SELS = GENERIC.concat(SITE.selectors || []);
  var RULES = SITE.textRules || [];
  var CLICKS = SITE.clickRules || [];

  function log() {
    if (!DEBUG) { return; }
    try { console.log('[OneCartBlocker]', [].slice.call(arguments).join(' ')); } catch (e) {}
  }

  function norm(t) {
    return String(t || '').toLowerCase().replace(/[\s\u00a0]+/g, ' ').trim();
  }

  var i, j;
  for (i = 0; i < RULES.length; i++) {
    RULES[i].ph = [];
    var src = RULES[i].phrases || [];
    for (j = 0; j < src.length; j++) {
      var ph = norm(src[j]);
      if (ph) { RULES[i].ph.push(ph); }
    }
  }
  for (i = 0; i < CLICKS.length; i++) {
    CLICKS[i].ph = CLICKS[i].text ? norm(CLICKS[i].text) : '';
  }

  function isExcluded(el) {
    if (!el || !el.nodeType || el.nodeType !== 1) { return true; }
    var tag = (el.tagName || '').toLowerCase();
    if (tag === 'html' || tag === 'body' || tag === 'head' || tag === 'script' ||
        tag === 'style' || tag === 'noscript' || tag === 'template' || tag === 'iframe') {
      return true;
    }
    var id = el.id || '';
    return id === 'root' || id === 'app' || id === 'app-root' || id === '__next' ||
           id === 'react-root' || id === 'main';
  }

  function hidden(el) {
    if (!el || !el.style) { return true; }
    var d = el.style.getPropertyValue('display') || '';
    var v = el.style.getPropertyValue('visibility') || '';
    return d.indexOf('none') !== -1 || v.indexOf('hidden') !== -1;
  }

  function hide(el, reason) {
    if (!el || !el.style || hidden(el)) { return false; }
    try {
      el.style.setProperty('display', 'none', 'important');
      el.style.setProperty('visibility', 'hidden', 'important');
      log('hidden ' + reason + ' <' + (el.tagName || '?') + ' id=' + el.id + ' class=' +
          ('' + el.className).slice(0, 60) + '>');
      return true;
    } catch (e) { return false; }
  }

  function matchSel(el) {
    if (!SELS.length || !el || !el.matches) { return false; }
    for (var k = 0; k < SELS.length; k++) {
      try { if (el.matches(SELS[k])) { return true; } } catch (e) {}
    }
    return false;
  }

  function ensureStyle() {
    if (!SELS.length) { return; }
    try {
      if (document.getElementById('onecart-blocker-style')) { return; }
      var css = [];
      for (var k = 0; k < SELS.length; k++) {
        css.push(SELS[k] + '{display:none !important;visibility:hidden !important;}');
      }
      var st = document.createElement('style');
      st.id = 'onecart-blocker-style';
      st.type = 'text/css';
      st.appendChild(document.createTextNode(css.join('\n')));
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
  }

  function boxOf(el) {
    try { return el.getBoundingClientRect(); } catch (e) { return null; }
  }

  function posOf(el) {
    try { return getComputedStyle(el).position; } catch (e) { return ''; }
  }

  function visibleArea(el) {
    var r = boxOf(el);
    if (!r) { return 0; }
    var w = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
    var h = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
    return w * h;
  }

  function depthOf(el) {
    var d = 0, n = el;
    while (n && n !== document.body) { n = n.parentElement; d++; if (d > 48) { break; } }
    return d;
  }

  function alphaOf(bg) {
    if (!bg || bg === 'transparent') { return 0; }
    var m = /rgba?\(\s*([\d%.,\s]+)\s*\)/.exec(bg);
    if (!m) { return null; }
    var p = m[1].split(',').map(function (x) { return parseFloat(x); });
    if (p.length >= 4) { return isNaN(p[3]) ? 1 : p[3]; }
    return p.length === 3 ? 1 : null;
  }

  function looksBackdrop(el) {
    if (!el || el.nodeType !== 1) { return false; }
    var name = (('' + (el.className || '')).toLowerCase() + ' ' + (el.id || '').toLowerCase());
    if (/(overlay|backdrop|scrim|dialog-bg|modal-bg)/.test(name)) { return true; }
    var st;
    try { st = getComputedStyle(el); } catch (e) { return false; }
    if (st.position !== 'fixed' && st.position !== 'absolute') { return false; }
    var r = boxOf(el);
    if (!r || r.width < window.innerWidth * 0.85 || r.height < window.innerHeight * 0.85) { return false; }
    if (st.backgroundImage && st.backgroundImage !== 'none') { return true; }
    var a = alphaOf(st.backgroundColor);
    return a !== null && a > 0.05 && a < 1;
  }

  function findBackdrop(modalRoot) {
    var n = modalRoot;
    for (var d = 0; d < 6 && n && n.parentElement; d++) {
      var parent = n.parentElement;
      var kids = parent.children;
      for (var k = 0; k < kids.length; k++) {
        var b = kids[k];
        if (b === modalRoot || b === n || hidden(b)) { continue; }
        if (looksBackdrop(b)) { return b; }
      }
      n = parent;
    }
    return null;
  }

  function collectRuleCandidates(root, rule, maxDepth) {
    var seeds = [], parents = [], cands = [];
    var walker;
    try { walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false); } catch (e) { return cands; }
    var node;
    while ((node = walker.nextNode())) {
      var p = node.parentElement;
      if (!p || isExcluded(p) || hidden(p)) { continue; }
      if (parents.indexOf(p) !== -1) { continue; }
      var pt;
      try { pt = norm(p.textContent || ''); } catch (e) { continue; }
      if (!pt) { continue; }
      parents.push(p);
      for (var k = 0; k < rule.ph.length; k++) {
        if (pt.indexOf(rule.ph[k]) !== -1) { seeds.push(p); break; }
      }
    }
    if (!seeds.length) { return cands; }
    for (var s = 0; s < seeds.length; s++) {
      var n = seeds[s], dd = 0;
      while (n && dd <= maxDepth) {
        if (!isExcluded(n) && cands.indexOf(n) === -1) { cands.push(n); }
        n = n.parentElement; dd++;
      }
    }
    return cands;
  }

  function pickWinner(scored, rule) {
    if (!scored.length) { return null; }
    if (rule.kind === 'banner') {
      var maxH = rule.bannerMaxHeight || 200;
      var sorted = scored.slice().sort(function (a, b) { return b.depth - a.depth; });
      for (var z = 0; z < sorted.length; z++) {
        var r = boxOf(sorted[z].el);
        if (!r) { continue; }
        if (r.width >= window.innerWidth * 0.5 && r.height > 0 && r.height <= maxH && r.top <= window.innerHeight * 0.6) {
          return sorted[z].el;
        }
      }
      return sorted[0].el;
    }
    var maxC = 0;
    for (var v = 0; v < scored.length; v++) { if (scored[v].count > maxC) { maxC = scored[v].count; } }
    var viewport = window.innerWidth * window.innerHeight;
    var best = null, bestArea = -1, bestDepth = -1;
    for (var w = 0; w < scored.length; w++) {
      if (scored[w].count !== maxC) { continue; }
      var area = visibleArea(scored[w].el);
      var pos = posOf(scored[w].el);
      var ok = (pos === 'fixed' || pos === 'absolute') || area < viewport * 0.9;
      if (ok && (area > bestArea || (area === bestArea && scored[w].depth > bestDepth))) {
        best = scored[w].el; bestArea = area; bestDepth = scored[w].depth;
      }
    }
    if (!best) {
      for (var x = 0; x < scored.length; x++) {
        if (scored[x].count !== maxC) { continue; }
        if (scored[x].depth > bestDepth) { best = scored[x].el; bestDepth = scored[x].depth; }
      }
    }
    return best;
  }

  function scanRuleIn(root, rule) {
    var maxDepth = rule.maxDepth || 10;
    var cands = collectRuleCandidates(root, rule, maxDepth);
    if (!cands.length) { return; }
    var scored = [];
    for (var c = 0; c < cands.length; c++) {
      var el = cands[c];
      var txt;
      try { txt = norm(el.textContent || ''); } catch (e) { continue; }
      if (!txt) { continue; }
      var cnt = 0;
      for (var q = 0; q < rule.ph.length; q++) {
        if (txt.indexOf(rule.ph[q]) !== -1) { cnt++; }
      }
      if (cnt >= (rule.minMatches || 1)) { scored.push({ el: el, count: cnt, depth: depthOf(el) }); }
    }
    var winner = pickWinner(scored, rule);
    if (!winner) { return false; }
    if (!hide(winner, 'text@' + HOSTKEY + ':' + rule.id)) { return false; }
    log('matched rule=' + rule.id + ' winner=<' + winner.tagName + ' class=' +
        ('' + winner.className).slice(0, 60) + '>');
    if (rule.backdrop) {
      var bd = findBackdrop(winner);
      if (bd) { hide(bd, 'backdrop@' + HOSTKEY + ':' + rule.id); }
    }
    return true;
  }

  // ---------- click rules ----------
  function scopeEls(root, scopeSel) {
    if (!scopeSel) { return root && root.nodeType === 1 ? [root] : []; }
    var out = [];
    try {
      if (root && root.nodeType === 1 && root.matches && root.matches(scopeSel)) { out.push(root); }
      var q = root.querySelectorAll(scopeSel);
      for (var s = 0; s < q.length; s++) { out.push(q[s]); }
    } catch (e) {}
    return out;
  }

  function findElemByText(root, phrase) {
    if (!root || !phrase) { return null; }
    var els, best = null, bestDepth = -1;
    try { els = root.querySelectorAll('*'); } catch (e) { return null; }
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (hidden(el)) { continue; }
      var txt;
      try { txt = norm(el.textContent || ''); } catch (e) { continue; }
      if (txt === phrase || txt.indexOf(phrase) === 0) {
        var d = depthOf(el);
        if (d > bestDepth) { best = el; bestDepth = d; }
      }
    }
    return best;
  }

  function clickTarget(el, rule) {
    if (!el || el.nodeType !== 1) { return false; }
    try { if (el.getAttribute('data-oc-clicked')) { return true; } } catch (e) { return true; }
    if (hidden(el)) { return false; }
    try { el.setAttribute('data-oc-clicked', '1'); } catch (e) {}
    try {
      el.click();
      log('clicked ' + rule.id + ' <' + el.tagName + '>' + (el.id ? (' id=' + el.id) : ''));
      return true;
    } catch (e) { return false; }
  }

  function fallbackHideModal(rule, scope) {
    if (!scope || hidden(scope)) { return; }
    hide(scope, 'click-fallback@' + HOSTKEY + ':' + rule.id);
    var o = scope, up = 0;
    while (o && o.nodeType === 1 && o !== document.body && up < 6) {
      if (o.matches && o.matches('[class*="ReactModal__Overlay"]')) {
        hide(o, 'click-fallback-overlay@' + HOSTKEY + ':' + rule.id);
        break;
      }
      o = o.parentElement; up++;
    }
    try {
      var bb = document.body;
      if (bb && bb.style && /ReactModal__Body--open/.test(bb.className || '')) {
        var ov = bb.style.overflow;
        if (ov !== 'auto' && ov !== 'visible' && ov !== 'scroll') { bb.style.overflow = 'auto'; }
      }
    } catch (e) {}
  }

  function tryClicksIn(root) {
    if (!CLICKS.length || !root || !root.querySelectorAll) { return; }
    for (var i = 0; i < CLICKS.length; i++) {
      var r = CLICKS[i];
      var scopes = scopeEls(root, r.scope);
      for (var k = 0; k < scopes.length; k++) {
        var scope = scopes[k];
        if (!scope || hidden(scope)) { continue; }
        var t = null;
        if (r.selector) { try { t = scope.querySelector(r.selector); } catch (e) {} }
        if (!t && r.ph) { t = findElemByText(scope, r.ph); }
        if (t) {
          if (clickTarget(t, r) && r.fallback) {
            (function (rule, sc) {
              setTimeout(function () { fallbackHideModal(rule, sc); }, 900);
            })(r, scope);
          }
          break;
        } else if (r.fallback) {
          fallbackHideModal(r, scope);
          break;
        }
      }
    }
  }

  function scanAll() {
    ensureStyle();
    if (SELS.length) {
      try {
        var hits = document.querySelectorAll(SELS.join(','));
        for (var h = 0; h < hits.length; h++) { hide(hits[h], 'sel@' + HOSTKEY + ':init'); }
      } catch (e) {}
    }
    for (var r = 0; r < RULES.length; r++) { scanRuleIn(document.body, RULES[r]); }
    tryClicksIn(document.body);
  }

  var pending = [], timer = null;
  function queueScan(root) {
    if (!root || root.nodeType !== 1) { return; }
    if (pending.indexOf(root) !== -1) { return; }
    pending.push(root);
    if (timer) { return; }
    timer = setTimeout(function () {
      timer = null;
      var roots = pending; pending = [];
      for (var a = 0; a < roots.length; a++) {
        for (var b = 0; b < RULES.length; b++) { scanRuleIn(roots[a], RULES[b]); }
        tryClicksIn(roots[a]);
      }
    }, 60);
  }

  function installObserver() {
    try {
      new MutationObserver(function (muts) {
        for (var m = 0; m < muts.length; m++) {
          var mu = muts[m];
          if (mu.type === 'attributes') {
            var t = mu.target;
            if (t && t.nodeType === 1 && !hidden(t)) {
              if (matchSel(t)) { hide(t, 'attr-sel@' + HOSTKEY); }
              else if (RULES.length) { queueScan(t); }
            }
          } else if (mu.type === 'childList') {
            var added = mu.addedNodes;
            for (var a = 0; a < added.length; a++) {
              var n = added[a];
              if (n && n.nodeType === 1) {
                if (matchSel(n)) { hide(n, 'child-sel@' + HOSTKEY); }
                if (RULES.length) { queueScan(n); }
              }
            }
          }
        }
      }).observe(document.documentElement, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['style', 'class', 'id']
      });
    } catch (e) {}
  }

  function boot() {
    ensureStyle();
    scanAll();
    installObserver();
    setTimeout(scanAll, 1500);
    setTimeout(scanAll, 4000);
    log('installed host=' + HOSTKEY + ' selectors=' + SELS.length + ' textRules=' + RULES.length + ' clickRules=' + CLICKS.length);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();