package ru.lumorrix.updater;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Манифест сборки (manifest.txt на VPS). Построчный формат, чтобы не тащить JSON-библиотеку:
 * <pre>
 * format=1
 * version=2026-10-29_1530
 * files=files/
 * sync-dir=mods
 * F	&lt;sha1&gt;	&lt;size&gt;	&lt;mode&gt;	&lt;path&gt;
 * </pre>
 * Режимы: sync — файл всегда как в сборке; config — обновляется, только если игрок его не менял;
 * once — кладётся один раз, если файла нет; self — сам апдейтер;
 * client — клиентский мод: ставится как sync, только если игрок выбрал клиентские моды (client_mods);
 *          удалённый игроком клиентский мод больше не ставится (config/lumorrix/updater/client-removed.txt).
 * C	&lt;path&gt;	&lt;modId&gt; — modId клиентского мода (старые апдейтеры такие строки пропускают).
 * Неизвестные *.jar в sync-dir сохраняются; prune_unknown_mods=true включает перенос в removed.
 */
final class Manifest {
    static final int FORMAT = 1;

    record Entry(String sha1, long size, String mode, String path) {
    }

    String version = "?";
    URI filesBase;
    final List<String> syncDirs = new ArrayList<>();
    final List<Entry> entries = new ArrayList<>();
    /** Клиентский мод → его modId (строки «C	путь	modId»): чтобы узнать мод и в новой версии файла. */
    final java.util.Map<String, String> clientIds = new java.util.HashMap<>();

    static Manifest parse(String text, URI manifestUri) throws IOException {
        Manifest m = new Manifest();
        m.filesBase = manifestUri.resolve("files/");
        int format = -1;
        for (String raw : text.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (raw.startsWith("C\t")) {
                String[] p = raw.split("\t", 3);
                if (p.length == 3) {
                    m.clientIds.put(p[1].replace('\\', '/'), p[2].strip());
                }
                continue;
            }
            if (line.startsWith("F\t")) {
                String[] p = raw.split("\t", 5);
                if (p.length != 5) {
                    throw new IOException("Битая строка манифеста: " + raw);
                }
                String sha1 = p[1].toLowerCase(Locale.ROOT);
                if (!sha1.matches("[0-9a-f]{40}")) {
                    throw new IOException("Неверный sha1 в манифесте: " + raw);
                }
                m.entries.add(new Entry(sha1, Long.parseLong(p[2]), p[3], p[4].replace('\\', '/')));
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            switch (key) {
                case "format" -> format = Integer.parseInt(value);
                case "version" -> m.version = value;
                case "files" -> m.filesBase = manifestUri.resolve(value.endsWith("/") ? value : value + "/");
                case "sync-dir" -> m.syncDirs.add(value.replace('\\', '/'));
                default -> {
                }
            }
        }
        if (format != FORMAT) {
            throw new IOException("Неподдерживаемый формат манифеста: " + format
                    + " (нужна новая версия lumorrix-updater)");
        }
        return m;
    }
}
