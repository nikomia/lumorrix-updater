package ru.lumorrix.updater;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/** Запускается отдельным процессом после выхода из игры и подменяет jar апдейтера. */
public final class SelfReplace {
    private SelfReplace() {
    }

    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Path from = Path.of(args[1]);
        Path to = Path.of(args[2]);
        ProcessHandle.of(pid).ifPresent(h -> {
            try {
                h.onExit().get(10, TimeUnit.MINUTES);
            } catch (Exception ignored) {
            }
        });
        for (int i = 0; i < 30; i++) {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Заменён " + to);
                return;
            } catch (Exception e) {
                Thread.sleep(1000);
            }
        }
        System.out.println("Не удалось заменить " + to);
    }
}
