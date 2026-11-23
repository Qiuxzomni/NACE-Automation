package com.nace.automation;

import org.json.JSONObject;

final class NaceSignInScript {
    private NaceSignInScript() {}

    static String prepare(String origin, String nonce, String dialCode, String phone, String password, boolean submit) {
        return """
                ((origin, nonce, dialCode, phone, password, submit) => {
                  if (location.origin !== origin || location.hash.split('?')[0] !== '#/login') return 'waiting';
                  const visible = e => e && e.getClientRects().length > 0 && getComputedStyle(e).visibility === 'visible';
                  const panel = document.querySelector('.login-component');
                  if (!visible(panel)) return 'waiting';
                  if (panel.classList.contains('register-component')) {
                    const tabs = [...document.querySelectorAll('.index-select-button .tab')].filter(visible);
                    if (tabs.length === 2 && tabs[0].classList.contains('active')) tabs[1].click();
                    return 'waiting';
                  }
                  const findForm = () => {
                    const p = document.querySelector('.login-component:not(.register-component)');
                    if (!visible(p)) return null;
                    const phone = p.querySelector('.is-left-icon input');
                    const secret = p.querySelector('input[type="password"]');
                    const country = p.querySelector('.select-phone .phone-text');
                    const button = p.querySelector('.login-btn button.oc-button--primary');
                    return visible(phone) && visible(secret) && visible(country) && visible(button)
                      ? {p, phone, secret, country, button} : null;
                  };
                  const form = findForm();
                  if (!form) return 'waiting';
                  let state = window.__naceSignIn;
                  if (state?.nonce !== nonce) {
                    if (state) {
                      state.controller.abort();
                      state.credential = null;
                    }
                    state = {nonce, controller:new AbortController(), submitted:false, countryOpened:false};
                    window.__naceSignIn = state;
                    document.addEventListener('click', event => {
                      const current = window.__naceSignIn;
                      if (current !== state || location.origin !== origin || location.hash.split('?')[0] !== '#/login') return;
                      const f = findForm();
                      if (!f || !f.button.contains(event.target) || f.button.disabled
                          || !f.phone.value || !f.secret.value) return;
                      current.submitted = true;
                      current.credential = {
                        phone: f.country.textContent.trim() + '|' + f.phone.value.trim(), password: f.secret.value
                      };
                    }, {capture:true, signal:state.controller.signal});
                  }
                  if (state.submitted) return visible(document.querySelector('.goole-verify')) ? 'verification' : 'submitted';
                  if (!submit) return 'ready';
                  if (form.country.textContent.trim() !== dialCode) {
                    const countries = [...document.querySelectorAll('.text-ul .text')].filter(e =>
                      visible(e) && e.textContent.trim().endsWith(' ' + dialCode));
                    if (countries.length > 0) {
                      countries[0].click();
                    } else if (!state.countryOpened) {
                      state.countryOpened = true;
                      form.country.closest('.select-phone').click();
                    }
                    return 'waiting';
                  }
                  const set = (field, value) => {
                    if (field.value === value) return;
                    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(field, value);
                    field.dispatchEvent(new Event('input', {bubbles:true}));
                  };
                  set(form.phone, phone);
                  set(form.secret, password);
                  const challenge = [...form.p.querySelectorAll('input')]
                    .some(e => visible(e) && e !== form.phone && e !== form.secret && e.type !== 'checkbox');
                  if (challenge && form.phone.value === phone && form.secret.value === password) {
                    return 'verification_prefilled';
                  }
                  if (challenge) return 'waiting';
                  if (form.button.disabled || form.button.classList.contains('is-disabled')) return 'waiting';
                  form.button.click();
                  return state.submitted ? 'submitted' : 'waiting';
                })
                """ + "(" + JSONObject.quote(origin) + "," + JSONObject.quote(nonce) + ","
                + JSONObject.quote(dialCode) + "," + JSONObject.quote(phone) + "," + JSONObject.quote(password)
                + "," + submit + ")";
    }

    static String scrollToVerification(String origin) {
        return """
                ((origin) => {
                  if (location.origin !== origin || location.hash.split('?')[0] !== '#/login') return;
                  const panel = document.querySelector('.login-component:not(.register-component)');
                  if (!panel) return;
                  const phone = panel.querySelector('.is-left-icon input');
                  const password = panel.querySelector('input[type="password"]');
                  const field = [...panel.querySelectorAll('input')].find(input =>
                    input !== phone && input !== password && input.type !== 'checkbox'
                      && input.getClientRects().length > 0 && getComputedStyle(input).visibility === 'visible');
                  field?.scrollIntoView({block:'center', behavior:'instant'});
                })
                """ + "(" + JSONObject.quote(origin) + ")";
    }

    static String takeSubmitted(String origin, String nonce) {
        return """
                ((origin, nonce) => {
                  if (location.origin !== origin || window.__naceSignIn?.nonce !== nonce) return null;
                  const state = window.__naceSignIn;
                  const value = state.credential ?? null;
                  state.credential = null;
                  state.controller.abort();
                  delete window.__naceSignIn;
                  return value;
                })
                """ + "(" + JSONObject.quote(origin) + "," + JSONObject.quote(nonce) + ")";
    }
}
