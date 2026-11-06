package ru.lumorrix.updater;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Мод авторизации едет внутри lumorrix-updater.jar. Сам NeoForge его не увидит: jar апдейтера
 * загружается как ранний сервис и как мод не сканируется. Поэтому распаковываем вложенный мод
 * в config/lumorrix/updater/ и сами отдаём его NeoForge'у — для игрока это по-прежнему один файл в mods.
 */
public final class AuthModLocator implements IModFileCandidateLocator {
    static final String EMBEDDED = "/META-INF/lumorrix/lumorrix-auth.jar";

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        try {
            Path target = Home.of(FMLPaths.GAMEDIR.get()).resolve("lumorrix-auth.jar");
            extract(target);
            pipeline.addPath(target, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
        } catch (Throwable t) {
            Log.error("Не удалось подключить мод авторизации", t);
        }
    }

    /** Перезаписываем, только если содержимое изменилось (новая версия апдейтера). */
    static void extract(Path target) throws IOException {
        byte[] data;
        try (InputStream in = AuthModLocator.class.getResourceAsStream(EMBEDDED)) {
            if (in == null) {
                throw new IOException("в jar нет " + EMBEDDED);
            }
            data = in.readAllBytes();
        }
        if (Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), data)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public String toString() {
        return "lumorrix-auth (из lumorrix-updater)";
    }
}
