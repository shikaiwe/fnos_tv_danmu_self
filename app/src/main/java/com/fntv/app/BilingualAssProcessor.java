package com.fntv.app;

import android.util.Log;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 双语 ASS 字幕合并处理器
 * 将时间高度重叠的多条 Dialogue（如日文+中文翻译行）合并成一行，用 \N 分隔，
 * 使 mpv (libass) 同时显示两条
 *
 * 额外功能：
 * - 自动检测字幕编码（UTF-8/UTF-16/GB18030 等），防止乱码导致字幕无法渲染
 * - 完整保留 [Fonts]/[Graphics] 节的内嵌字体数据（供 libass 原样加载）
 * - Style 字体名仅在本文件未内嵌该字体时改写为打包字体族，跨设备显示一致
 */
public class BilingualAssProcessor {

    private static final String TAG = "AssProcessor";

    /** 打包字体族名（assets/fonts/NotoSansSC-Regular.otf，与 mpv sub-font 保持一致） */
    public static final String FALLBACK_FONT_FAMILY = "Noto Sans SC";

    /** 合并后的 ASS 内容 */
    public static class ProcessedAss {
        public final String content;
        public final int mergedCount;

        public ProcessedAss(String content, int mergedCount) {
            this.content = content;
            this.mergedCount = mergedCount;
        }
    }

    /** 字幕文件编码候选顺序（UTF-8 优先；ISO-8859-1 永不解错，作兜底） */
    private static final Charset[] SUBTITLE_ENCODINGS = {
            StandardCharsets.UTF_8,
            Charset.forName("GB18030"),
            Charset.forName("Shift_JIS"),
            StandardCharsets.ISO_8859_1
    };

    // ASS 对话框中的控制标记（移除不影响显示的）
    private static final Pattern REMOVE_CONTROL_TAGS = Pattern.compile("\\\\cf\\d|\\\\[1-8a-f]", Pattern.CASE_INSENSITIVE);

    /**
     * 从字节数组解析字幕内容，自动检测编码（UTF-8 优先）
     * @param bytes   字幕文件字节
     * @return 解码后的文本
     */
    public static String decodeAssBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        // 显式 BOM 直接按对应编码解码
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        // 无 BOM 的 UTF-16：ASCII 文本区间会出现大量 0x00 字节
        if (looksLikeUtf16(bytes)) {
            return new String(bytes, StandardCharsets.UTF_16LE);
        }
        // 依次尝试：解码出现 U+FFFD 说明编码不对（ISO-8859-1 永远不会，作最终兜底）
        for (Charset cs : SUBTITLE_ENCODINGS) {
            String text = new String(bytes, cs);
            if (!text.contains("\uFFFD")) {
                return text;
            }
        }
        Log.w(TAG, "所有编码均含非法序列，按 ISO-8859-1 兜底解码");
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /** 无 BOM 时判断是否为 UTF-16：检查头部样本字节中 0x00 占比与位置 */
    private static boolean looksLikeUtf16(byte[] bytes) {
        int sample = Math.min(bytes.length, 512);
        if (sample < 4 || bytes.length % 2 != 0) return false;
        int zeros = 0;
        for (int i = 0; i < sample; i++) {
            if (bytes[i] == 0) zeros++;
        }
        // 纯 ASCII/UTF-8 文本不会出现这么多 0x00
        return zeros * 10 >= sample;
    }

    /**
     * 将文本类字幕（SRT/VTT/SMI/MicroDVD 等，非 ASS）统一重编码为 UTF-8 字节。
     * mpv/ffmpeg 对非 UTF-8 文本（常见 GBK 编码 SRT）不做编码转换，直接投喂会整屏乱码。
     * 判定为文本格式时返回归一后的 UTF-8 字节；二进制格式（PGS .sup、VobSub 等）返回 null 原样透传。
     *
     * @param bytes    原始字幕字节
     * @param fileName 文件名（用于扩展名判断）
     * @return UTF-8 归一后的字节；非文本格式返回 null
     */
    public static byte[] normalizeNonAssTextBytes(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) return null;
        String lower = fileName != null ? fileName.toLowerCase() : "";

        // 二进制字幕格式（位图）：扩展名 + 内容双重确认，必须原样透传给 mpv。
        // ".sub" 既可能是 MicroDVD 文本也可能是 VobSub 位图，由内容（NUL 占比）区分
        boolean binaryByExt = lower.endsWith(".sup") || lower.endsWith(".idx");
        if (binaryByExt || looksBinary(bytes)) return null;

        boolean textByExt = lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".txt")
                || lower.endsWith(".smi") || lower.endsWith(".sami") || lower.endsWith(".sub")
                || lower.endsWith(".sbv") || lower.endsWith(".ssa") || lower.endsWith(".ass");
        // 无已知扩展名：样本字节均为可打印/常见文本字节才当文本处理
        if (!textByExt && !looksText(bytes)) return null;

        String decoded = decodeAssBytes(bytes);
        byte[] utf8 = decoded.getBytes(StandardCharsets.UTF_8);
        // 已是等效 UTF-8 则返回原字节，避免无谓的磁盘重写
        return java.util.Arrays.equals(utf8, stripUtf8Bom(bytes)) ? bytes : utf8;
    }

    /** 去掉 UTF-8 BOM 后的字节（用于比较是否已为 UTF-8） */
    private static byte[] stripUtf8Bom(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return java.util.Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        return bytes;
    }

    /** 二进制格式粗判：头部样本含大量控制字节或 0x00（位图字幕特征） */
    private static boolean looksBinary(byte[] bytes) {
        int sample = Math.min(bytes.length, 512);
        int suspicious = 0;
        for (int i = 0; i < sample; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0 || (b < 0x09) || (b > 0x0D && b < 0x20)) suspicious++;
        }
        return suspicious * 5 >= sample;
    }

    /** 未知扩展名时判断是否为文本：样本字节均为可打印字符/常见控制符 */
    private static boolean looksText(byte[] bytes) {
        int sample = Math.min(bytes.length, 512);
        if (sample == 0) return false;
        for (int i = 0; i < sample; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0 || (b < 0x09) || (b > 0x0D && b < 0x20)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从字符串解析 ASS 内容（假设输入已是正确解码的 UTF-8 文本）
     * @param rawAss 原始 ASS 文本
     * @return 处理后的 ASS
     */
    public static ProcessedAss process(String rawAss) {
        if (rawAss == null || rawAss.isEmpty()) {
            return new ProcessedAss(rawAss, 0);
        }

        List<String> headerLines = new ArrayList<>();
        List<AssDialogue> dialogues = new ArrayList<>();
        List<String> formatLine = new ArrayList<>();
        // 文件内嵌字体的名字提示（[Fonts] 节的 "fontname:" 行）
        List<String> embeddedFontHints = new ArrayList<>();

        BufferedReader reader = new BufferedReader(new StringReader(rawAss));
        try {
            String line;
            String section = "";
            boolean inEvents = false;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    section = trimmed;
                }
                if (inEvents && line.startsWith("Dialogue:")) {
                    AssDialogue d = parseDialogue(line);
                    if (d != null) dialogues.add(d);
                } else if (inEvents && line.startsWith("Format:")) {
                    formatLine.add(line);
                } else if (line.startsWith("[Events]")) {
                    inEvents = true;
                    headerLines.add(line);
                } else if (isFontDataSection(section)) {
                    // [Fonts]/[Graphics] 节是 UUE 编码的内嵌字体数据，必须逐行原样保留，
                    // 其中会有以 "!" ";" "[" 开头的行，都不能当注释/节头处理
                    if (trimmed.toLowerCase().startsWith("fontname:")) {
                        embeddedFontHints.add(trimmed.substring("fontname:".length()).trim());
                    }
                    headerLines.add(line);
                } else if (line.startsWith("[Script Info]") || line.startsWith("[V4+ Styles]")
                        || line.startsWith("[V4 Styles]") || line.startsWith("[Aegisub")
                        || line.startsWith("[Fonts]") || line.startsWith("[Graphics]")
                        || line.startsWith("Style:") || line.startsWith("Comment:")
                        || line.startsWith("PlayResX") || line.startsWith("PlayResY")
                        || line.startsWith("ScaledBorderAndShadow") || line.startsWith("Video")
                        || line.startsWith("Audio") || line.startsWith("Timer")) {
                    headerLines.add(line);
                } else if (!trimmed.isEmpty() && !line.startsWith(";")) {
                    // 非空非注释行
                    if (line.startsWith("[")) {
                        headerLines.add(line);
                    } else if (!inEvents) {
                        headerLines.add(line);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "读取 ASS 失败", e);
            return new ProcessedAss(rawAss, 0);
        }

        if (dialogues.isEmpty()) {
            return new ProcessedAss(rawAss, 0);
        }

        // 按开始时间排序
        Collections.sort(dialogues, new Comparator<AssDialogue>() {
            @Override public int compare(AssDialogue a, AssDialogue b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });

        // 严格时间重叠合并：同一时间段的多条 Dialogue（如日中双语）合并成一行。
        // 以组内第一条的原始时间窗为锚点、不随合并扩展，避免相邻台词只擦边重叠时
        // 被链式扩散，把整份字幕并成一行覆盖全片。
        // 双语翻译行通常与原文行几乎同起同止，要求重叠时长 ≥ 较短一条的一半才合并。
        List<AssDialogue> merged = new ArrayList<>();
        boolean[] used = new boolean[dialogues.size()];
        int mergeCount = 0;

        for (int i = 0; i < dialogues.size(); i++) {
            if (used[i]) continue;
            AssDialogue anchor = dialogues.get(i);
            List<AssDialogue> group = new ArrayList<>();
            group.add(anchor);
            used[i] = true;
            long groupEnd = anchor.endMs;

            // 往后找所有与锚点严格重叠且重叠率达到阈值的 Dialogue。
            // 仅合并同 Layer 且同 Style 的行：特效字幕（\pos/\move 等）放在独立 Layer/Style，
            // 与台词时间经常重叠，跨 Layer/Style 合并会把定位标签拼进台词行导致渲染错乱。
            for (int j = i + 1; j < dialogues.size(); j++) {
                if (used[j]) continue;
                AssDialogue next = dialogues.get(j);
                if (next.startMs >= anchor.endMs) break; // 按开始时间排序，后面不会再与锚点重叠
                if (next.layer != anchor.layer || !anchor.style.equals(next.style)) continue;
                long overlap = Math.min(anchor.endMs, next.endMs) - Math.max(anchor.startMs, next.startMs);
                if (overlap <= 0) continue;
                long shorter = Math.min(anchor.endMs - anchor.startMs, next.endMs - next.startMs);
                if (shorter > 0 && overlap * 2 < shorter) continue;
                group.add(next);
                used[j] = true;
                groupEnd = Math.max(groupEnd, next.endMs);
            }

            if (group.size() > 1) {
                // 多条合并：用 \N 分隔
                StringBuilder mergedText = new StringBuilder();
                for (AssDialogue d : group) {
                    if (mergedText.length() > 0) mergedText.append("\\N");
                    mergedText.append(d.text);
                }
                merged.add(new AssDialogue(anchor.startMs, groupEnd,
                        anchor.style, mergedText.toString(),
                        anchor.marginL, anchor.marginR, anchor.marginV, anchor.effect, anchor.layer));
                mergeCount += group.size();
            } else {
                merged.add(anchor);
            }
        }

        // 重新按时间排序
        Collections.sort(merged, new Comparator<AssDialogue>() {
            @Override public int compare(AssDialogue a, AssDialogue b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });

        // 重新组装 ASS。
        // Style 字体名只在"未被本文件内嵌"时改写为内置字体族：
        // 内嵌字体依赖原始字体名精确匹配，不能动；未内嵌的字体在设备上不存在，
        // libass 不会为未知字体族做任意回退，必须指向打包的 Noto Sans SC。
        StringBuilder sb = new StringBuilder();
        for (String h : headerLines) {
            sb.append(h.startsWith("Style:")
                    ? rewriteStyleFontIfMissing(h, embeddedFontHints) : h).append("\n");
        }
        if (!formatLine.isEmpty()) {
            for (String f : formatLine) {
                sb.append(f).append("\n");
            }
        }
        // 没找到 Format 行，加默认
        if (formatLine.isEmpty()) {
            sb.append("Format: Layer, Start, End, Style, Actor, MarginL, MarginR, MarginV, Effect, Text\n");
        }
        for (AssDialogue d : merged) {
            sb.append(d.toDialogueLine()).append("\n");
        }

        return new ProcessedAss(sb.toString(), mergeCount);
    }

    /** 是否为内嵌字体/图片数据节（UUE 编码，逐行原样保留） */
    private static boolean isFontDataSection(String section) {
        return section.startsWith("[Fonts]") || section.startsWith("[Graphics]")
                || section.startsWith("[Embedded Fonts]");
    }

    /**
     * Style 行字体名改写：字体被本文件内嵌（名字提示模糊匹配）时保留原名，
     * 否则改写为内置回退字体族，保证 libass 一定解析得到
     */
    private static String rewriteStyleFontIfMissing(String styleLine, List<String> embeddedHints) {
        // Style: Name,Fontname,Fontsize,... 字体名是第2个字段（第1、2个逗号之间）
        int firstComma = styleLine.indexOf(',');
        int secondComma = styleLine.indexOf(',', firstComma + 1);
        if (firstComma < 0 || secondComma < 0) return styleLine;
        String fontName = styleLine.substring(firstComma + 1, secondComma).trim();
        if (isCoveredByEmbeddedFont(fontName, embeddedHints)) return styleLine;
        return styleLine.substring(0, firstComma + 1) + FALLBACK_FONT_FAMILY
                + styleLine.substring(secondComma);
    }

    /** 字体名是否被文件内嵌字体覆盖（大小写不敏感的双向包含匹配） */
    private static boolean isCoveredByEmbeddedFont(String fontName, List<String> embeddedHints) {
        if (fontName == null || fontName.isEmpty()) return false;
        if (embeddedHints.isEmpty()) return false;
        String lower = fontName.toLowerCase();
        for (String hint : embeddedHints) {
            String h = hint.toLowerCase();
            if (h.contains(lower) || lower.contains(h)) return true;
        }
        return false;
    }

    /** 解析 ASS Dialogue 行 */
    private static AssDialogue parseDialogue(String line) {
        // Dialogue: Layer,Start,End,Style,Actor,MarginL,MarginR,MarginV,Effect,Text
        if (!line.startsWith("Dialogue:")) return null;
        String body = line.substring("Dialogue:".length()).trim();
        // ASS 格式严格用逗号分隔前9个字段，剩余是文本
        int commaCount = 0;
        int splitPos = -1;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == ',') {
                commaCount++;
                if (commaCount == 9) {
                    splitPos = i;
                    break;
                }
            }
        }
        if (splitPos < 0) return null;

        String[] parts = new String[9];
        int start = 0;
        int idx = 0;
        for (int i = 0; i < splitPos && idx < 9; i++) {
            if (body.charAt(i) == ',') {
                parts[idx++] = body.substring(start, i);
                start = i + 1;
            }
        }
        // 补全剩余逗号分隔的前9个字段
        while (idx < 9 && start < splitPos) {
            int nextComma = body.indexOf(',', start);
            if (nextComma < 0 || nextComma > splitPos) {
                parts[idx++] = body.substring(start, splitPos);
                break;
            }
            parts[idx++] = body.substring(start, nextComma);
            start = nextComma + 1;
        }

        String text = body.substring(splitPos + 1);
        try {
            int layer = parseIntSafe(parts[0]);
            long startMs = parseAssTime(parts[1]);
            long endMs = parseAssTime(parts[2]);
            // 空字段（如空 Effect）在上方切分后为 null，须回退为空串，避免输出字面量 "null"
            String style = parts.length > 3 && parts[3] != null ? parts[3] : "";
            int marginL = parts.length > 5 ? parseIntSafe(parts[5]) : 0;
            int marginR = parts.length > 6 ? parseIntSafe(parts[6]) : 0;
            int marginV = parts.length > 7 ? parseIntSafe(parts[7]) : 0;
            String effect = parts.length > 8 && parts[8] != null ? parts[8] : "";
            return new AssDialogue(startMs, endMs, style, text, marginL, marginR, marginV, effect, layer);
        } catch (Exception e) {
            return null;
        }
    }

    /** ASS 时间格式 0:00:01.23 → 毫秒 */
    private static long parseAssTime(String time) {
        // 格式: H:MM:SS.cc (centiseconds) 或 H:MM:SS.mmm
        try {
            String[] parts = time.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            String[] secParts = parts[2].split("\\.");
            int s = Integer.parseInt(secParts[0]);
            int ms = secParts.length > 1 ? Integer.parseInt(secParts[1]) * (secParts[1].length() == 2 ? 10 : 1) : 0;
            return h * 3600000L + m * 60000L + s * 1000L + ms;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    /** ASS 对话行 */
    private static class AssDialogue {
        long startMs, endMs;
        String style, text, effect;
        int marginL, marginR, marginV;
        int layer;

        AssDialogue(long startMs, long endMs, String style, String text,
                    int marginL, int marginR, int marginV, String effect, int layer) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.style = style;
            this.text = text;
            this.marginL = marginL;
            this.marginR = marginR;
            this.marginV = marginV;
            this.effect = effect;
            this.layer = layer;
        }

        String toDialogueLine() {
            return String.format("Dialogue: %d,%s,%s,%s,,%d,%d,%d,%s,%s", layer,
                    formatAssTime(startMs), formatAssTime(endMs), style,
                    marginL, marginR, marginV, effect, text);
        }

        private static String formatAssTime(long ms) {
            long totalSec = ms / 1000;
            int h = (int) (totalSec / 3600);
            int m = (int) ((totalSec % 3600) / 60);
            int s = (int) (totalSec % 60);
            int cs = (int) ((ms % 1000) / 10);
            return String.format("%d:%02d:%02d.%02d", h, m, s, cs);
        }
    }
}
