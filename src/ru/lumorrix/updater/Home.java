package ru.lumorrix.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Рабочая папка апдейтера: config/lumorrix/updater. До 1.5.0 она лежала в корне игры
 * (lumorrix-updater/) — при первом обращении переносим её содержимое на новое место.
 */
final class Home {
    private Home() {
    }

    static Path of(Path gameDir) {
        Path home = gameDir.resolve("config").resolve("lumorrix").resolve("updater");
        Path old = gameDir.resolve("lumorrix-updater");
        if (Files.isDirectory(old)) {
            try {
                moveTree(old, home);
            } catch (IOException ignored) {
                // Не вышло — останется старая папка, настройки возьмём по умолчанию.
            }
        }
        return home;
    }

    /** Переносит файлы из from в to (уже существующие в to не перезаписывает) и удаляет from. */
    static void moveTree(Path from, Path to) throws IOException {
        List<Path> all;
        try (Stream<Path> s = Files.walk(from)) {
            all = s.toList();
        }
        for (Path p : all) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            Path target = to.resolve(from.relativize(p).toString());
            if (Files.exists(target)) {
                Files.delete(p);
            } else {
                Files.createDirectories(target.getParent());
                Files.move(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (Path p : all.stream().sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(p);
        }
    }
}
