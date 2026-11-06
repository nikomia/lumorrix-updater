package ru.lumorrix.updater;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.List;
import java.util.Set;

/**
 * Точка входа. ModLauncher подхватывает jar из папки mods как сервис трансформации
 * ещё до того, как NeoForge начнёт искать моды, поэтому в onLoad можно спокойно
 * докачивать, заменять и удалять jar-файлы модов.
 */
public final class UpdaterService implements ITransformationService {

    @Override
    public String name() {
        return "lumorrix_updater";
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
        try {
            Path gameDir = env.getProperty(IEnvironment.Keys.GAMEDIR.get())
                    .orElse(Path.of("").toAbsolutePath()).toAbsolutePath().normalize();
            boolean forced = Boolean.getBoolean("lumorrix.updater.force");
            if (!forced && isServer(env, gameDir)) {
                Log.info("Это сервер — обновление клиентской сборки пропущено");
                return;
            }
            if (update(gameDir)) {
                restart();
            }
        } catch (Updater.Cancelled c) {
            Log.info("Окно выбора модов закрыто — закрываю игру");
            System.exit(0);
        } catch (Throwable t) {
            // Апдейтер никогда не должен ронять игру.
            Log.error("Обновление сборки не удалось", t);
        }
    }

    /**
     * Обновляет сборку и сообщает версии моду авторизации. Лаунчер Lumorrix сам ставит сборку до запуска игры
     * и передаёт -Dlumorrix.launcher.managed=true — тогда файлы здесь не скачиваются и не удаляются.
     * Возвращает true, если нужен перезапуск игры.
     */
    static boolean update(Path gameDir) throws java.io.IOException {
        Updater updater = new Updater(gameDir);
        try {
            if (Boolean.getBoolean("lumorrix.launcher.managed")) {
                updater.runManaged();
            } else {
                updater.run();
            }
        } finally {
            publishVersions(gameDir);
        }
        return updater.needRestart();
    }

    /** Новые моды раннего слоя заработают только после перезапуска: сообщаем и закрываем игру. */
    private static void restart() {
        Log.info("Сборка обновлена — нужен перезапуск, закрываю игру");
        try {
            System.setProperty("awt.useSystemAAFontSettings", "on");
            System.setProperty("swing.aatext", "true");
            RestartDialog.show();
        } catch (Throwable t) {
            Log.warn("Не удалось показать окно перезапуска: " + t);
            Dialogs.info("Lumorrix", "Сборка обновлена. Часть модов заработает только после перезапуска —\n"
                    + "игра закроется, запустите её снова.");
        }
        System.exit(0);
    }

    /**
     * Мод авторизации отправляет это серверу: по нему сервер понимает, что игрок зашёл через апдейтер
     * и с какой версией сборки. Если сборку обновить не вышло, version.txt останется старым — и сервер
     * попросит перезапустить игру.
     */
    private static void publishVersions(Path gameDir) {
        System.setProperty("lumorrix.updater.version", Updater.VERSION);
        try {
            Path v = Home.of(gameDir).resolve("version.txt");
            if (Files.isRegularFile(v)) {
                System.setProperty("lumorrix.modpack.version", Files.readString(v).strip());
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * В onLoad ModLauncher ещё не заполнил LAUNCHTARGET (приходит пустым), поэтому сервер узнаём
     * по всем доступным признакам, а в остальных случаях считаем запуск клиентским.
     */
    static boolean isServer(IEnvironment env, Path gameDir) {
        String target = env.getProperty(IEnvironment.Keys.LAUNCHTARGET.get()).orElse("").toLowerCase(Locale.ROOT);
        if (!target.isEmpty()) {
            return !target.contains("client");
        }
        // Сервер NeoForge запускается с аргументами из win_args.txt/unix_args.txt — они видны тут.
        String cmd = System.getProperty("sun.java.command", "").toLowerCase(Locale.ROOT);
        if (cmd.contains("forgeserver") || cmd.contains("neoforgeserver")) {
            return true;
        }
        if (cmd.contains("forgeclient")) {
            return false;
        }
        return Files.isRegularFile(gameDir.resolve("server.properties"));
    }

    @Override
    public void initialize(IEnvironment environment) {
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        return List.of();
    }
}
