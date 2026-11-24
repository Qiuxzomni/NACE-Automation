package com.nace.automation;

import org.json.JSONObject;

final class NaceWebScript {
    static final String ETH = "ETH/USDT";
    static final String BTC = "BTC/USDT";

    private NaceWebScript() {}

    static String instrumentId(String pair) {
        if (ETH.equals(pair)) return "48162";
        if (BTC.equals(pair)) return "48128";
        throw new IllegalArgumentException("Select ETH/USDT or BTC/USDT.");
    }

    static String startProbe(String origin, int token) {
        return """
                ((expectedOrigin, id) => {
                  if (location.origin !== expectedOrigin) return false;
                  const app = document.querySelector('#app')?.__vue_app__;
                  const store = app?.config.globalProperties.$pinia?._s.get('useCommon');
                  if (!store) return false;
                  const href = location.href;
                  const token = store.token;
                  const startedAt = Date.now();
                  const isLive = value => value === 0 || value === '0';
                  const sameSession = () => location.href === href && store.token === token;
                  let liveVerified = false;
                  const result = {
                    id, data: null,
                    current: sameSession,
                    canConfirm: () => liveVerified && sameSession() && !!token &&
                        isLive(store.accountType) && Date.now() >= startedAt && Date.now() - startedAt < 12000
                  };
                  window.__naceSessionProbe = result;
                  (async () => {
                    if (!token) return {session:'SIGNED_OUT'};
                    const info = await store.getUserInfo();
                    if (!store.token || [401, 666].includes(Number(info?.code))) {
                      return {session:'SIGNED_OUT'};
                    }
                    if (!sameSession()) return {session:'UNAVAILABLE'};
                    if (Number(info?.code) !== 200 || !info.data) return {session:'UNAVAILABLE'};
                    const data = {session:'SIGNED_IN', liveAccount:isLive(info.data.accountType)};
                    try {
                      const wallet = await store.memberWallet();
                      if (!store.token || [401, 666].includes(Number(wallet?.code))) {
                        return {session:'SIGNED_OUT'};
                      }
                      if (!sameSession()) return {session:'UNAVAILABLE'};
                      const amount = store.walletInfo?.futuresAmount;
                      if (data.liveAccount && Number(wallet?.code) === 200 &&
                          amount !== null && amount !== undefined && Number.isFinite(Number(amount))) {
                        data.balance = String(amount);
                      }
                    } catch (error) {
                      // Wallet service availability does not determine sign-in.
                    }
                    liveVerified = data.liveAccount;
                    return data;
                  })().then(data => { result.data = data; }, () => { result.data = {session:'UNAVAILABLE'}; });
                  return true;
                })
                """ + "(" + JSONObject.quote(origin) + "," + token + ")";
    }

    static String readProbe(int token) {
        return "(()=>{const r=window.__naceSessionProbe;return r?.id===" + token
                + "&&r.data?(r.current()||r.data.session==='SIGNED_OUT'?r.data:{session:'UNAVAILABLE'}):null;})()";
    }

    // The exchange restores its saved instrument on mount. Its drawer updates that preference.
    /**
     * Checks that the NACE page shows the selected pair. Read-only unless {@code allowPanel}: a wrong
     * pair returns 'mismatch' and the service reloads the pair's own link. Only when that did not help
     * (warm-up check, never WATCH) may it select the pair through the side panel as a last resort.
     * With {@code showCopyList} it also opens the Copy Trading tab (a tab, not a pair change).
     */
    static String selectWatchPair(String origin, String pair, boolean allowPanel, boolean showCopyList) {
        return """
                ((expectedOrigin, pair, stockId, allowPanel, showCopyList) => {
                  if (location.origin !== expectedOrigin) return 'other';
                  const [path, search = ''] = location.hash.slice(1).split('?');
                  if (path !== '/forward') return 'other';
                  const visible = element => {
                    const box = element.getBoundingClientRect(), style = getComputedStyle(element);
                    return box.width > 0 && box.height > 0 && style.visibility === 'visible'
                      && box.right > 0 && box.left < innerWidth && style.display !== 'none';
                  };
                  const text = element => element.textContent.replace(/\\s+/g, '').toUpperCase();
                  const header = document.querySelector('.stock-tarde_header_body .title .title_flex .names');
                  const idMatches = new URLSearchParams(search).get('id') === stockId;
                  if (header && text(header) === pair && idMatches) {
                    if (!showCopyList) return 'matched';
                    const list = document.querySelector('.order-list');
                    if (!list) return 'waiting-no-list';
                    const global = document.querySelector('#app')?.__vue_app__?.config.globalProperties;
                    const translated = typeof global?.$t === 'function' ? global.$t('common.gendanliebiao') : null;
                    const label = translated && translated !== 'common.gendanliebiao' ? translated : 'Copy Trading List';
                    const key = label.replace(/\\s+/g, '').toUpperCase();
                    const tabs = [...list.querySelectorAll('.tab_active_stock-detail_select .text-tabs-label')]
                        .filter(element => visible(element) && text(element) === key);
                    if (tabs.length !== 1) return tabs.length ? 'waiting-tabs' : 'waiting-no-tab';
                    const control = tabs[0];
                    if (!control.classList.contains('active') && control.getAttribute('aria-selected') !== 'true') {
                      control.click();
                      return 'waiting';
                    }
                    list.scrollIntoView({block:'start', behavior:'instant'});
                    return 'matched';
                  }
                  if (!header) return 'loading';
                  // 'mismatch-url': on another pair's address. 'mismatch-header': right address, other pair shown.
                  if (!allowPanel) return idMatches ? 'mismatch-header' : 'mismatch-url';
                  const drawer = [...document.querySelectorAll('.tarde.app_content')].find(visible);
                  if (!drawer) {
                    // One click, then give the side panel time to slide in. Clicking every poll
                    // toggled it open and closed and could leave it stuck open.
                    const now = Date.now();
                    if (now - (window.__naceDrawerClickAt || 0) < 2500) return 'drawer-opening';
                    const trigger = header?.closest('.title');
                    if (trigger && visible(trigger)) {
                      window.__naceDrawerClickAt = now;
                      trigger.click();
                    }
                    return 'drawer-clicked';
                  }
                  const rows = [...drawer.querySelectorAll('.chart-four .title.ellipsis-1')]
                    .filter(label => visible(label) && text(label) === pair);
                  if (rows.length === 1) {
                    rows[0].closest('.chart-four').click();
                    // Give the site time to switch; if it did not, the next attempt comes after 2.5 s.
                    window.__naceDrawerClickAt = Date.now();
                    return 'selected';
                  }
                  const input = drawer.querySelector('.search-ul input');
                  const symbol = pair.split('/')[0];
                  if (input && input.value !== symbol) {
                    input.value = symbol;
                    input.dispatchEvent(new Event('input', {bubbles: true}));
                  }
                  return 'waiting';
                })
                """ + "(" + JSONObject.quote(origin) + "," + JSONObject.quote(pair) + "," + JSONObject.quote(instrumentId(pair)) + "," + allowPanel + "," + showCopyList + ")";
    }

    static String cancelRun(String nonce) {
        return "if (window.__naceWorkflow?.nonce===" + JSONObject.quote(nonce)
                + " && !window.__naceWorkflow.confirmedTap) delete window.__naceWorkflow;";
    }

    static String scrollWatchBy(String origin, float x, float y, float delta) {
        return """
                ((expectedOrigin, x, y, delta) => {
                  if (location.origin !== expectedOrigin) return;
                  let element = document.elementFromPoint(x * innerWidth, y * innerHeight);
                  const distance = delta * innerHeight;
                  for (; element && element !== document.body; element = element.parentElement) {
                    if (!/^(auto|scroll)$/.test(getComputedStyle(element).overflowY)) continue;
                    const previous = element.scrollTop;
                    element.scrollTop += distance;
                    if (element.scrollTop !== previous) return;
                  }
                  window.scrollBy(0, distance);
                })
                """ + "(" + JSONObject.quote(origin) + "," + x + "," + y + "," + delta + ")";
    }

    static String workflow(
            String origin, String action, String pair, String nonce, long fromMs, long untilMs, int verifiedProbe, String signalId
    ) {
        return """
                ((expectedOrigin, action, pair, stockId, nonce, from, until, verifiedProbe, signalId) => {
                  const normalize = value => (value ?? '').replace(/\\s+/g, ' ').trim();
                  const text = element => normalize(element.innerText ?? element.textContent);
                  const visible = element => {
                    const box = element.getBoundingClientRect();
                    const style = getComputedStyle(element);
                    return box.width > 0 && box.height > 0 && style.visibility === 'visible'
                      && style.display !== 'none' && style.opacity !== '0';
                  };
                  const exact = (root, selector, label) => {
                    const matched = [...root.querySelectorAll(selector)]
                      .filter(element => visible(element) && text(element) === label);
                    return matched.filter(element => !matched.some(child => child !== element && element.contains(child)));
                  };
                  const pick = (root, selector, label) => {
                    const items = exact(root, selector, label);
                    return items.length === 1 ? items[0] : null;
                  };
                  const routeMatches = () => {
                    if (location.origin !== expectedOrigin) return false;
                    const [path, search = ''] = location.hash.slice(1).split('?');
                    if (path !== '/forward' || new URLSearchParams(search).get('id') !== stockId) return false;
                    return !!pick(document, '.stock-tarde_header_body .title_flex .names', pair)
                      || !!pick(document, '.stock-tarde_header_body .title', pair);
                  };
                  const app = document.querySelector('#app')?.__vue_app__;
                  const global = app?.config.globalProperties;
                  const store = global?.$pinia?._s.get('useCommon');
                  const label = (key, fallback) => {
                    const translated = typeof global?.$t === 'function' ? global.$t('common.' + key) : null;
                    return !translated || translated === 'common.' + key ? fallback : normalize(translated);
                  };
                  // The site's keyed followlist VNode uses the signal's backend id. Walk only
                  // mounted Vue nodes and require an exact DOM element and an unambiguous key.
                  const signalCards = () => {
                    const cards = [...document.querySelectorAll('.followlist')].filter(visible);
                    if (!cards.length) return [];
                    const wanted = new Set(cards), found = new Map(), seen = new WeakSet();
                    const stack = [app?._container?._vnode ?? app?._instance?.subTree];
                    let visited = 0;
                    while (stack.length && visited < 20000) {
                      const node = stack.pop();
                      if (!node || typeof node !== 'object' || seen.has(node)) continue;
                      seen.add(node); visited++;
                      if (node.type === 'div' && wanted.has(node.el)) {
                        const key = node.key;
                        const valid = (typeof key === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(key))
                          || (typeof key === 'number' && Number.isSafeInteger(key) && key >= 0);
                        const id = valid ? String(key) : null;
                        const prior = found.get(node.el);
                        found.set(node.el, prior === undefined ? id : null);
                      }
                      if (node.component?.subTree) stack.push(node.component.subTree);
                      if (node.suspense?.activeBranch) stack.push(node.suspense.activeBranch);
                      if (Array.isArray(node.children)) stack.push(...node.children);
                    }
                    if (stack.length) return null;
                    const records = [];
                    for (const card of cards) {
                      const buttons = exact(card, '.tool span', label('querengendanxinxi', 'Confirm Copy Trading Information'));
                      if (buttons.length > 1) return null;
                      const button = buttons[0];
                      if (!button || button.matches(':disabled, [disabled], [aria-disabled="true"], .disabled')) continue;
                      const id = found.get(card);
                      if (!id || records.some(record => record.id === id)) return null;
                      records.push({id, card, button});
                    }
                    return records;
                  };
                  const popups = () => [...document.querySelectorAll('.follow-order--popup')].filter(visible);
                  const prompt = () => {
                    const matching = popups().filter(popup =>
                      !!pick(popup, '.title', label('qurengenzunxundingdan', 'Confirm Follow Order')));
                    return matching.length === 1 ? matching[0] : null;
                  };
                  const success = () => !!pick(
                    document, '.van-toast, .van-toast__text, .el-message, [role="alert"]',
                    label('zunxundingdanchenggong', 'Follow Order Successful'));
                  const eligible = () => {
                    const now = Date.now();
                    return now >= from && now < until && !!store?.token && routeMatches();
                  };
                  const amount = value => {
                    const match = normalize(value).match(/^(\\d+(?:\\.\\d+)?)\\s*(?:USDT)?$/i);
                    return match && Number(match[1]) > 0 ? Number(match[1]) : null;
                  };
                  const confirmationEligible = () => {
                    const proof = window.__naceSessionProbe;
                    return eligible() && window.__naceWorkflow === current && current?.nonce === nonce
                      && typeof signalId === 'string' && !!signalId && current.signalId === signalId
                      && verifiedProbe > 0 && proof?.id === verifiedProbe && proof.canConfirm?.();
                  };
                  const hit = (element, guard = eligible) => {
                    if (!element || !visible(element) || element.matches(':disabled, [disabled], [aria-disabled="true"], .disabled')) {
                      return false;
                    }
                    element.scrollIntoView({block:'center', inline:'nearest', behavior:'instant'});
                    const box = element.getBoundingClientRect();
                    const x = box.left + box.width / 2, y = box.top + box.height / 2;
                    const front = document.elementFromPoint(x, y);
                    if (!front || (front !== element && !element.contains(front))) return false;
                    const old = document.querySelector('[data-nace-auto-marker]');
                    old?.remove();
                    const marker = document.createElement('span');
                    marker.setAttribute('data-nace-auto-marker', '');
                    marker.setAttribute('aria-hidden', 'true');
                    marker.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;'
                      + 'width:27px;height:27px;border:3px solid #ffad35;border-radius:50%;'
                      + 'box-shadow:0 0 14px #ff8a18;transform:translate(-50%,-50%);left:' + x + 'px;top:' + y + 'px;';
                    document.body.appendChild(marker);
                    setTimeout(() => marker.remove(), 1000);
                    if (!guard()) return false;
                    element.click();
                    return true;
                  };
                  const current = window.__naceWorkflow;
                  if (action === 'observe') {
                    const canObserve = () => window.__naceWorkflow === current && current?.nonce === nonce && current.signalId === signalId
                      && typeof signalId === 'string' && signalId.length > 0 && current.confirmedTap
                      && !!store?.token && routeMatches() && !popups().length;
                    if (window.__naceWorkflow !== current || current?.nonce !== nonce
                        || current.signalId !== signalId || typeof signalId !== 'string'
                        || !signalId || !current.confirmedTap) return 'order_context_lost';
                    if (!store?.token) return 'order_session_missing';
                    if (!routeMatches()) return 'order_pair_changed';
                    if (popups().length) return 'order_dialog_open';
                    const list = document.querySelector('.order-list');
                    const orderTab = list && pick(list, '.tab_active_stock-detail_select .text-tabs-label',
                      label('market_dangqianweituo', 'Order'));
                    if (!orderTab) return 'order_tab_missing';
                    if (!orderTab.classList.contains('active')) {
                      if (!hit(orderTab, canObserve)) return 'order_tab_blocked';
                      current.lastOrderRefreshAt = Date.now();
                      return 'order_tab_opening';
                    }
                    if ([...list.querySelectorAll('.list-item-all')].some(visible)) return 'submitted';
                    current.lastOrderRefreshAt ??= Date.now();
                    if ((current.orderRefreshes ?? 0) < 2
                        && Date.now() - current.lastOrderRefreshAt >= 8000 && hit(orderTab, canObserve)) {
                      current.orderRefreshes = (current.orderRefreshes ?? 0) + 1;
                      current.lastOrderRefreshAt = Date.now();
                    }
                    return 'order_rows_empty';
                  }
                  if (!eligible()) return 'verify';
                  if (action === 'tab') {
                    if (popups().length) return 'stale_dialog';
                    const tab = pick(document, 'button, [role="tab"], div, span', label('gendanliebiao', 'Copy Trading List'));
                    if (!tab) return 'copy_tab_missing';
                    if (!hit(tab)) return 'copy_tab_blocked';
                    window.__naceWorkflow = {nonce, firstTap:false, confirmedTap:false};
                    return 'tab';
                  }
                  if (!current || current.nonce !== nonce) return 'invalid';
                  if (action === 'signals') {
                    if (current.firstTap || popups().length) return null;
                    const cards = signalCards();
                    return cards === null ? 'signal_identity_unavailable' : cards.map(card => card.id);
                  }
                  if (action === 'open') {
                    if (current.firstTap || popups().length || typeof signalId !== 'string' || !signalId) return 'invalid';
                    const cards = signalCards();
                    if (cards === null) return 'signal_identity_unavailable';
                    const matched = cards.filter(card => card.id === signalId);
                    if (matched.length !== 1) return 'signal_disappeared';
                    const selected = matched[0];
                    current.signalId = signalId;
                    const stillSelected = () => {
                      const updated = signalCards();
                      return eligible() && current.nonce === nonce && current.signalId === signalId
                        && !current.firstTap && !popups().length && updated !== null
                        && updated.filter(card => card.id === signalId && card.card === selected.card
                          && card.button === selected.button).length === 1;
                    };
                    if (!hit(selected.button, stillSelected)) {
                      delete current.signalId;
                      return 'signal_button_blocked';
                    }
                    current.firstTap = true;
                    return 'first_tap';
                  }
                  if (!current.firstTap || current.confirmedTap || typeof signalId !== 'string'
                      || !signalId || current.signalId !== signalId) return 'invalid';
                  const dialog = prompt();
                  if (!dialog) return 'dialog_missing';
                  const confirm = pick(dialog, '.btn', label('confirm', 'Confirm'));
                  if (!confirm) return 'confirm_button_missing';
                  if (action === 'dialog') return 'dialog';
                  if (action !== 'confirm') return 'waiting';
                  if (success()) return 'success_toast_visible';
                  if (!confirmationEligible()) return 'recheck';
                  if (amount(dialog.querySelector('input')?.value) === null) return 'confirm_amount_invalid';
                  if (!hit(confirm, confirmationEligible)) {
                    return confirmationEligible() ? 'confirm_button_blocked' : 'recheck';
                  }
                  current.confirmedTap = true;
                  return 'confirmation_tapped';
                })
                """ + "("
                + JSONObject.quote(origin) + "," + JSONObject.quote(action) + "," + JSONObject.quote(pair) + ","
                + JSONObject.quote(instrumentId(pair)) + "," + JSONObject.quote(nonce) + ","
                + fromMs + "," + untilMs + "," + verifiedProbe + ","
                + (signalId == null ? "null" : JSONObject.quote(signalId)) + ")";
    }
}
