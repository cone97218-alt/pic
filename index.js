/**
 * 酒馆匣互联（酒馆匣 <-> TauriTavern）
 * 通过 TT 酒馆自带的本机 MCP 客户端直连 127.0.0.1:8796-8800，
 * 不走云端、无需账号，实现在库状态比对、一键装入与拉取同步。
 */
(function () {
    'use strict';

    const DISPLAY_NAME = '酒馆匣互联';
    const MODULE_NAME = 'stVaultBridge';
    const CANDIDATE_PORTS = [8796, 8797, 8798, 8799, 8800];
    const CHUNK_BYTES = 48 * 1024;
    const TOOL_NAMES = [
        'stvault_ping', 'stvault_sync_inventory',
        'stvault_outbox_list', 'stvault_outbox_fetch', 'stvault_outbox_ack',
        'stvault_pull_poll', 'stvault_pull_done',
        'stvault_push_begin', 'stvault_push_chunk', 'stvault_push_end'
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
        const v = window.__TAURITAVERN__;
        if (!v || !v.api || !v.api.mcp) {
            throw new Error('需要 TauriTavern 2.3.0 及以上版本');
        }
        return v;
    }

    async function hostReady() {
        const p = (window.__TAURITAVERN__ && window.__TAURITAVERN__.ready) || window.__TAURITAVERN_MAIN_READY__;
        if (p && typeof p.then === 'function') {
            try { await p; } catch (_) {}
        }
        return host();
    }

    async function getHeaders() {
        const mod = await import('../../../../script.js');
        return mod.getRequestHeaders();
    }

    async function saveStSettings() {
        try {
            const mod = await import('../../../../script.js');
            if (typeof mod.saveSettingsDebounced === 'function') mod.saveSettingsDebounced();
        } catch (_) {}
    }

    function setStatus(text, cls) {
        const el = document.getElementById('stv_bridge_status');
        if (!el) return;
        el.textContent = text;
        el.classList.remove('stv-ok', 'stv-err');
        if (cls) el.classList.add(cls);
    }

    function log(msg) {
        const box = document.getElementById('stv_bridge_log');
        if (!box) return;
        const line = '[' + new Date().toLocaleTimeString() + '] ' + msg + '\n';
        box.textContent = (line + box.textContent).slice(0, 6000);
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
        const executionCallId = crypto.randomUUID();
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
            } catch (_) {
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

    async function fetchStSettings() {
        const headers = await getHeaders();
        headers['Content-Type'] = 'application/json';
        const resp = await fetch('/api/settings/get', { method: 'POST', headers, body: '{}' });
        if (!resp.ok) throw new Error('读取酒馆设置失败');
        return resp.json();
    }

    async function fetchStCharacters() {
        const headers = await getHeaders();
        headers['Content-Type'] = 'application/json';
        const resp = await fetch('/api/characters/all', { method: 'POST', headers, body: '{}' });
        if (!resp.ok) return [];
        const data = await resp.json();
        return Array.isArray(data) ? data : [];
    }

    async function syncInventory() {
        if (!state.connected) return;
        try {
            const [settingsData, chars] = await Promise.all([fetchStSettings(), fetchStCharacters()]);
            const themes = Array.isArray(settingsData.themes) ? settingsData.themes.map(t => t && t.name).filter(Boolean) : [];
            const worldbooks = Array.isArray(settingsData.world_names) ? settingsData.world_names.filter(Boolean) : [];
            const presets = [];
            if (settingsData.openai_setting_names && typeof settingsData.openai_setting_names === 'object') {
                presets.push(...Object.keys(settingsData.openai_setting_names));
            }
            const characters = chars.map(c => c && (c.name || c.avatar)).filter(Boolean);

            await callTool('stvault_sync_inventory', { themes, characters, worldbooks, presets });
            log('已同步在库清单：美化 ' + themes.length + ' · 角色卡 ' + characters.length + ' · 世界书 ' + worldbooks.length + ' · 预设 ' + presets.length);
        } catch (e) {
            log('同步清单失败：' + e.message);
        }
    }

    async function pushBytesToVault(kind, fileName, bytes) {
        const begin = await callTool('stvault_push_begin', { kind, fileName, bytes: bytes.length });
        const opId = begin.opId;
        let idx = 0;
        for (let off = 0; off < bytes.length; off += CHUNK_BYTES) {
            const slice = bytes.subarray(off, Math.min(bytes.length, off + CHUNK_BYTES));
            await callTool('stvault_push_chunk', { opId, index: idx++, data: bytesToBase64(slice) });
        }
        return callTool('stvault_push_end', { opId });
    }

    async function handlePull(kind) {
        log('收到酒馆匣拉取请求：' + kind);
        const enc = new TextEncoder();
        let totalSent = 0;
        const settingsData = await fetchStSettings();

        if (kind === 'all' || kind === 'theme') {
            const themes = Array.isArray(settingsData.themes) ? settingsData.themes : [];
            for (const t of themes) {
                if (!t || !t.name) continue;
                const bytes = enc.encode(JSON.stringify(t, null, 2));
                await pushBytesToVault('theme', t.name + '.json', bytes);
                totalSent++;
            }
        }
        if (kind === 'all' || kind === 'worldbook') {
            const names = Array.isArray(settingsData.world_names) ? settingsData.world_names : [];
            const headers = await getHeaders();
            headers['Content-Type'] = 'application/json';
            for (const name of names) {
                try {
                    const r = await fetch('/api/worldinfo/get', { method: 'POST', headers, body: JSON.stringify({ name }) });
                    if (!r.ok) continue;
                    const wb = await r.json();
                    wb.name = wb.name || name;
                    await pushBytesToVault('worldbook', name + '.json', enc.encode(JSON.stringify(wb, null, 2)));
                    totalSent++;
                } catch (_) {}
            }
        }
        if (kind === 'all' || kind === 'preset') {
            const map = settingsData.openai_setting_names || {};
            const presetsArr = Array.isArray(settingsData.openai_settings) ? settingsData.openai_settings : [];
            for (const [pName, pIdx] of Object.entries(map)) {
                const raw = presetsArr[pIdx];
                if (!raw) continue;
                const text = typeof raw === 'string' ? raw : JSON.stringify(raw, null, 2);
                await pushBytesToVault('preset', pName + '.json', enc.encode(text));
                totalSent++;
            }
        }
        if (kind === 'all' || kind === 'character') {
            const chars = await fetchStCharacters();
            const headers = await getHeaders();
            headers['Content-Type'] = 'application/json';
            for (const c of chars) {
                if (!c || !c.avatar) continue;
                try {
                    const r = await fetch('/api/characters/export', {
                        method: 'POST', headers,
                        body: JSON.stringify({ format: 'png', avatar_url: c.avatar })
                    });
                    if (!r.ok) continue;
                    const buf = new Uint8Array(await r.arrayBuffer());
                    await pushBytesToVault('character', c.avatar, buf);
                    totalSent++;
                } catch (_) {}
            }
        }

        const summary = '已同步 ' + totalSent + ' 项至酒馆匣';
        await callTool('stvault_pull_done', { summary });
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
            const r = await fetch('/api/themes/save', { method: 'POST', headers, body: JSON.stringify(themeObj) });
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
            const r = await fetch('/api/characters/import', { method: 'POST', headers, body: form });
            if (!r.ok) throw new Error('导入角色卡失败');
            return;
        }
        if (item.kind === 'worldbook') {
            delete headers['Content-Type'];
            delete headers['content-type'];
            const fn = /\.json$/i.test(item.fileName) ? item.fileName : (item.fileName + '.json');
            const form = new FormData();
            form.append('avatar', new Blob([bytes], { type: 'application/json' }), fn);
            const r = await fetch('/api/worldinfo/import', { method: 'POST', headers, body: form });
            if (!r.ok) throw new Error('导入世界书失败');
            return;
        }
        if (item.kind === 'preset') {
            headers['Content-Type'] = 'application/json';
            const preset = JSON.parse(text());
            const r = await fetch('/api/presets/save', {
                method: 'POST', headers,
                body: JSON.stringify({ name: stripExt(item.name || item.fileName), apiId: 'openai', preset })
            });
            if (!r.ok) throw new Error('保存预设失败');
            return;
        }
        if (item.kind === 'regex') {
            const extMod = await import('../../../extensions.js');
            const parsed = JSON.parse(text());
            const list = Array.isArray(parsed) ? parsed : [parsed];
            const cur = Array.isArray(extMod.extension_settings.regex) ? extMod.extension_settings.regex.slice() : [];
            for (const s of list) cur.push(s);
            extMod.extension_settings.regex = cur;
            await saveStSettings();
            return;
        }
        if (item.kind === 'qr') {
            headers['Content-Type'] = 'application/json';
            const qr = JSON.parse(text());
            const r = await fetch('/api/quick-replies/save', { method: 'POST', headers, body: JSON.stringify(qr) });
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
        if (okCount > 0) {
            await syncInventory();
        }
    }

    async function pollOnce() {
        if (!state.connected || state.busy || document.visibilityState !== 'visible') return;
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
        <div class="menu_button" id="stv_btn_push_themes">推送全部美化</div>
        <div class="menu_button" id="stv_btn_receive">检查待装入</div>
      </div>
      <div class="stv-bridge-log" id="stv_bridge_log"></div>
    </div>
  </div>
</div>`;
    }

    function mount() {
        const container = document.getElementById('extensions_settings2') || document.getElementById('extensions_settings');
        if (!container || document.getElementById('stv_bridge_status')) return;
        const wrap = document.createElement('div');
        wrap.innerHTML = buildHtml();
        container.appendChild(wrap);

        document.getElementById('stv_btn_connect').addEventListener('click', async () => {
            setStatus('正在连接酒馆匣...', '');
            try { await connect(); } catch (e) { log(e.message); }
        });
        document.getElementById('stv_btn_sync').addEventListener('click', async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await syncInventory();
        });
        document.getElementById('stv_btn_push_themes').addEventListener('click', async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await handlePull('theme');
        });
        document.getElementById('stv_btn_receive').addEventListener('click', async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await processOutbox();
        });
    }

    function init() {
        const timer = setInterval(() => {
            if (document.getElementById('extensions_settings2') || document.getElementById('extensions_settings')) {
                clearInterval(timer);
                mount();
            }
        }, 600);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
