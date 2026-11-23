package com.stvault.app.core

enum class DiffKind { ADDED, REMOVED, MODIFIED }

data class DiffEntry(
    val kind: DiffKind,
    val section: String,
    val title: String,
    val summary: String,
    val oldText: String = "",
    val newText: String = ""
)

data class DiffReport(
    val addedCount: Int,
    val modifiedCount: Int,
    val removedCount: Int,
    val entries: List<DiffEntry>
)

object VersionDiffEngine {

    fun diffPresets(oldP: ParsedPreset, newP: ParsedPreset): DiffReport {
        val out = mutableListOf<DiffEntry>()

        // 1. Parameters
        compareOpt("参数", "Temperature", oldP.temperature?.toString(), newP.temperature?.toString(), out)
        compareOpt("参数", "Top P", oldP.topP?.toString(), newP.topP?.toString(), out)
        compareOpt("参数", "Max Tokens", oldP.maxTokens?.toString(), newP.maxTokens?.toString(), out)
        compareOpt("参数", "Max Context", oldP.maxContext?.toString(), newP.maxContext?.toString(), out)

        // 2. Prompts (match by identifier if non-blank, else name)
        fun promptKey(p: ParsedPresetPrompt): String = p.identifier.ifBlank { p.name }
        val oldMap = LinkedHashMap<String, ParsedPresetPrompt>()
        oldP.prompts.forEach { oldMap[promptKey(it)] = it }
        val newMap = LinkedHashMap<String, ParsedPresetPrompt>()
        newP.prompts.forEach { newMap[promptKey(it)] = it }

        for ((k, np) in newMap) {
            val op = oldMap[k]
            if (op == null) {
                out.add(
                    DiffEntry(
                        kind = DiffKind.ADDED,
                        section = "提示词",
                        title = np.name.ifBlank { k },
                        summary = "${if (np.enabled) "启用" else "关闭"} · ${np.content.length} 字",
                        newText = np.content
                    )
                )
            } else {
                val changes = mutableListOf<String>()
                if (op.enabled != np.enabled) {
                    changes.add("开关：${if (op.enabled) "开" else "关"} → ${if (np.enabled) "开" else "关"}")
                }
                if (op.role != np.role) {
                    changes.add("角色：${op.role} → ${np.role}")
                }
                if (op.content.trim() != np.content.trim()) {
                    val delta = np.content.length - op.content.length
                    val sign = if (delta >= 0) "+$delta" else "$delta"
                    changes.add("正文改动（$sign 字）")
                }
                if (changes.isNotEmpty()) {
                    out.add(
                        DiffEntry(
                            kind = DiffKind.MODIFIED,
                            section = "提示词",
                            title = np.name.ifBlank { k },
                            summary = changes.joinToString(" · "),
                            oldText = op.content,
                            newText = np.content
                        )
                    )
                }
            }
        }
        for ((k, op) in oldMap) {
            if (k !in newMap) {
                out.add(
                    DiffEntry(
                        kind = DiffKind.REMOVED,
                        section = "提示词",
                        title = op.name.ifBlank { k },
                        summary = "已移除（原 ${op.content.length} 字）",
                        oldText = op.content
                    )
                )
            }
        }

        // 3. Embedded Regex & Tavern Helper
        diffNamedList(
            section = "正则脚本",
            oldItems = oldP.embeddedRegexScripts.associate { it.name to "${it.findRegex}\n→\n${it.replaceString}" },
            newItems = newP.embeddedRegexScripts.associate { it.name to "${it.findRegex}\n→\n${it.replaceString}" },
            out = out
        )
        diffNamedList(
            section = "酒馆助手",
            oldItems = oldP.embeddedHelperScripts.associate { it.name to it.content },
            newItems = newP.embeddedHelperScripts.associate { it.name to it.content },
            out = out
        )

        return buildReport(out)
    }

    fun diffWorldBooks(oldW: ParsedWorldBook, newW: ParsedWorldBook): DiffReport {
        val out = mutableListOf<DiffEntry>()

        fun entryKey(e: ParsedWorldEntry, idx: Int): String {
            return e.title.trim().ifBlank {
                e.keys.joinToString(",").trim().ifBlank { "uid_${e.uid}_$idx" }
            }
        }

        val oldMap = LinkedHashMap<String, ParsedWorldEntry>()
        oldW.entries.forEachIndexed { idx, e -> oldMap[entryKey(e, idx)] = e }
        val newMap = LinkedHashMap<String, ParsedWorldEntry>()
        newW.entries.forEachIndexed { idx, e -> newMap[entryKey(e, idx)] = e }

        for ((k, ne) in newMap) {
            val oe = oldMap[k]
            if (oe == null) {
                val mode = when {
                    !ne.enabled -> "关闭"
                    ne.constant -> "蓝灯常驻"
                    else -> "绿灯触发"
                }
                out.add(
                    DiffEntry(
                        kind = DiffKind.ADDED,
                        section = "条目",
                        title = ne.title.ifBlank { k },
                        summary = "$mode · ${ne.content.length} 字",
                        newText = ne.content
                    )
                )
            } else {
                val changes = mutableListOf<String>()
                if (oe.enabled != ne.enabled || oe.constant != ne.constant) {
                    changes.add("状态变更")
                }
                if (oe.keys != ne.keys) {
                    changes.add("关键词变更")
                }
                if (oe.content.trim() != ne.content.trim()) {
                    val delta = ne.content.length - oe.content.length
                    val sign = if (delta >= 0) "+$delta" else "$delta"
                    changes.add("内容改动（$sign 字）")
                }
                if (changes.isNotEmpty()) {
                    out.add(
                        DiffEntry(
                            kind = DiffKind.MODIFIED,
                            section = "条目",
                            title = ne.title.ifBlank { k },
                            summary = changes.joinToString(" · "),
                            oldText = oe.content,
                            newText = ne.content
                        )
                    )
                }
            }
        }
        for ((k, oe) in oldMap) {
            if (k !in newMap) {
                out.add(
                    DiffEntry(
                        kind = DiffKind.REMOVED,
                        section = "条目",
                        title = oe.title.ifBlank { k },
                        summary = "已移除（原 ${oe.content.length} 字）",
                        oldText = oe.content
                    )
                )
            }
        }

        return buildReport(out)
    }

    fun diffCharacters(oldC: ParsedCharacterCard, newC: ParsedCharacterCard): DiffReport {
        val out = mutableListOf<DiffEntry>()

        compareTextField("基础设定", "角色名", oldC.name, newC.name, out)
        compareTextField("基础设定", "角色设定 (Description)", oldC.description, newC.description, out)
        compareTextField("基础设定", "性格 (Personality)", oldC.personality, newC.personality, out)
        compareTextField("基础设定", "场景 (Scenario)", oldC.scenario, newC.scenario, out)
        compareTextField("基础设定", "系统提示词", oldC.systemPrompt, newC.systemPrompt, out)
        compareTextField("基础设定", "作者备注", oldC.creatorNotes, newC.creatorNotes, out)
        compareTextField("开场白", "主开场白", oldC.firstMes, newC.firstMes, out)

        val maxAlt = maxOf(oldC.alternateGreetings.size, newC.alternateGreetings.size)
        for (i in 0 until maxAlt) {
            val oAlt = oldC.alternateGreetings.getOrNull(i)
            val nAlt = newC.alternateGreetings.getOrNull(i)
            when {
                oAlt == null && nAlt != null -> out.add(
                    DiffEntry(DiffKind.ADDED, "备用开场白", "开场白 #${i + 2}", "新增（${nAlt.length} 字）", newText = nAlt)
                )
                oAlt != null && nAlt == null -> out.add(
                    DiffEntry(DiffKind.REMOVED, "备用开场白", "开场白 #${i + 2}", "已移除（原 ${oAlt.length} 字）", oldText = oAlt)
                )
                oAlt != null && nAlt != null && oAlt.trim() != nAlt.trim() -> {
                    val delta = nAlt.length - oAlt.length
                    val sign = if (delta >= 0) "+$delta" else "$delta"
                    out.add(
                        DiffEntry(DiffKind.MODIFIED, "备用开场白", "开场白 #${i + 2}", "内容改动（$sign 字）", oAlt, nAlt)
                    )
                }
            }
        }

        val oldWbCount = oldC.embeddedWorldBook?.entries?.size ?: 0
        val newWbCount = newC.embeddedWorldBook?.entries?.size ?: 0
        if (oldWbCount != newWbCount) {
            out.add(
                DiffEntry(
                    kind = DiffKind.MODIFIED,
                    section = "内置世界书",
                    title = "世界书条目数",
                    summary = "$oldWbCount 条 → $newWbCount 条"
                )
            )
        }

        diffNamedList(
            section = "内置正则",
            oldItems = oldC.embeddedRegexScripts.associate { it.name to "${it.findRegex}\n→\n${it.replaceString}" },
            newItems = newC.embeddedRegexScripts.associate { it.name to "${it.findRegex}\n→\n${it.replaceString}" },
            out = out
        )

        return buildReport(out)
    }

    fun diffRawTexts(oldRaw: String, newRaw: String, module: String): String {
        val oTrim = oldRaw.trim()
        val nTrim = newRaw.trim()
        if (oTrim == nTrim) return "代码完全一致，未发生变化"

        try {
            when (module) {
                "themes" -> {
                    if (oTrim.startsWith("{") && nTrim.startsWith("{")) {
                        val oObj = org.json.JSONObject(oTrim)
                        val nObj = org.json.JSONObject(nTrim)
                        val changedKeys = mutableListOf<String>()
                        val allKeys = (oObj.keys().asSequence().toSet() + nObj.keys().asSequence().toSet()) - setOf("_xia")
                        var cssDelta = 0
                        for (k in allKeys) {
                            val ov = oObj.opt(k)?.toString().orEmpty().trim()
                            val nv = nObj.opt(k)?.toString().orEmpty().trim()
                            if (ov != nv) {
                                if (k == "custom_css") {
                                    cssDelta = nv.length - ov.length
                                } else {
                                    changedKeys.add("$k: ${ov.take(24)} → ${nv.take(24)}")
                                }
                            }
                        }
                        val parts = mutableListOf<String>()
                        if (cssDelta != 0) {
                            val sign = if (cssDelta > 0) "+$cssDelta" else "$cssDelta"
                            parts.add("custom_css 改动 ($sign 字符)")
                        } else if (oObj.optString("custom_css").trim() != nObj.optString("custom_css").trim()) {
                            parts.add("custom_css 内容修改")
                        }
                        if (changedKeys.isNotEmpty()) {
                            parts.add("配色/属性改动 ${changedKeys.size} 项 (${changedKeys.take(4).joinToString(" · ")})")
                        }
                        if (parts.isNotEmpty()) return parts.joinToString("\n")
                    }
                }
                "presets" -> {
                    val op = PresetParser.parse(oTrim, "old")
                    val np = PresetParser.parse(nTrim, "new")
                    if (op != null && np != null) {
                        val rep = diffPresets(op, np)
                        if (rep.entries.isNotEmpty()) {
                            return formatReportBrief(rep)
                        }
                    }
                }
                "worldbooks" -> {
                    val ow = WorldBookParser.parse(oTrim, "old")
                    val nw = WorldBookParser.parse(nTrim, "new")
                    if (ow != null && nw != null) {
                        val rep = diffWorldBooks(ow, nw)
                        if (rep.entries.isNotEmpty()) {
                            return formatReportBrief(rep)
                        }
                    }
                }
                "characters" -> {
                    val oc = CharacterCardParser.parseJson(oTrim, "old")
                    val nc = CharacterCardParser.parseJson(nTrim, "new")
                    if (oc != null && nc != null) {
                        val rep = diffCharacters(oc, nc)
                        if (rep.entries.isNotEmpty()) {
                            return formatReportBrief(rep)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        val oLines = oTrim.lines()
        val nLines = nTrim.lines()
        val lineDelta = nLines.size - oLines.size
        val charDelta = nTrim.length - oTrim.length
        val lSign = if (lineDelta >= 0) "+$lineDelta" else "$lineDelta"
        val cSign = if (charDelta >= 0) "+$charDelta" else "$charDelta"
        return "代码行数 ${oLines.size} → ${nLines.size} ($lSign 行)，字符数 $cSign"
    }

    private fun formatReportBrief(rep: DiffReport): String {
        val head = "+${rep.addedCount} 新增 · ~${rep.modifiedCount} 修改 · -${rep.removedCount} 删除"
        val topItems = rep.entries.take(5).joinToString("\n") { e ->
            val sym = when (e.kind) {
                DiffKind.ADDED -> "+"
                DiffKind.MODIFIED -> "~"
                DiffKind.REMOVED -> "-"
            }
            "$sym [${e.section}] ${e.title}：${e.summary}"
        }
        val more = if (rep.entries.size > 5) "\n...等共 ${rep.entries.size} 处变更" else ""
        return "$head\n$topItems$more"
    }

    private fun compareOpt(section: String, title: String, oldVal: String?, newVal: String?, out: MutableList<DiffEntry>) {
        if (oldVal == newVal) return
        out.add(
            DiffEntry(
                kind = DiffKind.MODIFIED,
                section = section,
                title = title,
                summary = "${oldVal ?: "默认"} → ${newVal ?: "默认"}",
                oldText = oldVal.orEmpty(),
                newText = newVal.orEmpty()
            )
        )
    }

    private fun compareTextField(section: String, title: String, oldVal: String, newVal: String, out: MutableList<DiffEntry>) {
        val o = oldVal.trim()
        val n = newVal.trim()
        if (o == n) return
        when {
            o.isEmpty() -> out.add(DiffEntry(DiffKind.ADDED, section, title, "新增（${n.length} 字）", newText = n))
            n.isEmpty() -> out.add(DiffEntry(DiffKind.REMOVED, section, title, "已清空（原 ${o.length} 字）", oldText = o))
            else -> {
                val delta = n.length - o.length
                val sign = if (delta >= 0) "+$delta" else "$delta"
                out.add(DiffEntry(DiffKind.MODIFIED, section, title, "内容改动（$sign 字）", o, n))
            }
        }
    }

    private fun diffNamedList(
        section: String,
        oldItems: Map<String, String>,
        newItems: Map<String, String>,
        out: MutableList<DiffEntry>
    ) {
        for ((k, nv) in newItems) {
            val ov = oldItems[k]
            when {
                ov == null -> out.add(DiffEntry(DiffKind.ADDED, section, k, "新增", newText = nv))
                ov.trim() != nv.trim() -> out.add(DiffEntry(DiffKind.MODIFIED, section, k, "脚本内容已修改", ov, nv))
            }
        }
        for ((k, ov) in oldItems) {
            if (k !in newItems) {
                out.add(DiffEntry(DiffKind.REMOVED, section, k, "已移除", oldText = ov))
            }
        }
    }

    private fun buildReport(entries: List<DiffEntry>): DiffReport {
        var added = 0
        var mod = 0
        var rem = 0
        for (e in entries) {
            when (e.kind) {
                DiffKind.ADDED -> added++
                DiffKind.MODIFIED -> mod++
                DiffKind.REMOVED -> rem++
            }
        }
        return DiffReport(added, mod, rem, entries)
    }
}
