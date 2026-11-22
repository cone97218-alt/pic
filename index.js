/**
 * 酒馆匣互联（酒馆匣 <-> TauriTavern）
 * 同时支持「酒馆助手热更新脚本」与「TT 原生扩展」双模式运行。
 * 通过 TT 自带本机 MCP 客户端直连 127.0.0.1:8796-8800，不上云、无需账号。
 */
(function () {
    'use strict';

    function getMainWin() {
        try { if (window.__TAURITAVERN__ || window.SillyTavern) return window; } catch (_) {}
        try { if (window.parent && window.parent !== window && (window.parent.__TAURITAVERN__ || window.parent.SillyTavern)) return window.parent; } catch (_) {}
        try { if (window.top && window.top !== window && (window.top.__TAURITAVERN__ || window.top.SillyTavern)) return window.top; } catch (_) {}
        return window;
    }

    const mainWin = getMainWin();
    const mainDoc = mainWin.document;

    // Prevent double mounting when hot-reloaded or when both extension & helper are active
    if (mainWin.__STVAULT_BRIDGE_LOADED__ && mainDoc.getElementById('stv_bridge_status')) {
        return;
    }
    mainWin.__STVAULT_BRIDGE_LOADED__ = true;

    const DISPLAY_NAME = '酒馆匣互联';
    const CANDIDATE_PORTS = [8796, 8797, 8798, 8799, 8800];
    const CHUNK_BYTES = 384 * 1024;
    const FAST_LIMIT_BYTES = 512 * 1024;
    const BATCH_MAX_ITEMS = 6;
    const BATCH_MAX_BYTES = 520 * 1024;
    const TOOL_NAMES = [
        'stvault_ping', 'stvault_sync_inventory',
        'stvault_outbox_list', 'stvault_outbox_fetch', 'stvault_outbox_ack',
        'stvault_pull_poll', 'stvault_pull_done',
        'stvault_push_begin', 'stvault_push_chunk', 'stvault_push_end',
        'stvault_push_fast', 'stvault_push_batch'
    ];

    const state = {
        registrationId: null,
        port: 0,
        connected: false,
        busy: false,
        callMode: null,
        pollTimer: null
    };

    function host() {
        const v = mainWin.__TAURITAVERN__;
        if (!v || !v.api || !v.api.mcp) {
            throw new Error('需要 TauriTavern 2.3.0 及以上版本');
        }
        return v;
    }

    async function hostReady() {
        const p = (mainWin.__TAURITAVERN__ && mainWin.__TAURITAVERN__.ready) || mainWin.__TAURITAVERN_MAIN_READY__;
        if (p && typeof p.then === 'function') {
            try { await p; } catch (_) {}
        }
        return host();
    }

    function contextApi() {
        try {
            if (mainWin.SillyTavern && typeof mainWin.SillyTavern.getContext === 'function') {
                return mainWin.SillyTavern.getContext() || {};
            }
        } catch (_) {}
        return {};
    }

    async function getHeaders() {
        const ctx = contextApi();
        if (typeof ctx.getRequestHeaders === 'function') {
            return Object.assign({}, ctx.getRequestHeaders());
        }
        return {};
    }

    async function saveStSettings() {
        const ctx = contextApi();
        if (typeof ctx.saveSettingsDebounced === 'function') {
            ctx.saveSettingsDebounced();
        }
    }

    function mainFetch(url, opts) {
        return mainWin.fetch(url, opts);
    }

    function setStatus(text, cls) {
        mainDoc.querySelectorAll('.stv-bridge-status').forEach((el) => {
            el.textContent = text;
            el.classList.remove('stv-ok', 'stv-err');
            if (cls) el.classList.add(cls);
        });
    }

    function log(msg) {
        const line = '[' + new Date().toLocaleTimeString() + '] ' + msg + '\n';
        mainDoc.querySelectorAll('.stv-bridge-log').forEach((box) => {
            box.textContent = (line + box.textContent).slice(0, 6000);
        });
    }

    function bytesToBase64(bytes) {
        let bin = '';
        for (let i = 0; i < bytes.length; i += 8192) {
            bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192));
        }
        return btoa(bin);
    }

    function base64ToBytes(b64) {
        const bin = atob(b64);
        const out = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
        return out;
    }

    function asObject(v) {
        if (typeof v === 'string') {
            try {
                const p = JSON.parse(v);
                return (p && typeof p === 'object' && !Array.isArray(p)) ? p : null;
            } catch (_) { return null; }
        }
        return (v && typeof v === 'object' && !Array.isArray(v)) ? v : null;
    }

    function asArray(v) {
        if (Array.isArray(v)) return v;
        if (typeof v === 'string') {
            try {
                const p = JSON.parse(v);
                return Array.isArray(p) ? p : [];
            } catch (_) { return []; }
        }
        return [];
    }

    function betterArray(a, b) {
        const pa = asArray(a);
        return pa.length > 0 ? pa : asArray(b);
    }

    function unwrapToolResult(res) {
        if (!res || res.outcome !== 'known_response') throw new Error('通道暂时不可用');
        const r = res.response;
        if (!r || r.kind !== 'tool_result') throw new Error('酒馆匣拒绝了请求');
        const txt = r.structuredJson || (Array.isArray(r.textBlocks) ? r.textBlocks.map(b => b.text).join('') : '');
        const val = txt ? JSON.parse(txt) : null;
        if (r.isError) throw new Error((val && val.error) || '操作失败');
        return val;
    }

    async function callTool(name, args) {
        if (!state.registrationId) throw new Error('尚未连接酒馆匣');
        const argumentsJson = JSON.stringify(args || {});
        const h = host();
        if (state.callMode !== 'legacy') {
            try {
                const out = await h.api.mcp.tools.testCall({
                    registrationId: state.registrationId,
                    nativeName: name,
                    argumentsJson
                });
                state.callMode = 'test';
                return unwrapToolResult(out);
            } catch (e) {
                if (state.callMode === 'test') throw e;
                state.callMode = 'legacy';
            }
        }
        const executionCallId = mainWin.crypto.randomUUID();
        await h.invoke.safeInvoke('start_legacy_mcp_tool_call', { dto: { executionCallId } });
        const out = await h.invoke.safeInvoke('call_legacy_mcp_tool', {
            dto: { executionCallId, toolId: 'mcp/' + state.registrationId + ':' + name, argumentsJson }
        });
        return unwrapToolResult(out);
    }

    async function forgetOldServers() {
        try {
            const api = host().api.mcp;
            const listed = await api.servers.list();
            const servers = (listed && Array.isArray(listed.servers)) ? listed.servers : [];
            for (const s of servers) {
                if (s && s.displayName === DISPLAY_NAME) {
                    try { await api.servers.remove(s.id); } catch (_) {}
                }
            }
        } catch (_) {}
    }

    async function connect() {
        const api = (await hostReady()).api.mcp;
        await forgetOldServers();

        for (const port of CANDIDATE_PORTS) {
            let created = null;
            try {
                created = await api.servers.create({
                    displayName: DISPLAY_NAME,
                    endpoint: 'http://127.0.0.1:' + port + '/mcp',
                    headers: {},
                    protocolVersion: 'auto'
                });
                await api.servers.setState({ registrationId: created.id, state: 'active' });
                const disc = await api.servers.discover(created.id);
                const tools = (disc && Array.isArray(disc.tools)) ? disc.tools : [];
                if (!tools.some(t => t && t.nativeName === 'stvault_ping')) {
                    throw new Error('not_stvault');
                }
                state.registrationId = created.id;
                state.port = port;
                state.callMode = null;

                for (const tn of TOOL_NAMES) {
                    try {
                        await api.tools.setPermission({ registrationId: created.id, nativeName: tn, permission: 'allow' });
                    } catch (_) {}
                }

                const ping = await callTool('stvault_ping', {});
                if (!ping || ping.app !== 'stvault') throw new Error('not_stvault');

                state.connected = true;
                setStatus('已连接酒馆匣 · 端口 ' + port, 'stv-ok');
                log('连接成功（端口 ' + port + '）');
                await syncInventory();
                startPolling();
                return true;
            } catch (err) {
                log('端口 ' + port + ' 未连上：' + (err && err.message ? err.message : String(err)));
                state.registrationId = null;
                state.connected = false;
                if (created) {
                    try { await api.servers.remove(created.id); } catch (_) {}
                }
            }
        }
        setStatus('未检测到酒馆匣，请先在酒馆匣内点「开启连接」', 'stv-err');
        throw new Error('未找到酒馆匣本机通道');
    }

    function mergedSettings(raw) {
        const api = asObject(raw) || {};
        const nested = asObject(api.settings) || {};
        const ctx = contextApi();
        const src = Object.assign({}, ctx, api, nested);
        src.themes = betterArray(src.themes, ctx.themes);
        src.world_names = betterArray(src.world_names, ctx.world_names);
        src.openai_settings = betterArray(src.openai_settings, ctx.openai_settings);
        src.openai_setting_names = betterArray(src.openai_setting_names, ctx.openai_setting_names);
        return src;
    }

    async function fetchStSettings() {
        let data = {};
        try {
            const headers = await getHeaders();
            headers['Content-Type'] = 'application/json';
            const resp = await mainFetch('/api/settings/get', { method: 'POST', headers, body: '{}' });
            if (resp.ok) data = await resp.json();
        } catch (_) {}
        return mergedSettings(data);
    }

    async function fetchStCharacters() {
        try {
            const headers = await getHeaders();
            headers['Content-Type'] = 'application/json';
            const resp = await mainFetch('/api/characters/all', { method: 'POST', headers, body: '{}' });
            if (resp.ok) {
                const data = await resp.json();
                if (Array.isArray(data) && data.length > 0) return data;
            }
        } catch (_) {}
        const ctx = contextApi();
        return Array.isArray(ctx.characters) ? ctx.characters : [];
    }

    function extractPresetNames(src) {
        const names = [];
        const rawNames = src.openai_setting_names;
        if (Array.isArray(rawNames)) {
            for (const n of rawNames) if (n) names.push(String(n));
        } else if (rawNames && typeof rawNames === 'object') {
            names.push(...Object.keys(rawNames));
        }
        return names;
    }

    async function syncInventory() {
        if (!state.connected) return;
        try {
            const [src, chars] = await Promise.all([fetchStSettings(), fetchStCharacters()]);
            const themes = asArray(src.themes).map(t => {
                const obj = asObject(t);
                return obj && (obj.name || obj.theme_name);
            }).filter(Boolean);
            const worldbooks = asArray(src.world_names).map(w => String(w || '').trim()).filter(Boolean);
            const presets = extractPresetNames(src);
            const characters = chars.map(c => c && (c.name || c.avatar)).filter(Boolean);

            await callTool('stvault_sync_inventory', { themes, characters, worldbooks, presets });
            setStatus('已连接 · 美化 ' + themes.length + ' · 卡 ' + characters.length + ' · 书 ' + worldbooks.length + ' · 预设 ' + presets.length, 'stv-ok');
            log('同步清单完成：美化 ' + themes.length + ' · 角色卡 ' + characters.length + ' · 世界书 ' + worldbooks.length + ' · 预设 ' + presets.length);
        } catch (e) {
            log('同步清单失败：' + (e && e.message ? e.message : String(e)));
        }
    }

    async function pushBytesToVault(kind, fileName, bytes) {
        if (bytes.length <= FAST_LIMIT_BYTES) {
            try {
                return await callTool('stvault_push_fast', { kind, fileName, data: bytesToBase64(bytes) });
            } catch (_) {}
        }
        const begin = await callTool('stvault_push_begin', { kind, fileName, bytes: bytes.length });
        const opId = begin.opId;
        let idx = 0;
        for (let off = 0; off < bytes.length; off += CHUNK_BYTES) {
            const slice = bytes.subarray(off, Math.min(bytes.length, off + CHUNK_BYTES));
            await callTool('stvault_push_chunk', { opId, index: idx++, data: bytesToBase64(slice) });
        }
        return callTool('stvault_push_end', { opId });
    }

    async function flushBatch(batch) {
        if (!batch.length) return 0;
        try {
            const res = await callTool('stvault_push_batch', { items: batch });
            return (res && typeof res.count === 'number') ? res.count : batch.length;
        } catch (_) {
            let ok = 0;
            for (const it of batch) {
                try {
                    await pushBytesToVault(it.kind, it.fileName, base64ToBytes(it.data));
                    ok++;
                } catch (_2) {}
            }
            return ok;
        }
    }

    async function pushItemsBatched(kind, items, label) {
        let sent = 0;
        let batch = [];
        let batchBytes = 0;
        for (let i = 0; i < items.length; i++) {
            const { fileName, bytes } = items[i];
            if (bytes.length > FAST_LIMIT_BYTES) {
                if (batch.length) {
                    sent += await flushBatch(batch);
                    batch = [];
                    batchBytes = 0;
                }
                setStatus('正在推送' + label + ' ' + (i + 1) + '/' + items.length, 'stv-ok');
                await pushBytesToVault(kind, fileName, bytes);
                sent++;
                continue;
            }
            const b64 = bytesToBase64(bytes);
            if (batch.length >= BATCH_MAX_ITEMS || (batch.length > 0 && batchBytes + b64.length > BATCH_MAX_BYTES)) {
                setStatus('极速批量推送' + label + ' ' + i + '/' + items.length + '...', 'stv-ok');
                sent += await flushBatch(batch);
                batch = [];
                batchBytes = 0;
            }
            batch.push({ kind, fileName, data: b64 });
            batchBytes += b64.length;
        }
        if (batch.length) {
            setStatus('极速批量推送' + label + ' ' + items.length + '/' + items.length + '...', 'stv-ok');
            sent += await flushBatch(batch);
        }
        return sent;
    }

    async function runPool(list, concurrency, worker) {
        let idx = 0;
        const runners = [];
        const n = Math.min(concurrency, list.length);
        for (let w = 0; w < n; w++) {
            runners.push((async () => {
                while (idx < list.length) {
                    const cur = idx++;
                    await worker(list[cur], cur);
                }
            })());
        }
        await Promise.all(runners);
    }

    async function handlePull(kind) {
        log('开始极速同步：' + kind);
        setStatus('正在同步 ' + kind + ' 到酒馆匣...', 'stv-ok');
        const enc = new TextEncoder();
        let totalSent = 0;
        let failCount = 0;
        const src = await fetchStSettings();

        if (kind === 'all' || kind === 'theme') {
            const themes = asArray(src.themes).map(asObject).filter(Boolean);
            log('读取到美化 ' + themes.length + ' 个，极速批量推送中...');
            const prepared = themes.map((t, i) => ({
                fileName: String(t.name || t.theme_name || ('theme_' + (i + 1))) + '.json',
                bytes: enc.encode(JSON.stringify(t, null, 2))
            }));
            totalSent += await pushItemsBatched('theme', prepared, '美化');
        }
        if (kind === 'all' || kind === 'character') {
            const chars = (await fetchStCharacters()).filter(c => c && c.avatar);
            log('读取到角色卡 ' + chars.length + ' 张，3 路并发推送中...');
            const headers = await getHeaders();
            headers['Content-Type'] = 'application/json';
            let doneChars = 0;
            await runPool(chars, 3, async (c) => {
                try {
                    const r = await mainFetch('/api/characters/export', {
                        method: 'POST', headers,
                        body: JSON.stringify({ format: 'png', avatar_url: c.avatar })
                    });
                    if (!r.ok) { failCount++; return; }
                    const buf = new Uint8Array(await r.arrayBuffer());
                    await pushBytesToVault('character', c.avatar, buf);
                    totalSent++;
                } catch (e) {
                    failCount++;
                    log('角色卡推送失败（' + (c.name || c.avatar) + '）：' + (e && e.message ? e.message : String(e)));
                } finally {
                    doneChars++;
                    setStatus('并发推送角色卡 ' + doneChars + '/' + chars.length, 'stv-ok');
                }
            });
        }
        if (kind === 'all' || kind === 'worldbook') {
            const names = asArray(src.world_names).map(w => String(w || '').trim()).filter(Boolean);
            log('读取到世界书 ' + names.length + ' 本，并发拉取并批量推送中...');
            const headers = await getHeaders();
            headers['Content-Type'] = 'application/json';
            const prepared = [];
            await runPool(names, 4, async (name) => {
                try {
                    const r = await mainFetch('/api/worldinfo/get', { method: 'POST', headers, body: JSON.stringify({ name }) });
                    if (!r.ok) { failCount++; return; }
                    const wb = await r.json();
                    wb.name = wb.name || name;
                    prepared.push({ fileName: name + '.json', bytes: enc.encode(JSON.stringify(wb, null, 2)) });
                } catch (_) {
                    failCount++;
                }
            });
            totalSent += await pushItemsBatched('worldbook', prepared, '世界书');
        }
        if (kind === 'all' || kind === 'preset') {
            const presetsArr = asArray(src.openai_settings);
            const rawNames = src.openai_setting_names;
            const prepared = [];
            if (Array.isArray(rawNames)) {
                presetsArr.forEach((raw, idx) => {
                    const obj = asObject(raw);
                    if (obj) {
                        const pName = String(rawNames[idx] || obj.name || ('preset_' + (idx + 1)));
                        prepared.push({ fileName: pName + '.json', bytes: enc.encode(JSON.stringify(obj, null, 2)) });
                    }
                });
            } else if (rawNames && typeof rawNames === 'object') {
                for (const [pName, pIdx] of Object.entries(rawNames)) {
                    const obj = asObject(presetsArr[pIdx]);
                    if (obj) prepared.push({ fileName: pName + '.json', bytes: enc.encode(JSON.stringify(obj, null, 2)) });
                }
            }
            log('读取到预设 ' + prepared.length + ' 个，极速批量推送中...');
            totalSent += await pushItemsBatched('preset', prepared, '预设');
        }

        const summary = '同步完成：推送 ' + totalSent + ' 项' + (failCount > 0 ? '（失败 ' + failCount + '）' : '');
        try { await callTool('stvault_pull_done', { summary }); } catch (_) {}
        setStatus(summary, 'stv-ok');
        log(summary);
    }

    async function fetchOutboxBytes(id) {
        const chunks = [];
        let offset = 0;
        let total = 0;
        while (true) {
            const res = await callTool('stvault_outbox_fetch', { id, offset, length: CHUNK_BYTES });
            const part = base64ToBytes(res.data || '');
            chunks.push(part);
            offset += part.length;
            total += part.length;
            if (res.eof || part.length === 0) break;
        }
        const out = new Uint8Array(total);
        let pos = 0;
        for (const c of chunks) { out.set(c, pos); pos += c.length; }
        return out;
    }

    async function installIntoTavern(item, bytes) {
        const headers = await getHeaders();
        const text = () => new TextDecoder('utf-8').decode(bytes);
        const stripExt = (n) => String(n || '').replace(/\.(json|png|css|txt|js)$/i, '');

        if (item.kind === 'theme') {
            headers['Content-Type'] = 'application/json';
            const themeObj = JSON.parse(text());
            themeObj.name = themeObj.name || stripExt(item.name || item.fileName);
            const r = await mainFetch('/api/themes/save', { method: 'POST', headers, body: JSON.stringify(themeObj) });
            if (!r.ok) throw new Error('保存美化失败');
            return;
        }
        if (item.kind === 'character') {
            delete headers['Content-Type'];
            delete headers['content-type'];
            const isJson = /\.json$/i.test(item.fileName);
            const form = new FormData();
            form.append('avatar', new Blob([bytes], { type: isJson ? 'application/json' : 'image/png' }), item.fileName);
            form.append('file_type', isJson ? 'json' : 'png');
            const r = await mainFetch('/api/characters/import', { method: 'POST', headers, body: form });
            if (!r.ok) throw new Error('导入角色卡失败');
            return;
        }
        if (item.kind === 'worldbook') {
            delete headers['Content-Type'];
            delete headers['content-type'];
            const fn = /\.json$/i.test(item.fileName) ? item.fileName : (item.fileName + '.json');
            const form = new FormData();
            form.append('avatar', new Blob([bytes], { type: 'application/json' }), fn);
            const r = await mainFetch('/api/worldinfo/import', { method: 'POST', headers, body: form });
            if (!r.ok) throw new Error('导入世界书失败');
            return;
        }
        if (item.kind === 'preset') {
            headers['Content-Type'] = 'application/json';
            const preset = JSON.parse(text());
            const r = await mainFetch('/api/presets/save', {
                method: 'POST', headers,
                body: JSON.stringify({ name: stripExt(item.name || item.fileName), apiId: 'openai', preset })
            });
            if (!r.ok) throw new Error('保存预设失败');
            return;
        }
        if (item.kind === 'regex') {
            const ctx = contextApi();
            const settings = ctx.extensionSettings || ctx.extension_settings;
            if (!settings) throw new Error('无法访问正则设置');
            const parsed = JSON.parse(text());
            const list = Array.isArray(parsed) ? parsed : [parsed];
            const cur = Array.isArray(settings.regex) ? settings.regex.slice() : [];
            for (const s of list) cur.push(s);
            settings.regex = cur;
            await saveStSettings();
            return;
        }
        if (item.kind === 'qr') {
            headers['Content-Type'] = 'application/json';
            const qr = JSON.parse(text());
            const r = await mainFetch('/api/quick-replies/save', { method: 'POST', headers, body: JSON.stringify(qr) });
            if (!r.ok) throw new Error('保存快速回复失败');
        }
    }

    async function processOutbox() {
        const listed = await callTool('stvault_outbox_list', {});
        const items = (listed && Array.isArray(listed.items)) ? listed.items : [];
        if (items.length === 0) return;

        let okCount = 0;
        for (const item of items) {
            try {
                const bytes = await fetchOutboxBytes(item.id);
                await installIntoTavern(item, bytes);
                await callTool('stvault_outbox_ack', { id: item.id });
                okCount++;
                log('已装入：' + item.name);
            } catch (e) {
                log('装入失败（' + item.name + '）：' + e.message);
            }
        }
        if (okCount > 0) await syncInventory();
    }

    async function pollOnce() {
        if (!state.connected || state.busy || mainDoc.visibilityState !== 'visible') return;
        state.busy = true;
        try {
            await processOutbox();
            const pullRes = await callTool('stvault_pull_poll', {});
            if (pullRes && pullRes.pullKind) {
                await handlePull(pullRes.pullKind);
            }
        } catch (e) {
            state.connected = false;
            setStatus('连接已断开，请重新点「连接酒馆匣」', 'stv-err');
            if (state.pollTimer) { clearInterval(state.pollTimer); state.pollTimer = null; }
        } finally {
            state.busy = false;
        }
    }

    function startPolling() {
        if (state.pollTimer) clearInterval(state.pollTimer);
        state.pollTimer = setInterval(pollOnce, 3000);
    }

    function injectStyles() {
        if (mainDoc.getElementById('stv_bridge_inline_style')) return;
        const st = mainDoc.createElement('style');
        st.id = 'stv_bridge_inline_style';
        st.textContent = `
.stv-bridge-box{display:flex;flex-direction:column;gap:8px;padding:6px 2px}
.stv-bridge-status{font-size:12.5px;padding:6px 10px;border-radius:8px;background:rgba(128,128,128,.12);opacity:.9}
.stv-bridge-status.stv-ok{background:rgba(22,163,74,.16);color:#22c55e}
.stv-bridge-status.stv-err{background:rgba(220,38,38,.16);color:#f87171}
.stv-bridge-row{display:flex;flex-wrap:wrap;gap:6px}
.stv-bridge-row .menu_button{flex:1 1 42%;justify-content:center;text-align:center;padding:7px 10px;font-size:12.5px}
.stv-bridge-log{font-size:11px;line-height:1.45;max-height:120px;overflow-y:auto;white-space:pre-wrap;word-break:break-all;padding:8px;border-radius:8px;background:rgba(0,0,0,.18);opacity:.8}
`;
        (mainDoc.head || mainDoc.documentElement).appendChild(st);
    }

    function buildHtml() {
        return `
<div class="inline-drawer">
  <div class="inline-drawer-toggle inline-drawer-header">
    <b>酒馆匣互联</b>
    <div class="inline-drawer-icon fa-solid fa-circle-chevron-down down"></div>
  </div>
  <div class="inline-drawer-content">
    <div class="stv-bridge-box">
      <div class="stv-bridge-status" id="stv_bridge_status">未连接 · 请先在酒馆匣打开「酒馆互联 → 开启连接」</div>
      <div class="stv-bridge-row">
        <div class="menu_button" id="stv_btn_connect">连接酒馆匣</div>
        <div class="menu_button" id="stv_btn_sync">同步清单</div>
        <div class="menu_button" id="stv_btn_push_themes">推美化</div>
        <div class="menu_button" id="stv_btn_push_chars">推角色卡</div>
        <div class="menu_button" id="stv_btn_push_wbs">推世界书</div>
        <div class="menu_button" id="stv_btn_push_presets">推预设</div>
        <div class="menu_button" id="stv_btn_receive">接收待装入</div>
      </div>
      <div class="stv-bridge-log" id="stv_bridge_log"></div>
    </div>
  </div>
</div>`;
    }

    function bindPanelButtons(root) {
        const byId = (id) => root.querySelector('#' + id);
        const btnConn = byId('stv_btn_connect');
        if (btnConn) btnConn.addEventListener('click', async () => {
            setStatus('正在连接酒馆匣...', '');
            try { await connect(); } catch (e) { log(e.message); }
        });
        const btnSync = byId('stv_btn_sync');
        if (btnSync) btnSync.addEventListener('click', async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await syncInventory();
        });
        const btnThemes = byId('stv_btn_push_themes');
        if (btnThemes) btnThemes.addEventListener('click', async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await handlePull('theme');
        });
        const btnChars = byId('stv_btn_push_chars');
        if (btnChars) btnChars.addEventListener('click', async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await handlePull('character');
        });
        const btnWbs = byId('stv_btn_push_wbs');
        if (btnWbs) btnWbs.addEventListener('click', async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await handlePull('worldbook');
        });
        const btnPresets = byId('stv_btn_push_presets');
        if (btnPresets) btnPresets.addEventListener('click', async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await handlePull('preset');
        });
        const btnRecv = byId('stv_btn_receive');
        if (btnRecv) btnRecv.addEventListener('click', async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await processOutbox();
        });
    }

    function openFloatingModal() {
        let modal = mainDoc.getElementById('stv_bridge_modal');
        if (modal) {
            modal.style.display = 'flex';
            return;
        }
        modal = mainDoc.createElement('div');
        modal.id = 'stv_bridge_modal';
        modal.style.cssText = 'position:fixed;inset:0;z-index:99999;background:rgba(0,0,0,.55);display:flex;align-items:center;justify-content:center;padding:16px;';
        modal.innerHTML = `
<div style="width:100%;max-width:420px;background:var(--SmartThemeBlurTintColor,#18181b);color:var(--SmartThemeBodyColor,#f4f4f5);border:1px solid rgba(255,255,255,.12);border-radius:14px;padding:14px 16px;box-shadow:0 12px 32px rgba(0,0,0,.45);">
  <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:10px;">
    <b style="font-size:15px;">酒馆匣互联</b>
    <span id="stv_modal_close" style="cursor:pointer;padding:4px 8px;opacity:.75;font-size:14px;">✕</span>
  </div>
  <div class="stv-bridge-box">
    <div class="stv-bridge-status" id="stv_bridge_status">未连接 · 请先在酒馆匣打开「酒馆互联 → 开启连接」</div>
    <div class="stv-bridge-row">
      <div class="menu_button" id="stv_btn_connect">连接酒馆匣</div>
      <div class="menu_button" id="stv_btn_sync">同步清单</div>
      <div class="menu_button" id="stv_btn_push_themes">推美化</div>
      <div class="menu_button" id="stv_btn_push_chars">推角色卡</div>
      <div class="menu_button" id="stv_btn_push_wbs">推世界书</div>
      <div class="menu_button" id="stv_btn_push_presets">推预设</div>
      <div class="menu_button" id="stv_btn_receive">接收待装入</div>
    </div>
    <div class="stv-bridge-log" id="stv_bridge_log"></div>
  </div>
</div>`;
        modal.addEventListener('click', (e) => {
            if (e.target === modal) modal.style.display = 'none';
        });
        mainDoc.body.appendChild(modal);
        const closeBtn = modal.querySelector('#stv_modal_close');
        if (closeBtn) closeBtn.addEventListener('click', () => { modal.style.display = 'none'; });
        bindPanelButtons(modal);
    }

    function mountWandMenu() {
        const menu = mainDoc.getElementById('extensionsMenu');
        if (!menu || mainDoc.getElementById('stv_wand_item')) return;
        const item = mainDoc.createElement('div');
        item.id = 'stv_wand_item';
        item.className = 'list-group-item flex-container flexGap5 interactable';
        item.tabIndex = 0;
        item.innerHTML = '<div class="fa-solid fa-plug extensionsMenuExtensionButton"></div><span>酒馆匣互联</span>';
        item.addEventListener('click', () => {
            openFloatingModal();
        });
        menu.appendChild(item);
    }

    function mount() {
        injectStyles();
        mountWandMenu();
        const container = mainDoc.getElementById('extensions_settings2') || mainDoc.getElementById('extensions_settings');
        if (container && !mainDoc.getElementById('stv_drawer_wrap')) {
            const wrap = mainDoc.createElement('div');
            wrap.id = 'stv_drawer_wrap';
            wrap.innerHTML = buildHtml();
            container.appendChild(wrap);
            bindPanelButtons(wrap);
        }
    }

    function init() {
        mount();
        const timer = setInterval(() => {
            mount();
            if (mainDoc.getElementById('stv_wand_item') && mainDoc.getElementById('stv_drawer_wrap')) {
                clearInterval(timer);
            }
        }, 800);
    }

    if (mainDoc.readyState === 'loading') {
        mainDoc.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
