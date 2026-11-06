package ru.lumorrix.updater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * config/lumorrix/updater/state.tsv:
 * I	path	sha1	mode — файлы, которые положил апдейтер (по ним понятно, что можно удалять);
 * C	path	size	mtime	sha1 — кэш хешей, чтобы не пересчитывать 500 МБ модов на каждом запуске.
 */
final class State {
    record Installed(String sha1, String mode) {
    }

    record Cached(long size, long mtime, String sha1) {
    }

    final Map<String, Installed> installed = new TreeMap<>();
    final Map<String, Cached> cache = new TreeMap<>();
    private final Path file;

    private State(Path file) {
        this.file = file;
    }

    static State load(Path file) {
        State s = new State(file);
        if (!Files.isRegularFile(file)) {
            return s;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length == 4 && p[0].equals("I")) {
                    s.installed.put(p[1], new Installed(p[2], p[3]));
                } else if (p.length == 5 && p[0].equals("C")) {
                    s.cache.put(p[1], new Cached(Long.parseLong(p[2]), Long.parseLong(p[3]), p[4]));
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("state.tsv повреждён, начинаю с чистого состояния: " + e);
            s.installed.clear();
            s.cache.clear();
        }
        return s;
    }

    void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        installed.forEach((path, i) -> sb.append("I\t").append(path).append('\t').append(i.sha1())
                .append('\t').append(i.mode()).append('\n'));
        cache.forEach((path, c) -> sb.append("C\t").append(path).append('\t').append(c.size())
                .append('\t').append(c.mtime()).append('\t').append(c.sha1()).append('\n'));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
