package ru.lumorrix.updater;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Установка профиля Lumorrix в найденные на компьютере лаунчеры. */
final class Install {
    static final String MC_VERSION = "1.21.1";
    static final String NEOFORGE = "21.1.265";
    static final String VERSION_ID = "neoforge-" + NEOFORGE;
    static final String NEOFORGE_INSTALLER = "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
            + NEOFORGE + "/neoforge-" + NEOFORGE + "-installer.jar";
    static final String PROFILE_KEY = "lumorrix";

    enum Kind {
        /** Официальный лаунчер: NeoForge + профиль с отдельной папкой игры. */
        OFFICIAL,
        /** TLauncher и подобные: всё прямо в .minecraft. */
        DOT_MINECRAFT,
        /** Prism / PolyMC / MultiMC: готовый инстанс. */
        MULTIMC,
        /** Modrinth App, ATLauncher, GDLauncher: импорт .mrpack. */
        MRPACK,
        /** CurseForge: импорт zip-профиля. */
        CURSEFORGE,
        /** Папка, выбранная вручную: апдейтер в mods, сборка туда же. */
        CUSTOM
    }

    record Launcher(Kind kind, String name, Path dir, String hint) {
        /** Можно ли скачать сборку прямо из установщика (папка игры известна заранее). */
        boolean canPredownload() {
            return kind == Kind.OFFICIAL || kind == Kind.DOT_MINECRAFT || kind == Kind.MULTIMC || kind == Kind.CUSTOM;
        }
    }

    /** Итог установки: папка игры (если известна) и что сказать игроку. */
    record Result(Path gameDir, String message) {
    }

    private static final String NL = "\n";
    /** Параметры сборщика мусора G1 для профиля игры (память задаётся отдельно). */
    static final String GC_ARGS = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200"
            + " -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch"
            + " -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M"
            + " -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4"
            + " -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90"
            + " -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem"
            + " -XX:MaxTenuringThreshold=1";
    private final Consumer<String> log;

    Install(Consumer<String> log) {
        this.log = log;
    }

    // ---------- поиск лаунчеров ----------

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean mac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    /** %APPDATA% на Windows, Application Support на macOS, ~/.local/share на Linux. */
    private static Path appData() {
        if (windows()) {
            String a = System.getenv("APPDATA");
            return a != null ? Path.of(a) : home().resolve("AppData/Roaming");
        }
        return mac() ? home().resolve("Library/Application Support") : home().resolve(".local/share");
    }

    private static Path localAppData() {
        String a = System.getenv("LOCALAPPDATA");
        return a != null ? Path.of(a) : home().resolve("AppData/Local");
    }

    static Path minecraftDir() {
        if (mac()) {
            return appData().resolve("minecraft");
        }
        return windows() ? appData().resolve(".minecraft") : home().resolve(".minecraft");
    }

    /** Папка игры для профиля официального лаунчера — отдельно от обычной .minecraft. */
    static Path officialGameDir() {
        return windows() ? appData().resolve(".lumorrix") : home().resolve(".lumorrix");
    }

    private static boolean any(Path... paths) {
        for (Path p : paths) {
            if (p != null && Files.exists(p)) {
                return true;
            }
        }
        return false;
    }

    static List<Launcher> detect() {
        List<Launcher> out = new ArrayList<>();
        Path mc = minecraftDir();

        Path pf86 = Path.of(System.getenv().getOrDefault("ProgramFiles(x86)", "C:/Program Files (x86)"));
        Path pf = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:/Program Files"));
        // launcher_profiles.json создают и сторонние лаунчеры, поэтому смотрим только на следы самого официального.
        boolean official = any(mc.resolve("launcher_accounts.json"), mc.resolve("launcher_accounts_microsoft_store.json"),
                mc.resolve("launcher_ui_state.json"), pf86.resolve("Minecraft Launcher"), pf.resolve("Minecraft Launcher"),
                Path.of("C:/XboxGames/Minecraft Launcher"),
                localAppData().resolve("Packages/Microsoft.4297127D64EC6_8wekyb3d8bbwe"),
                mac() ? Path.of("/Applications/Minecraft.app") : null);
        if (official) {
            out.add(new Launcher(Kind.OFFICIAL, "Minecraft Launcher", mc,
                    "Поставит NeoForge " + NEOFORGE + " и профиль «Lumorrix» со своей папкой"));
        }
        // TL Legacy (Legacy Launcher) ставится в %APPDATA%\.tlauncher\legacy, обычный TLauncher — в .tlauncher рядом.
        Path tl = appData().resolve(".tlauncher");
        Path tlLegacy = tl.resolve("legacy");
        if (Files.isDirectory(tlLegacy)) {
            Path dir = gameDirFromProperties(tlLegacy, mc);
            out.add(new Launcher(Kind.DOT_MINECRAFT, "TL Legacy", dir,
                    "Поставит NeoForge " + NEOFORGE + " и сборку в папку игры лаунчера"));
        }
        if (any(mc.resolve("TlauncherProfiles.json")) || hasOtherThan(tl, "legacy")) {
            Path dir = gameDirFromProperties(tl, mc);
            out.add(new Launcher(Kind.DOT_MINECRAFT, "TLauncher", dir,
                    "Поставит NeoForge " + NEOFORGE + " и сборку в папку игры лаунчера"));
        }

        addMultiMc(out, "Prism Launcher", windows() || mac() ? appData().resolve("PrismLauncher")
                : home().resolve(".local/share/PrismLauncher"));
        addMultiMc(out, "PolyMC", windows() || mac() ? appData().resolve("PolyMC")
                : home().resolve(".local/share/PolyMC"));
        for (Path p : List.of(home().resolve("MultiMC"), home().resolve("Desktop/MultiMC"),
                home().resolve("Downloads/MultiMC"), Path.of("C:/MultiMC"), localAppData().resolve("Programs/MultiMC"))) {
            if (Files.isRegularFile(p.resolve("multimc.cfg"))) {
                addMultiMc(out, "MultiMC", p);
                break;
            }
        }

        for (Path p : List.of(appData().resolve("ModrinthApp"), appData().resolve("com.modrinth.theseus"))) {
            if (Files.isDirectory(p)) {
                out.add(new Launcher(Kind.MRPACK, "Modrinth App", p,
                        "Откроет импорт сборки Lumorrix.mrpack"));
                break;
            }
        }
        if (any(appData().resolve("ATLauncher"), home().resolve("ATLauncher"))) {
            out.add(new Launcher(Kind.MRPACK, "ATLauncher", appData().resolve("ATLauncher"),
                    "Сохранит Lumorrix.mrpack для импорта (вкладка «Import»)"));
        }
        if (any(appData().resolve("gdlauncher_carbon"), appData().resolve("gdlauncher_next"))) {
            out.add(new Launcher(Kind.MRPACK, "GDLauncher", appData().resolve("gdlauncher_carbon"),
                    "Сохранит Lumorrix.mrpack для импорта"));
        }
        if (any(home().resolve("curseforge/minecraft"), appData().resolve("CurseForge"))) {
            out.add(new Launcher(Kind.CURSEFORGE, "CurseForge", home().resolve("curseforge/minecraft"),
                    "Сохранит zip для «Создать профиль» → «Импорт»"));
        }
        return out;
    }

    /** Есть ли в папке что-то кроме указанной подпапки. */
    private static boolean hasOtherThan(Path dir, String except) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var s = Files.list(dir)) {
            return s.anyMatch(p -> !p.getFileName().toString().equalsIgnoreCase(except));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Папка игры из настроек лаунчеров семейства TLauncher (ключ minecraft.gamedir в *.properties).
     * Формат точно не документирован, поэтому если ничего не нашли — обычная .minecraft.
     */
    static Path gameDirFromProperties(Path root, Path fallback) {
        boolean skipLegacy = !root.getFileName().toString().equalsIgnoreCase("legacy");
        try (var s = Files.walk(root, 4)) {
            for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".properties"))
                    .filter(f -> !skipLegacy || !root.relativize(f).toString().toLowerCase(Locale.ROOT).startsWith("legacy"))
                    .toList()) {
                if (Files.size(p) > 1_000_000) {
                    continue;
                }
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(p)) {
                    props.load(in);
                }
                String v = props.getProperty("minecraft.gamedir");
                if (v != null && !v.isBlank()) {
                    Path dir = Path.of(v.strip());
                    if (Files.isDirectory(dir)) {
                        return dir.toAbsolutePath().normalize();
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return fallback;
    }

    private static void addMultiMc(List<Launcher> out, String name, Path dir) {
        if (Files.isDirectory(dir.resolve("instances")) || Files.isRegularFile(dir.resolve("prismlauncher.cfg"))
                || Files.isRegularFile(dir.resolve("polymc.cfg")) || Files.isRegularFile(dir.resolve("multimc.cfg"))) {
            out.add(new Launcher(Kind.MULTIMC, name, dir, "Создаст готовый инстанс «Lumorrix»"));
        }
    }

    // ---------- установка ----------

    Result install(Launcher l, int ramGb) throws IOException, InterruptedException {
        return switch (l.kind()) {
            case OFFICIAL -> {
                Path gameDir = installOfficial(l.dir(), ramGb);
                yield new Result(gameDir, "Откройте Minecraft Launcher, выберите профиль «Lumorrix» и нажмите «Играть».");
            }
            case DOT_MINECRAFT -> {
                installNeoForgeIfMissing(l.dir());
                putUpdater(l.dir());
                yield new Result(l.dir(), "Откройте " + l.name() + ", выберите версию «" + VERSION_ID
                        + "» и задайте не меньше " + ramGb + " ГБ памяти в настройках.");
            }
            case MULTIMC -> {
                Path mc = installMultiMc(l.dir(), ramGb);
                yield new Result(mc, "В " + l.name() + " появился инстанс «Lumorrix» (если нет — перезапустите лаунчер).");
            }
            case MRPACK -> {
                Path pack = writeMrpack();
                boolean opened = l.name().equals("Modrinth App") && open(pack);
                if (!opened) {
                    reveal(pack);
                }
                yield new Result(null, (opened ? "В Modrinth App открылся импорт — нажмите «Установить».\n"
                        : "Файл сохранён: " + pack + "\nИмпортируйте его в " + l.name() + ".\n")
                        + "Задайте профилю " + ramGb + " ГБ памяти. При первом запуске сборка скачается сама.");
            }
            case CUSTOM -> {
                Path dir = gameDirFor(l.dir());
                putUpdater(dir);
                yield new Result(dir, "Сборка ставится в " + dir + "." + NL + "Запустите её в своём лаунчере на NeoForge "
                        + NEOFORGE + " (Minecraft " + MC_VERSION + "), указав эту папку как папку игры," + NL
                        + "и дайте игре не меньше " + ramGb + " ГБ памяти.");
            }
            case CURSEFORGE -> {
                Path pack = writeCurseForgeZip();
                reveal(pack);
                yield new Result(null, "Файл сохранён: " + pack + "\nВ CurseForge: «Minecraft» → «Создать профиль» → "
                        + "«Импорт» → выберите этот файл.\nПри первом запуске сборка скачается сама.");
            }
        };
    }

    /** Возвращает папку игры, куда потом качается сборка. */
    Path installOfficial(Path mcDir, int ramGb) throws IOException, InterruptedException {
        installNeoForgeIfMissing(mcDir);
        Path gameDir = officialGameDir();
        putUpdater(gameDir);

        log.accept("Добавляю профиль «Lumorrix» в launcher_profiles.json");
        Path profiles = mcDir.resolve("launcher_profiles.json");
        Object root;
        try {
            root = Json.parse(Files.readString(profiles, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException("launcher_profiles.json повреждён: " + e.getMessage(), e);
        }
        if (!(root instanceof Map)) {
            throw new IOException("launcher_profiles.json: неожиданный формат");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> rootMap = (Map<String, Object>) root;
        Object p = rootMap.get("profiles");
        @SuppressWarnings("unchecked")
        Map<String, Object> list = p instanceof Map ? (Map<String, Object>) p : new LinkedHashMap<>();
        rootMap.put("profiles", list);

        String now = Instant.now().toString();
        Map<String, Object> profile = new LinkedHashMap<>();
        Object old = list.get(PROFILE_KEY);
        profile.put("created", old instanceof Map<?, ?> o && o.get("created") != null ? o.get("created") : now);
        profile.put("gameDir", gameDir.toString());
        profile.put("icon", iconDataUri());
        profile.put("javaArgs", "-Xmx" + ramGb + "G -Xms2G " + GC_ARGS);
        profile.put("lastUsed", now);
        profile.put("lastVersionId", VERSION_ID);
        profile.put("name", "Lumorrix");
        profile.put("type", "custom");
        list.put(PROFILE_KEY, profile);

        Files.copy(profiles, mcDir.resolve("launcher_profiles.json.bak-lumorrix"), StandardCopyOption.REPLACE_EXISTING);
        Path tmp = mcDir.resolve("launcher_profiles.json.tmp");
        Files.writeString(tmp, Json.write(rootMap), StandardCharsets.UTF_8);
        Files.move(tmp, profiles, StandardCopyOption.REPLACE_EXISTING);
        return gameDir;
    }

    private void installNeoForgeIfMissing(Path mcDir) throws IOException, InterruptedException {
        Files.createDirectories(mcDir);
        Path profiles = mcDir.resolve("launcher_profiles.json");
        if (!Files.isRegularFile(profiles)) {
            // Без этого файла установщик NeoForge отказывается ставить клиент.
            Files.writeString(profiles, "{\n  \"profiles\" : {}\n}\n", StandardCharsets.UTF_8);
        }
        Path versionJson = mcDir.resolve("versions").resolve(VERSION_ID).resolve(VERSION_ID + ".json");
        if (Files.isRegularFile(versionJson)) {
            log.accept("NeoForge " + NEOFORGE + " уже установлен");
            return;
        }
        Path dir = Files.createTempDirectory("lumorrix-neoforge");
        Path jar = dir.resolve("neoforge-" + NEOFORGE + "-installer.jar");
        log.accept("Скачиваю установщик NeoForge " + NEOFORGE);
        try (InputStream in = URI.create(NEOFORGE_INSTALLER).toURL().openStream()) {
            Files.copy(in, jar);
        }
        log.accept("Устанавливаю NeoForge (скачает Minecraft " + MC_VERSION + ", это пара минут)");
        String java = ProcessHandle.current().info().command().orElse("java");
        Process proc = new ProcessBuilder(java, "-jar", jar.toString(), "--install-client", mcDir.toString())
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String l = line.strip();
                if (!l.isEmpty() && l.length() < 160) {
                    log.accept("NeoForge: " + l);
                }
            }
        }
        if (!proc.waitFor(20, TimeUnit.MINUTES)) {
            proc.destroyForcibly();
            throw new IOException("Установщик NeoForge завис");
        }
        if (proc.exitValue() != 0) {
            throw new IOException("Установщик NeoForge завершился с кодом " + proc.exitValue()
                    + " (лог: " + dir.resolve(jar.getFileName() + ".log") + ")");
        }
        if (!Files.isRegularFile(versionJson)) {
            throw new IOException("Установщик NeoForge завершился, но версия " + VERSION_ID + " не появилась");
        }
    }

    /** Prism / PolyMC / MultiMC. Возвращает папку minecraft нового инстанса. */
    Path installMultiMc(Path launcherDir, int ramGb) throws IOException {
        Path inst = pickInstanceDir(instancesDir(launcherDir));
        log.accept("Создаю инстанс: " + inst);
        Path mc = inst.resolve("minecraft");
        Files.createDirectories(mc);
        Files.writeString(inst.resolve("instance.cfg"), String.join("\n",
                "[General]",
                "ConfigVersion=1.3",
                "InstanceType=OneSix",
                "name=Lumorrix",
                "iconKey=lumorrix",
                "OverrideMemory=true",
                "MinMemAlloc=2048",
                "MaxMemAlloc=" + ramGb * 1024,
                "OverrideJavaArgs=true",
                "JvmArgs=" + GC_ARGS,
                ""), StandardCharsets.UTF_8);
        try (InputStream in = resource("mmc-pack.json")) {
            Files.copy(in, inst.resolve("mmc-pack.json"), StandardCopyOption.REPLACE_EXISTING);
        }
        Path icons = launcherDir.resolve("icons");
        Files.createDirectories(icons);
        try (InputStream in = resource("icon.png")) {
            Files.copy(in, icons.resolve("lumorrix.png"), StandardCopyOption.REPLACE_EXISTING);
        }
        putUpdater(mc);
        return mc;
    }

    static Path instancesDir(Path launcherDir) {
        for (String cfgName : List.of("prismlauncher.cfg", "polymc.cfg", "multimc.cfg")) {
            Path cfg = launcherDir.resolve(cfgName);
            if (!Files.isRegularFile(cfg)) {
                continue;
            }
            Properties p = new Properties();
            try (var r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                p.load(r);
                String dir = p.getProperty("InstanceDir");
                if (dir != null && !dir.isBlank()) {
                    return launcherDir.resolve(dir.strip());
                }
            } catch (IOException | IllegalArgumentException ignored) {
            }
        }
        return launcherDir.resolve("instances");
    }

    /** «Lumorrix», а если папка занята чужим инстансом — «Lumorrix-2» и т. д. Свой прошлый — переиспользуем. */
    private static Path pickInstanceDir(Path instances) {
        for (int n = 1; ; n++) {
            Path p = instances.resolve(n == 1 ? "Lumorrix" : "Lumorrix-" + n);
            if (!Files.exists(p) || Files.isRegularFile(p.resolve("minecraft/mods/" + Updater.SELF_NAME))) {
                return p;
            }
        }
    }

    // ---------- файлы сборок для импорта ----------

    /** Modrinth-формат: только NeoForge и апдейтер в overrides, остальное он докачает при первом запуске. */
    Path writeMrpack() throws IOException {
        String index = Json.write(Map.of(
                "formatVersion", new Json.Num("1"),
                "game", "minecraft",
                "versionId", Updater.VERSION,
                "name", "Lumorrix",
                "summary", "Сборка сервера Lumorrix — обновляется автоматически",
                "files", List.of(),
                "dependencies", Map.of("minecraft", MC_VERSION, "neoforge", NEOFORGE)));
        return writePack("Lumorrix.mrpack", "modrinth.index.json", index);
    }

    Path writeCurseForgeZip() throws IOException {
        Map<String, Object> loader = new LinkedHashMap<>();
        loader.put("id", VERSION_ID);
        loader.put("primary", Boolean.TRUE);
        Map<String, Object> minecraft = new LinkedHashMap<>();
        minecraft.put("version", MC_VERSION);
        minecraft.put("modLoaders", List.of(loader));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minecraft", minecraft);
        m.put("manifestType", "minecraftModpack");
        m.put("manifestVersion", new Json.Num("1"));
        m.put("name", "Lumorrix");
        m.put("version", Updater.VERSION);
        m.put("author", "Lumorrix");
        m.put("files", List.of());
        m.put("overrides", "overrides");
        return writePack("Lumorrix-CurseForge.zip", "manifest.json", Json.write(m));
    }

    private Path writePack(String fileName, String indexName, String index) throws IOException {
        Path downloads = home().resolve("Downloads");
        Path out = (Files.isDirectory(downloads) ? downloads : home()).resolve(fileName);
        log.accept("Сохраняю " + out);
        try (OutputStream os = Files.newOutputStream(out); ZipOutputStream z = new ZipOutputStream(os)) {
            z.putNextEntry(new ZipEntry(indexName));
            z.write(index.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("overrides/mods/" + Updater.SELF_NAME));
            Files.copy(selfJar(), z);
            z.closeEntry();
        }
        return out;
    }

    private boolean open(Path file) {
        try {
            java.awt.Desktop.getDesktop().open(file.toFile());
            return true;
        } catch (Throwable t) {
            log.accept("Не удалось открыть " + file.getFileName() + " автоматически: " + t.getMessage());
            return false;
        }
    }

    private static void reveal(Path file) {
        try {
            if (windows()) {
                new ProcessBuilder("explorer.exe", "/select," + file).start();
            } else {
                java.awt.Desktop.getDesktop().open(file.getParent().toFile());
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------- общее ----------

    /** Если выбран корень инстанса Prism/MultiMC — берём его папку игры (minecraft или .minecraft). */
    static Path gameDirFor(Path dir) {
        if (Files.isRegularFile(dir.resolve("instance.cfg"))) {
            for (String sub : List.of("minecraft", ".minecraft")) {
                if (Files.isDirectory(dir.resolve(sub))) {
                    return dir.resolve(sub);
                }
            }
            return dir.resolve("minecraft");
        }
        return dir;
    }

    /** jar, из которого запущен установщик. */
    static Path selfJar() throws IOException {
        try {
            Path p = Path.of(Install.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(p)) {
                throw new IOException("Установщик запущен не из jar-файла: " + p);
            }
            return p;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Не удалось определить путь к lumorrix-updater.jar", e);
        }
    }

    private void putUpdater(Path gameDir) throws IOException {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        Path target = mods.resolve(Updater.SELF_NAME);
        Path self = selfJar();
        if (!self.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            Files.copy(self, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.accept("Апдейтер положен в " + mods);
    }

    static InputStream resource(String name) throws IOException {
        InputStream in = Install.class.getResourceAsStream("/lumorrix/" + name);
        if (in == null) {
            throw new IOException("В jar нет ресурса " + name);
        }
        return in;
    }

    private static String iconDataUri() throws IOException {
        try (InputStream in = resource("icon.png")) {
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        }
    }
}
