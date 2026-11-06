package ru.lumorrix.updater;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Пишет и в лог игры (slf4j), и в собственный config/lumorrix/updater/updater.log.
 * В установщике (java -jar) slf4j нет — тогда только файл и консоль.
 */
final class Log {
    private static final boolean SLF4J = hasSlf4j();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static Path file;

    private Log() {
    }

    static void init(Path logFile) {
        file = logFile;
        try {
            Files.createDirectories(logFile.getParent());
            // Держим только последний запуск, чтобы файл не рос бесконечно.
            Files.writeString(logFile, "", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            file = null;
        }
    }

    private static boolean hasSlf4j() {
        try {
            Class.forName("org.slf4j.LoggerFactory", false, Log.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Отдельный класс, чтобы без slf4j он просто не загружался. */
    private static final class Slf4j {
        static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("LumorrixUpdater");
    }

    static void info(String msg) {
        if (SLF4J) {
            Slf4j.LOG.info(msg);
        } else {
            System.out.println(msg);
        }
        write("INFO", msg, null);
    }

    /** Подробности — только в updater.log, чтобы не засорять лог игры сотнями строк. */
    static void detail(String msg) {
        write("INFO", msg, null);
    }

    static void warn(String msg) {
        if (SLF4J) {
            Slf4j.LOG.warn(msg);
        } else {
            System.out.println(msg);
        }
        write("WARN", msg, null);
    }

    static void error(String msg, Throwable t) {
        if (SLF4J) {
            Slf4j.LOG.error(msg, t);
        } else {
            System.out.println(msg + ": " + t);
        }
        write("ERROR", msg, t);
    }

    private static synchronized void write(String level, String msg, Throwable t) {
        if (file == null) {
            return;
        }
        StringBuilder sb = new StringBuilder()
                .append(LocalDateTime.now().format(TIME)).append(" [").append(level).append("] ").append(msg)
                .append(System.lineSeparator());
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append(sw);
        }
        try {
            Files.writeString(file, sb, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
