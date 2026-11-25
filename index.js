/**
 * 酒馆匣互联（酒馆匣 <-> TauriTavern）
 * 同时支持「酒馆助手热更新脚本」与「TT 原生扩展」双模式运行。
 * 通过 TT 自带本机 MCP 客户端直连 127.0.0.1:8796-8800，不上云、无需账号。
 */
(function () {
    'use strict';

    function getMainWin() {
        try { if (window.parent && window.parent !== window && window.parent.document) return window.parent; } catch (_) {}
        try { if (window.top && window.top !== window && window.top.document) return window.top; } catch (_) {}
        return window;
    }

    const mainWin = getMainWin();
    const mainDoc = mainWin.document;

    // Prevent double mounting when hot-reloaded or when both extension & helper are active
    if (mainWin.__STVAULT_BRIDGE_LOADED__ && mainDoc.getElementById('stv_drawer_wrap')) {
        return;
    }
    mainWin.__STVAULT_BRIDGE_LOADED__ = true;

    const DISPLAY_NAME = '酒馆匣互联';
    const CANDIDATE_PORTS = [8796, 8797, 8798, 8799, 8800];
    // Stay safely within Tauri Android IPC argument size limit (~100-128 KB)
    const CHUNK_BYTES = 60 * 1024;
    const FAST_LIMIT_BYTES = 60 * 1024;
    const BATCH_MAX_ITEMS = 5;
    const BATCH_MAX_BYTES = 84 * 1024;
    const TOOL_NAMES = [
        'stvault_ping', 'stvault_sync_inventory',
        'stvault_outbox_list', 'stvault_outbox_fetch', 'stvault_outbox_ack',
        'stvault_pull_poll', 'stvault_pull_done',
        'stvault_push_begin', 'stvault_push_chunk', 'stvault_push_end',
        'stvault_push_fast', 'stvault_push_batch', 'stvault_sync_theme_tags'
    ];

    const state = {
        registrationId: null,
        port: 0,
        connected: false,
        busy: false,
        callMode: null,
        pollTimer: null,
        pollFailCount: 0,
        pushedCache: {} // kind -> { ts: number, done: Set<string> }
    };

    function getCheckpointSet(kind) {
        const now = Date.now();
        let entry = state.pushedCache[kind];
        if (!entry || now - entry.ts > 30 * 60 * 1000) {
            entry = { ts: now, done: new Set() };
            state.pushedCache[kind] = entry;
        }
        entry.ts = now;
        return entry.done;
    }

    function clearCheckpoint(kind) {
        delete state.pushedCache[kind];
    }

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

    async function callToolWithRetry(name, args) {
        try {
            return await callTool(name, args);
        } catch (err) {
            const msg = err && err.message ? err.message : String(err);
            log('调用 ' + name + ' 异常（' + msg + '），重试一次...');
            await new Promise((r) => setTimeout(r, 400));
            try {
                return await callTool(name, args);
            } catch (_) {
                await connect(true);
                return await callTool(name, args);
            }
        }
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

    async function connect(silent) {
        if (!silent) log('正在初始化本机 MCP 通道...');
        const api = (await hostReady()).api.mcp;
        await forgetOldServers();

        for (const port of CANDIDATE_PORTS) {
            let created = null;
            try {
                if (!silent) {
                    setStatus('正在探测端口 ' + port + '...', '');
                    log('探测端口 ' + port + '...');
                }
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
                if (!silent) {
                    setStatus('已连接酒馆匣 · 端口 ' + port, 'stv-ok');
                    log('连接成功（端口 ' + port + '）');
                    await syncInventory();
                    await syncThemeTagsBidirectional(true);
                    startPolling();
                }
                return true;
            } catch (err) {
                if (!silent) log('端口 ' + port + ' 未连上：' + (err && err.message ? err.message : String(err)));
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

    function extractCompanionTagPayload() {
        const tagPaths = [];
        const themeTags = {};
        const backgroundBindings = {};
        try {
            const tm = mainWin.themeManager;
            if (tm && typeof tm.getTags === 'function') {
                const tags = asArray(tm.getTags());
                const byId = new Map();
                for (const t of tags) {
                    if (t && t.id && t.name) byId.set(t.id, t);
                }
                function resolveFullPath(tag) {
                    const segs = [];
                    const visited = new Set();
                    let cur = tag;
                    while (cur && !visited.has(cur.id)) {
                        visited.add(cur.id);
                        segs.unshift(String(cur.name).trim());
                        cur = cur.parentId ? byId.get(cur.parentId) : null;
                    }
                    return segs.filter(Boolean).slice(0, 3).join('/');
                }
                for (const t of tags) {
                    if (!t || !t.name) continue;
                    const path = resolveFullPath(t);
                    if (!path) continue;
                    if (!tagPaths.includes(path)) tagPaths.push(path);
                    const themes = asArray(t.themes);
                    for (const tn of themes) {
                        const cleanName = String(tn || '').trim();
                        if (!cleanName) continue;
                        if (!themeTags[cleanName]) themeTags[cleanName] = [];
                        if (!themeTags[cleanName].includes(path)) {
                            themeTags[cleanName].push(path);
                        }
                    }
                }
            }
            // Extract theme <-> background bindings from st-theme-companion
            const ctx = contextApi();
            const extSet = (ctx.extensionSettings || ctx.extension_settings || {});
            const compSet = extSet['st-theme-companion'] || extSet['theme-companion'] || extSet['themeManager'] || {};
            const bgMap = compSet.backgroundBindings || (tm && tm.getBackgroundBindings && tm.getBackgroundBindings()) || null;
            if (bgMap && typeof bgMap === 'object') {
                for (const [k, v] of Object.entries(bgMap)) {
                    if (k && typeof v === 'string' && v.trim()) backgroundBindings[k] = v.trim();
                }
            }
        } catch (_) {}
        return { tagPaths, themeTags, backgroundBindings };
    }

    async function syncThemeTagsBidirectional(silent) {
        if (!state.connected) return;
        try {
            const payload = extractCompanionTagPayload();
            const res = await callToolWithRetry('stvault_sync_theme_tags', payload);
            if (!res || !res.ok) return;

            const vaultPaths = asArray(res.vaultTagPaths);
            const vaultThemeTags = asObject(res.vaultThemeTags) || {};
            const vaultBgBindings = asObject(res.backgroundBindings) || {};

            // Broadcast vault tags & background bindings to st-theme-companion
            try {
                mainDoc.dispatchEvent(new CustomEvent('stvault:tagsSynced', {
                    detail: {
                        tagPaths: vaultPaths,
                        themeTags: vaultThemeTags,
                        backgroundBindings: vaultBgBindings
                    }
                }));
            } catch (_) {}

            if (!silent) {
                const uApp = res.updatedThemesInApp || 0;
                const aCat = res.addedCategoryPaths || 0;
                const uBg = res.updatedBgBindings || 0;
                log('联动同步完成：标签更新 ' + uApp + ' 项（新分类 ' + aCat + '），背景绑定 ' + uBg + ' 项');
            }
        } catch (e) {
            if (!silent) log('标签同步失败：' + (e && e.message ? e.message : String(e)));
        }
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

    async function pushBytesToVault(kind, fileName, bytes, tags) {
        const tagArr = Array.isArray(tags) && tags.length > 0 ? tags : undefined;
        if (bytes.length <= FAST_LIMIT_BYTES) {
            try {
                return await callToolWithRetry('stvault_push_fast', { kind, fileName, data: bytesToBase64(bytes), tags: tagArr });
            } catch (_) {}
        }
        const begin = await callToolWithRetry('stvault_push_begin', { kind, fileName, bytes: bytes.length, tags: tagArr });
        const opId = begin.opId;
        let idx = 0;
        for (let off = 0; off < bytes.length; off += CHUNK_BYTES) {
            const slice = bytes.subarray(off, Math.min(bytes.length, off + CHUNK_BYTES));
            await callToolWithRetry('stvault_push_chunk', { opId, index: idx++, data: bytesToBase64(slice) });
        }
        return callToolWithRetry('stvault_push_end', { opId });
    }

    async function flushBatch(batch) {
        if (!batch.length) return 0;
        try {
            const res = await callToolWithRetry('stvault_push_batch', { items: batch });
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
        const doneSet = getCheckpointSet(kind);
        const pending = items.filter((it) => !doneSet.has(it.fileName));
        const skipped = items.length - pending.length;
        if (skipped > 0) {
            log('断点续传：跳过已推 ' + skipped + ' 个，继续推送剩余 ' + pending.length + ' 个' + label);
        }
        if (pending.length === 0) {
            clearCheckpoint(kind);
            return 0;
        }

        let sent = 0;
        let batch = [];
        let batchNames = [];
        let batchBytes = 0;
        let hadError = false;

        async function commitBatch() {
            if (!batch.length) return;
            const n = await flushBatch(batch);
            if (n > 0) {
                sent += n;
                for (const fn of batchNames) doneSet.add(fn);
            } else {
                hadError = true;
            }
            batch = [];
            batchNames = [];
            batchBytes = 0;
        }

        for (let i = 0; i < pending.length; i++) {
            const { fileName, bytes, tags } = pending[i];
            const prog = skipped + i + 1;
            if (bytes.length > FAST_LIMIT_BYTES) {
                await commitBatch();
                setStatus('正在推送' + label + ' ' + prog + '/' + items.length, 'stv-ok');
                try {
                    await pushBytesToVault(kind, fileName, bytes, tags);
                    doneSet.add(fileName);
                    sent++;
                } catch (e) {
                    hadError = true;
                    log(label + '推送失败（' + fileName + '）：' + (e && e.message ? e.message : String(e)));
                }
                continue;
            }
            const b64 = bytesToBase64(bytes);
            if (batch.length >= BATCH_MAX_ITEMS || (batch.length > 0 && batchBytes + b64.length > BATCH_MAX_BYTES)) {
                setStatus('极速批量推送' + label + ' ' + (skipped + i) + '/' + items.length + '...', 'stv-ok');
                await commitBatch();
            }
            const entry = { kind, fileName, data: b64 };
            if (Array.isArray(tags) && tags.length > 0) entry.tags = tags;
            batch.push(entry);
            batchNames.push(fileName);
            batchBytes += b64.length;
        }
        if (batch.length) {
            setStatus('极速批量推送' + label + ' ' + items.length + '/' + items.length + '...', 'stv-ok');
            await commitBatch();
        }
        if (!hadError && doneSet.size >= items.length) {
            clearCheckpoint(kind);
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
            await syncThemeTagsBidirectional(true);
            const companionMap = extractCompanionTagPayload().themeTags;
            const themes = asArray(src.themes).map(asObject).filter(Boolean);
            log('读取到美化 ' + themes.length + ' 个，极速批量推送中...');
            const prepared = themes.map((t, i) => {
                const tName = String(t.name || t.theme_name || ('theme_' + (i + 1)));
                const cTags = companionMap[tName] || [];
                const xiaTags = (t._xia && Array.isArray(t._xia.tags)) ? t._xia.tags : [];
                const mergedTags = Array.from(new Set([...cTags, ...xiaTags])).filter(Boolean);
                const copy = Object.assign({}, t);
                if (mergedTags.length > 0) {
                    copy._xia = Object.assign({}, copy._xia || {}, { tags: mergedTags });
                }
                return {
                    fileName: tName + '.json',
                    bytes: enc.encode(JSON.stringify(copy, null, 2)),
                    tags: mergedTags
                };
            });
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
            try {
                const tags = Array.isArray(item.tags) ? item.tags : [];
                const category = typeof item.category === 'string' ? item.category : (typeof item.folder === 'string' ? item.folder : '');
                mainDoc.dispatchEvent(new CustomEvent('stvault:themePushed', {
                    detail: { theme: themeObj, tags, category, autoApply: Boolean(item.autoApply) }
                }));
            } catch (_) {}
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
            return;
        }
        if (item.kind === 'background') {
            delete headers['Content-Type'];
            delete headers['content-type'];
            const fn = item.fileName || (item.name + '.png');
            const mime = /\.jpe?g$/i.test(fn) ? 'image/jpeg' : (/\.webp$/i.test(fn) ? 'image/webp' : 'image/png');
            const form = new FormData();
            form.append('avatar', new Blob([bytes], { type: mime }), fn);
            const r = await mainFetch('/api/backgrounds/upload', { method: 'POST', headers, body: form });
            if (!r.ok) throw new Error('上传背景图失败');
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
            state.pollFailCount = 0;
            if (pullRes && pullRes.pullKind) {
                await handlePull(pullRes.pullKind);
            }
        } catch (e) {
            state.pollFailCount++;
            if (state.pollFailCount >= 2) {
                state.connected = false;
                setStatus('连接已断开，请重新点「连接酒馆匣」', 'stv-err');
                if (state.pollTimer) { clearInterval(state.pollTimer); state.pollTimer = null; }
            }
        } finally {
            state.busy = false;
        }
    }

    async function pushSingleItem(kind, targetName) {
        if (!state.connected) {
            try { await connect(); } catch (e) { log(e.message); return; }
        }
        const enc = new TextEncoder();
        const headers = await getHeaders();
        headers['Content-Type'] = 'application/json';
        setStatus('正在推送单个' + kind + '...', 'stv-ok');

        if (kind === 'theme') {
            const src = await fetchStSettings();
            const themes = asArray(src.themes).map(asObject).filter(Boolean);
            const nameToFind = targetName || (mainDoc.querySelector('#themes')?.value || '');
            const hit = themes.find(t => String(t.name || t.theme_name || '') === nameToFind) || themes[0];
            if (!hit) { log('未找到目标美化'); return; }
            const tName = String(hit.name || hit.theme_name || 'theme');
            const companionMap = extractCompanionTagPayload().themeTags;
            const mergedTags = Array.from(new Set([...(companionMap[tName] || []), ...((hit._xia && Array.isArray(hit._xia.tags)) ? hit._xia.tags : [])])).filter(Boolean);
            const copy = Object.assign({}, hit);
            if (mergedTags.length > 0) copy._xia = Object.assign({}, copy._xia || {}, { tags: mergedTags });
            await pushBytesToVault('theme', tName + '.json', enc.encode(JSON.stringify(copy, null, 2)), mergedTags);
            setStatus('已推送美化：' + tName, 'stv-ok');
            log('单项推送美化成功：' + tName);
            return;
        }
        if (kind === 'character') {
            const chars = (await fetchStCharacters()).filter(c => c && c.avatar);
            const ctx = contextApi();
            const curChar = (ctx.characters && ctx.characterId != null) ? ctx.characters[ctx.characterId] : null;
            const hit = targetName
                ? chars.find(c => c.name === targetName || c.avatar === targetName)
                : (curChar && curChar.avatar ? curChar : chars[0]);
            if (!hit || !hit.avatar) { log('当前未打开角色卡或未找到目标角色卡'); return; }
            const r = await mainFetch('/api/characters/export', {
                method: 'POST', headers, body: JSON.stringify({ format: 'png', avatar_url: hit.avatar })
            });
            if (!r.ok) throw new Error('导出角色卡失败');
            await pushBytesToVault('character', hit.avatar, new Uint8Array(await r.arrayBuffer()));
            setStatus('已推送角色卡：' + (hit.name || hit.avatar), 'stv-ok');
            log('单项推送角色卡成功：' + (hit.name || hit.avatar));
            return;
        }
        if (kind === 'worldbook') {
            const src = await fetchStSettings();
            const names = asArray(src.world_names).map(w => String(w || '').trim()).filter(Boolean);
            const name = targetName || names[0];
            if (!name) { log('未找到目标世界书'); return; }
            const r = await mainFetch('/api/worldinfo/get', { method: 'POST', headers, body: JSON.stringify({ name }) });
            if (!r.ok) throw new Error('读取世界书失败');
            const wb = await r.json();
            wb.name = wb.name || name;
            await pushBytesToVault('worldbook', name + '.json', enc.encode(JSON.stringify(wb, null, 2)));
            setStatus('已推送世界书：' + name, 'stv-ok');
            log('单项推送世界书成功：' + name);
            return;
        }
        if (kind === 'preset') {
            const src = await fetchStSettings();
            const presetsArr = asArray(src.openai_settings);
            const rawNames = src.openai_setting_names;
            const curPreset = targetName || (mainDoc.querySelector('#settings_preset_openai')?.selectedOptions?.[0]?.textContent?.trim() || '');
            let foundObj = null;
            let foundName = curPreset;
            if (Array.isArray(rawNames)) {
                const idx = rawNames.findIndex(n => String(n || '').trim() === curPreset);
                const pickIdx = idx >= 0 ? idx : 0;
                foundObj = asObject(presetsArr[pickIdx]);
                foundName = String(rawNames[pickIdx] || foundName || 'preset');
            } else if (rawNames && typeof rawNames === 'object') {
                const entry = Object.entries(rawNames).find(([k]) => k === curPreset) || Object.entries(rawNames)[0];
                if (entry) {
                    foundName = entry[0];
                    foundObj = asObject(presetsArr[entry[1]]);
                }
            }
            if (!foundObj) { log('未找到目标预设'); return; }
            await pushBytesToVault('preset', foundName + '.json', enc.encode(JSON.stringify(foundObj, null, 2)));
            setStatus('已推送预设：' + foundName, 'stv-ok');
            log('单项推送预设成功：' + foundName);
        }
    }

    function startPolling() {
        if (state.pollTimer) clearInterval(state.pollTimer);
        state.pollTimer = setInterval(pollOnce, 6000);
    }

    function injectStyles() {
        if (mainDoc.getElementById('stv_bridge_inline_style')) return;
        const st = mainDoc.createElement('style');
        st.id = 'stv_bridge_inline_style';
        st.textContent = `
.stv-card{display:flex;flex-direction:column;gap:10px;padding:8px 2px}
.stv-sec{display:flex;flex-direction:column;gap:8px;padding:10px 12px;border-radius:10px;background:rgba(128,128,128,.08);border:1px solid rgba(128,128,128,.16)}
.stv-sec-head{display:flex;align-items:center;justify-content:space-between;gap:8px}
.stv-sec-title{font-size:12px;font-weight:600;opacity:.75;letter-spacing:.02em}
.stv-bridge-status{font-size:12px;padding:6px 10px;border-radius:8px;background:rgba(128,128,128,.1);opacity:.92;line-height:1.4}
.stv-bridge-status.stv-ok{background:rgba(22,163,74,.14);color:#22c55e}
.stv-bridge-status.stv-err{background:rgba(220,38,38,.14);color:#f87171}
.stv-grid2{display:grid;grid-template-columns:1fr 1fr;gap:6px}
.stv-grid3{display:grid;grid-template-columns:1fr 1fr 1fr;gap:6px}
.stv-btn{display:flex;align-items:center;justify-content:center;padding:7px 10px;border-radius:8px;font-size:12px;font-weight:500;cursor:pointer;background:rgba(128,128,128,.14);border:1px solid rgba(128,128,128,.18);transition:opacity .15s;user-select:none;text-align:center}
.stv-btn:hover{opacity:.85}
.stv-btn-primary{background:var(--SmartThemeQuoteColor, #3b82f6);color:#fff;border-color:transparent;font-weight:600}
.stv-toggle-row{display:flex;align-items:center;justify-content:space-between;font-size:12px;opacity:.88;padding-top:2px}
.stv-single-row{display:flex;gap:6px;align-items:center}
.stv-select{flex:1;min-width:0;padding:6px 8px;border-radius:8px;font-size:12px;background:rgba(0,0,0,.18);border:1px solid rgba(128,128,128,.22);color:inherit}
.stv-details summary{font-size:12px;opacity:.72;cursor:pointer;padding:2px 0;user-select:none}
.stv-bridge-log{font-size:11px;line-height:1.45;max-height:110px;overflow-y:auto;white-space:pre-wrap;word-break:break-all;padding:8px;border-radius:8px;background:rgba(0,0,0,.16);opacity:.8}
`;
        (mainDoc.head || mainDoc.documentElement).appendChild(st);
    }

    function buildHtml() {
        const autoOn = localStorage.getItem('stv_bridge_auto_connect') === '1';
        return `
<div class="inline-drawer">
  <div class="inline-drawer-toggle inline-drawer-header">
    <b>酒馆匣互联 <span style="font-weight:normal;opacity:.55;font-size:11px;">v1.15.1</span></b>
    <div class="inline-drawer-icon fa-solid fa-circle-chevron-down down"></div>
  </div>
  <div class="inline-drawer-content">
    <div class="stv-card">
      <div class="stv-sec">
        <div class="stv-sec-head">
          <span class="stv-sec-title">连接状态</span>
          <div class="stv-btn stv-btn-primary" id="stv_btn_connect" style="padding:4px 12px;">连接</div>
        </div>
        <div class="stv-bridge-status" id="stv_bridge_status">未连接 · 请先在酒馆匣开启互联</div>
        <label class="stv-toggle-row">
          <span>打开酒馆自动连接</span>
          <input type="checkbox" id="stv_chk_autoconnect" ${autoOn ? 'checked' : ''} />
        </label>
      </div>

      <div class="stv-sec">
        <div class="stv-sec-head">
          <span class="stv-sec-title">单项推送到酒馆匣</span>
        </div>
        <div class="stv-grid3">
          <div class="stv-btn" id="stv_btn_cur_theme">当前美化</div>
          <div class="stv-btn" id="stv_btn_cur_char">当前角色</div>
          <div class="stv-btn" id="stv_btn_cur_preset">当前预设</div>
        </div>
        <div class="stv-single-row">
          <select class="stv-select" id="stv_sel_kind" style="flex:0 0 76px;">
            <option value="theme">美化</option>
            <option value="character">角色卡</option>
            <option value="worldbook">世界书</option>
            <option value="preset">预设</option>
          </select>
          <select class="stv-select" id="stv_sel_item"><option value="">点击加载...</option></select>
          <div class="stv-btn stv-btn-primary" id="stv_btn_push_one" style="flex:0 0 auto;padding:6px 11px;">推送</div>
        </div>
      </div>

      <div class="stv-sec">
        <div class="stv-grid2">
          <div class="stv-btn" id="stv_btn_receive">接收待装入</div>
          <div class="stv-btn" id="stv_btn_sync">同步清单与标签</div>
        </div>
        <details class="stv-details">
          <summary>全量批量推送</summary>
          <div class="stv-grid2" style="margin-top:8px;">
            <div class="stv-btn" id="stv_btn_push_themes">全部美化</div>
            <div class="stv-btn" id="stv_btn_push_chars">全部角色卡</div>
            <div class="stv-btn" id="stv_btn_push_wbs">全部世界书</div>
            <div class="stv-btn" id="stv_btn_push_presets">全部预设</div>
          </div>
        </details>
      </div>

      <div class="stv-bridge-log" id="stv_bridge_log"></div>
    </div>
  </div>
</div>`;
    }

    async function runExclusive(task) {
        if (state.busy) {
            log('当前有同步任务正在进行，请稍候...');
            return;
        }
        state.busy = true;
        try {
            await task();
        } finally {
            state.busy = false;
        }
    }

    async function populateSinglePicker(root) {
        const selKind = root.querySelector('#stv_sel_kind');
        const selItem = root.querySelector('#stv_sel_item');
        if (!selKind || !selItem) return;
        const kind = selKind.value;
        selItem.innerHTML = '<option value="">加载中...</option>';
        try {
            let names = [];
            if (kind === 'character') {
                const chars = await fetchStCharacters();
                names = chars.filter(c => c && c.avatar).map(c => c.name || c.avatar);
            } else {
                const src = await fetchStSettings();
                if (kind === 'theme') {
                    names = asArray(src.themes).map(t => { const o = asObject(t); return o && (o.name || o.theme_name); }).filter(Boolean);
                } else if (kind === 'worldbook') {
                    names = asArray(src.world_names).map(w => String(w || '').trim()).filter(Boolean);
                } else if (kind === 'preset') {
                    names = extractPresetNames(src);
                }
            }
            if (!names.length) {
                selItem.innerHTML = '<option value="">（空）</option>';
                return;
            }
            selItem.innerHTML = names.map(n => `<option value="${String(n).replace(/"/g, '&quot;')}">${n}</option>`).join('');
        } catch (_) {
            selItem.innerHTML = '<option value="">加载失败</option>';
        }
    }

    function bindPanelButtons(root) {
        const byId = (id) => root.querySelector('#' + id);
        const btnConn = byId('stv_btn_connect');
        if (btnConn) btnConn.addEventListener('click', () => runExclusive(async () => {
            setStatus('正在连接酒馆匣...', '');
            try { await connect(); } catch (e) { log(e.message); }
        }));
        const chkAuto = byId('stv_chk_autoconnect');
        if (chkAuto) chkAuto.addEventListener('change', () => {
            localStorage.setItem('stv_bridge_auto_connect', chkAuto.checked ? '1' : '0');
            if (chkAuto.checked && !state.connected) {
                runExclusive(async () => { try { await connect(); } catch (_) {} });
            }
        });
        const selKind = byId('stv_sel_kind');
        const selItem = byId('stv_sel_item');
        if (selKind) selKind.addEventListener('change', () => populateSinglePicker(root));
        if (selItem) selItem.addEventListener('focus', () => {
            if (selItem.options.length <= 1 && (!selItem.value)) populateSinglePicker(root);
        });
        const btnPushOne = byId('stv_btn_push_one');
        if (btnPushOne) btnPushOne.addEventListener('click', () => runExclusive(async () => {
            const k = selKind ? selKind.value : 'theme';
            const v = selItem ? selItem.value : '';
            if (!v) { await populateSinglePicker(root); return; }
            await pushSingleItem(k, v);
        }));
        const btnCurTheme = byId('stv_btn_cur_theme');
        if (btnCurTheme) btnCurTheme.addEventListener('click', () => runExclusive(() => pushSingleItem('theme', '')));
        const btnCurChar = byId('stv_btn_cur_char');
        if (btnCurChar) btnCurChar.addEventListener('click', () => runExclusive(() => pushSingleItem('character', '')));
        const btnCurPreset = byId('stv_btn_cur_preset');
        if (btnCurPreset) btnCurPreset.addEventListener('click', () => runExclusive(() => pushSingleItem('preset', '')));

        const btnSync = byId('stv_btn_sync');
        if (btnSync) btnSync.addEventListener('click', () => runExclusive(async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await syncInventory();
            await syncThemeTagsBidirectional(false);
        }));
        const btnThemes = byId('stv_btn_push_themes');
        if (btnThemes) btnThemes.addEventListener('click', () => runExclusive(async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await handlePull('theme');
        }));
        const btnChars = byId('stv_btn_push_chars');
        if (btnChars) btnChars.addEventListener('click', () => runExclusive(async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await handlePull('character');
        }));
        const btnWbs = byId('stv_btn_push_wbs');
        if (btnWbs) btnWbs.addEventListener('click', () => runExclusive(async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await handlePull('worldbook');
        }));
        const btnPresets = byId('stv_btn_push_presets');
        if (btnPresets) btnPresets.addEventListener('click', () => runExclusive(async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await handlePull('preset');
        }));
        const btnRecv = byId('stv_btn_receive');
        if (btnRecv) btnRecv.addEventListener('click', () => runExclusive(async () => {
            if (!state.connected) { try { await connect(); } catch (e) { log(e.message); return; } }
            await processOutbox();
        }));
    }

    function mount() {
        injectStyles();
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
            if (mainDoc.getElementById('stv_drawer_wrap')) {
                clearInterval(timer);
            }
        }, 800);

        if (localStorage.getItem('stv_bridge_auto_connect') === '1') {
            setTimeout(() => {
                if (!state.connected && !state.busy) {
                    runExclusive(async () => { try { await connect(); } catch (_) {} });
                }
            }, 1800);
        }

        mainDoc.addEventListener('visibilitychange', () => {
            if (mainDoc.visibilityState === 'visible' && state.connected && !state.busy) {
                pollOnce();
            }
        });

        let tagDebounce = null;
        mainDoc.addEventListener('themeManager:tagsChanged', () => {
            if (!state.connected || state.busy) return;
            if (tagDebounce) clearTimeout(tagDebounce);
            tagDebounce = setTimeout(() => {
                tagDebounce = null;
                if (state.connected && !state.busy) {
                    syncThemeTagsBidirectional(true);
                }
            }, 1200);
        });
    }

    if (mainDoc.readyState === 'loading') {
        mainDoc.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
