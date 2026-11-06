package ru.lumorrix.updater;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

final class Updater {
    static final String VERSION = "1.6.13";
    static final String DEFAULT_URL = "https://lumorrix.ru/modpack/manifest.txt";
    static final String SELF_NAME = "lumorrix-updater.jar";
    private static final int THREADS = 4;
    private static final int RETRIES = 5;

    /** Jar с такими сервисами FML грузит раньше нас — новый такой мод заработает только со следующего запуска. */
    private static final Set<String> SERVICE_LAYER = Set.of(
            "cpw.mods.modlauncher.api.ITransformationService",
            "net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
            "net.neoforged.neoforgespi.locating.IModFileReader",
            "net.neoforged.neoforgespi.locating.IDependencyLocator",
            "net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper",
            "net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider");

    private final Path gameDir;
    private final Path home;
    private final Path tmpDir;
    private State state;
    private URI filesBase;
    private boolean needRestart;
    private boolean pruneUnknownMods;
    /** Итог для установщика: сборка в актуальном состоянии. */
    private boolean ok;

    Updater(Path gameDir) {
        this.gameDir = gameDir;
        this.home = Home.of(gameDir);
        this.tmpDir = home.resolve("tmp");
    }

    boolean ok() {
        return ok;
    }

    /** Обновление принесло моды, которые NeoForge грузит до апдейтера, — нужен перезапуск игры. */
    boolean needRestart() {
        return needRestart;
    }


    void run() throws IOException {
        Log.init(home.resolve("updater.log"));
        Log.info("Lumorrix Updater " + VERSION + ", папка игры: " + gameDir);
        Properties settings = loadSettings();
        if (!Boolean.parseBoolean(settings.getProperty("enabled", "true").strip())) {
            Log.info("Обновление выключено в config/lumorrix/updater/settings.properties");
            return;
        }
        String url = System.getProperty("lumorrix.updater.url", settings.getProperty("url", DEFAULT_URL)).strip();
        List<Pattern> keep = globs(settings.getProperty("keep", ""));
        pruneUnknownMods = Boolean.parseBoolean(settings.getProperty("prune_unknown_mods", "false").strip());
        cleanupLeftovers();

        Progress progress = Progress.open("Lumorrix: проверка сборки", "Lumorrix: checking modpack");
        try {
            URI manifestUri = URI.create(url);
            Manifest manifest;
            try {
                manifest = Manifest.parse(fetchText(manifestUri), manifestUri);
            } catch (IOException e) {
                Log.warn("Не удалось получить манифест " + url + ": " + e);
                Progress.message("Lumorrix: сервер сборки недоступен — запуск без обновления",
                        "Lumorrix: update server unavailable, starting without update");
                return;
            }
            Log.info("Сборка на сервере: " + manifest.version + ", файлов: " + manifest.entries.size());
            applyClientChoice(manifest, settings);
            update(manifest, keep, progress);
        } finally {
            progress.close();
            try {
                migratePunchyArmorSleeves();
            } catch (IOException | RuntimeException e) {
                Log.error("Could not apply the Punchy armor sleeve default", e);
            }
        }
    }

    /** Запуск из лаунчера Lumorrix: сборку уже поставил он, здесь только локальные настройки. */
    void runManaged() throws IOException {
        Log.init(home.resolve("updater.log"));
        Log.info("Lumorrix Updater " + VERSION + ", папка игры: " + gameDir + " — сборкой управляет лаунчер");
        try {
            migratePunchyArmorSleeves();
        } catch (IOException | RuntimeException e) {
            Log.error("Could not apply the Punchy armor sleeve default", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void migratePunchyArmorSleeves() throws IOException {
        Path marker = home.resolve("punchy-armor-sleeves-v1");
        Path mods = gameDir.resolve("mods");
        if (Files.exists(marker) || !Files.isDirectory(mods)) return;
        try (var jars = Files.newDirectoryStream(mods, "punchy-*.jar")) {
            if (!jars.iterator().hasNext()) return;
        }

        Path config = gameDir.resolve("config/punchy/punchy_config.json");
        Object data = Files.exists(config) ? Json.parse(Files.readString(config)) : new LinkedHashMap<String, Object>();
        if (!(data instanceof Map<?, ?> fields)) throw new IOException("Punchy config must be a JSON object");
        if (!Boolean.FALSE.equals(fields.get("renderArmorArmsFP"))) {
            ((Map<String, Object>) fields).put("renderArmorArmsFP", false);
            Files.createDirectories(config.getParent());
            Path temporary = Files.createTempFile(config.getParent(), "punchy-", ".tmp");
            try {
                Files.writeString(temporary, Json.write(fields));
                Files.move(temporary, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        Files.createDirectories(home);
        Files.writeString(marker, "");
    }

    private void update(Manifest manifest, List<Pattern> keep, Progress progress) throws IOException {
        state = State.load(home.resolve("state.tsv"));
        filesBase = manifest.filesBase;
        Set<String> manifestPaths = new HashSet<>();
        List<Manifest.Entry> toDownload = new ArrayList<>();

        // 1. Что скачать.
        int checked = 0;
        for (Manifest.Entry e : manifest.entries) {
            checked++;
            progress.update("Проверка файлов: " + checked + "/" + manifest.entries.size(),
                    "Checking files: " + checked + "/" + manifest.entries.size(),
                    (double) checked / manifest.entries.size());
            Path target = resolve(e.path());
            if (target == null) {
                Log.warn("Пропускаю подозрительный путь из манифеста: " + e.path());
                continue;
            }
            manifestPaths.add(e.path());
            String current = Files.isRegularFile(target) ? hash(e.path(), target) : null;
            State.Installed rec = state.installed.get(e.path());
            boolean need = switch (e.mode()) {
                case "sync", "self" -> !e.sha1().equals(current);
                // Игрок поменял конфиг (хеш не совпадает с тем, что мы клали) — не трогаем.
                case "config" -> current == null
                        || (!e.sha1().equals(current) && (rec == null || rec.sha1().equals(current)));
                case "once" -> current == null && rec == null;
                default -> {
                    Log.warn("Неизвестный режим " + e.mode() + " у " + e.path());
                    yield false;
                }
            };
            if (need) {
                toDownload.add(e);
            } else if (e.sha1().equals(current)) {
                state.installed.put(e.path(), new State.Installed(e.sha1(), e.mode()));
            }
        }

        // 2. Что убрать: файлы, которые клали мы и которых больше нет в сборке…
        List<Path> toDelete = new ArrayList<>();
        for (Iterator<Map.Entry<String, State.Installed>> it = state.installed.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, State.Installed> rec = it.next();
            // Читаем до it.remove(): TreeMap может переиспользовать узел под соседний ключ.
            String path = rec.getKey();
            State.Installed installed = rec.getValue();
            if (manifestPaths.contains(path)) {
                continue;
            }
            it.remove();
            Path f = resolve(path);
            if (f == null || !Files.isRegularFile(f)) {
                continue;
            }
            String mode = installed.mode();
            boolean ours = mode.equals("sync") || installed.sha1().equals(hash(path, f));
            if (ours && !mode.equals("self")) {
                toDelete.add(f);
            }
        }
        // Неизвестные jar сохраняем. Строгая очистка включается только явной настройкой.
        List<Path> toQuarantine = new ArrayList<>();
        if (pruneUnknownMods) {
            for (String dir : manifest.syncDirs) {
                Path d = resolve(dir);
                if (d == null || !Files.isDirectory(d)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(d)) {
                    for (Path f : files.toList()) {
                        String name = f.getFileName().toString();
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (!Files.isRegularFile(f) || !lower.endsWith(".jar") || lower.equals(SELF_NAME)
                                || manifestPaths.contains(dir + "/" + name) || toDelete.contains(f)
                                || keep.stream().anyMatch(p -> p.matcher(name).matches())) {
                            continue;
                        }
                        toQuarantine.add(f);
                    }
                }
            }
        }

        if (toDownload.isEmpty() && toDelete.isEmpty() && toQuarantine.isEmpty()) {
            Log.info("Сборка актуальна");
            finish(manifest);
            ok = true;
            return;
        }

        // 3. Скачиваем всё во временную папку. Если хоть что-то не скачалось — не трогаем
        //    ни одного файла, чтобы не получить полуобновлённую сборку.
        if (!download(toDownload, progress)) {
            // state не сохраняем: в нём уже вычеркнуты файлы, которые так и не удалили.
            Progress.message("Lumorrix: не удалось скачать обновление — запуск со старой сборкой",
                    "Lumorrix: download failed, starting the previous version");
            return;
        }

        // 4. Применяем.
        progress.update("Установка обновления", "Installing update", 1);
        for (Path f : toDelete) {
            Log.detail("Удаляю: " + gameDir.relativize(f));
            Files.deleteIfExists(f);
            state.cache.remove(key(f));
        }
        Path removed = home.resolve("removed");
        for (Path f : toQuarantine) {
            Files.createDirectories(removed);
            Log.detail("Убираю лишний мод в config/lumorrix/updater/removed: " + f.getFileName());
            Files.move(f, removed.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            state.cache.remove(key(f));
        }
        int failed = 0;
        for (Manifest.Entry e : toDownload) {
            Path blob = tmpDir.resolve(e.sha1());
            Path target = resolve(e.path());
            try {
                Files.createDirectories(target.getParent());
                if (e.mode().equals("self")) {
                    selfUpdate(blob, target);
                } else {
                    Files.copy(blob, target, StandardCopyOption.REPLACE_EXISTING);
                    if (target.getFileName().toString().endsWith(".jar") && isServiceLayerJar(target)) {
                        Log.info(e.path() + " грузится до модов — нужен перезапуск");
                        needRestart = true;
                    }
                }
                Log.detail("Установлен: " + e.path());
                state.installed.put(e.path(), new State.Installed(e.sha1(), e.mode()));
                state.cache.remove(e.path());
            } catch (IOException ex) {
                failed++;
                Log.error("Не удалось установить " + e.path(), ex);
            }
        }
        Log.info("Установлено: " + (toDownload.size() - failed) + ", удалено: " + toDelete.size()
                + ", убрано в removed: " + toQuarantine.size() + ", ошибок: " + failed);
        if (failed > 0) {
            Progress.message("Lumorrix: " + failed + " файл(ов) не удалось обновить, подробности в config/lumorrix/updater/updater.log",
                    "Lumorrix: " + failed + " file(s) failed to update, see config/lumorrix/updater/updater.log");
        } else {
            Progress.message("Lumorrix: сборка обновлена до " + manifest.version,
                    "Lumorrix: modpack updated to " + manifest.version);
            ok = true;
        }
        finish(manifest);
    }

    private void finish(Manifest manifest) throws IOException {
        // Кэш хешей держим только для файлов, которые ещё существуют.
        state.cache.keySet().removeIf(p -> {
            Path f = resolve(p);
            return f == null || !Files.isRegularFile(f);
        });
        state.save();
        Files.writeString(home.resolve("version.txt"), manifest.version + "\n", StandardCharsets.UTF_8);
        deleteTree(tmpDir);
        try {
            enableResourcePacks(manifest);
        } catch (IOException | RuntimeException e) {
            Log.error("Не удалось включить ресурспаки в options.txt", e);
        }
    }

    /** Версия данных 1.21.1 — нужна, если options.txt ещё нет (игра не запускалась). */
    private static final int OPTIONS_DATA_VERSION = 3955;

    /**
     * Включает ресурспаки из сборки в options.txt — один раз для каждого пака. Какие уже включали,
     * помним в config/lumorrix/updater/resourcepacks.txt: если игрок сам выключил пак, обратно его не включаем.
     */
    private void enableResourcePacks(Manifest manifest) throws IOException {
        List<String> packs = new ArrayList<>();
        for (Manifest.Entry e : manifest.entries) {
            String p = e.path();
            if (p.startsWith("resourcepacks/") && p.endsWith(".zip") && p.indexOf('/', "resourcepacks/".length()) < 0) {
                packs.add("file/" + p.substring("resourcepacks/".length()));
            }
        }
        Path doneFile = home.resolve("resourcepacks.txt");
        Set<String> done = new HashSet<>();
        if (Files.isRegularFile(doneFile)) {
            done.addAll(Files.readAllLines(doneFile, StandardCharsets.UTF_8));
        }
        List<String> toEnable = packs.stream().filter(p -> !done.contains(p)).toList();
        if (toEnable.isEmpty()) {
            return;
        }

        Path options = gameDir.resolve("options.txt");
        List<String> lines = Files.isRegularFile(options)
                ? new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8))
                // Игра ещё не запускалась: сразу русский язык, иначе перевод не будет виден.
                : new ArrayList<>(List.of("version:" + OPTIONS_DATA_VERSION, "lang:ru_ru"));
        int idx = -1;
        List<Object> enabled = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("resourcePacks:")) {
                idx = i;
                Object parsed = Json.parse(lines.get(i).substring("resourcePacks:".length()));
                if (parsed instanceof List<?> l) {
                    enabled.addAll(l);
                }
            }
        }
        if (!enabled.contains("vanilla")) {
            enabled.add(0, "vanilla");
        }
        for (String p : toEnable) {
            if (!enabled.contains(p)) {
                enabled.add(p); // в конце списка — выше по приоритету, поверх модов
            }
        }
        StringBuilder sb = new StringBuilder("resourcePacks:[");
        for (int i = 0; i < enabled.size(); i++) {
            sb.append(i > 0 ? "," : "").append('"').append(String.valueOf(enabled.get(i)).replace("\"", "\\\"")).append('"');
        }
        String line = sb.append(']').toString();
        if (idx >= 0) {
            lines.set(idx, line);
        } else {
            lines.add(line);
        }
        Path tmp = gameDir.resolve("options.txt.lumorrix-tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, options, StandardCopyOption.REPLACE_EXISTING);
        done.addAll(toEnable);
        Files.write(doneFile, new ArrayList<>(done), StandardCharsets.UTF_8);
        Log.info("Включены ресурспаки: " + String.join(", ", toEnable));
    }

    // ---------- скачивание ----------

    private boolean download(List<Manifest.Entry> entries, Progress progress) {
        if (entries.isEmpty()) {
            return true;
        }
        long total = entries.stream().mapToLong(Manifest.Entry::size).sum();
        Log.info("Скачиваю " + entries.size() + " файл(ов), " + mb(total));
        AtomicLong done = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(r, "lumorrix-updater-download");
            t.setDaemon(true);
            return t;
        });
        try {
            Files.createDirectories(tmpDir);
            List<Future<Boolean>> futures = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Manifest.Entry e : entries) {
                if (seen.add(e.sha1())) {
                    futures.add(pool.submit(() -> fetchBlob(e, done)));
                }
            }
            pool.shutdown();
            while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                progress.update("Загрузка сборки: " + mb(done.get()) + " / " + mb(total),
                        "Downloading modpack: " + mbEn(done.get()) + " / " + mbEn(total),
                        total == 0 ? 1 : (double) done.get() / total);
            }
            boolean all = true;
            for (Future<Boolean> f : futures) {
                all &= f.get();
            }
            return all;
        } catch (Exception e) {
            Log.error("Ошибка загрузки", e);
            return false;
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean fetchBlob(Manifest.Entry e, AtomicLong done) {
        Path blob = tmpDir.resolve(e.sha1());
        URI uri = filesBase.resolve(e.sha1());
        for (int attempt = 1; attempt <= RETRIES; attempt++) {
            long counted = 0;
            try {
                HttpURLConnection c = open(uri);
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(blob)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        counted += n;
                        done.addAndGet(n);
                    }
                }
                String got = HexFormat.of().formatHex(md.digest());
                if (got.equals(e.sha1()) && counted == e.size()) {
                    return true;
                }
                throw new IOException("контрольная сумма не совпала (" + got + ", " + counted + " байт)");
            } catch (IOException | NoSuchAlgorithmException ex) {
                done.addAndGet(-counted);
                Log.warn("Загрузка " + e.path() + ", попытка " + attempt + "/" + RETRIES + ": " + ex);
                if (attempt < RETRIES) {
                    pause(attempt);
                }
            }
        }
        return false;
    }

    private String fetchText(URI uri) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            // Параметр против кэша Cloudflare/прокси: манифест должен быть всегда свежим.
            URI fresh = URI.create(uri + (uri.getQuery() == null ? "?" : "&") + "t=" + System.currentTimeMillis());
            try {
                HttpURLConnection c = open(fresh);
                try (InputStream in = c.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                last = e;
                Log.warn("Манифест, попытка " + attempt + "/3: " + e);
                pause(attempt);
            }
        }
        throw last;
    }

    /** Соединения с Cloudflare иногда рвутся на рукопожатии — перед повтором ждём немного. */
    private static void pause(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static HttpURLConnection open(URI uri) throws IOException {
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "LumorrixUpdater/" + VERSION);
        c.setRequestProperty("Cache-Control", "no-cache");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code + " для " + uri);
        }
        return c;
    }

    // ---------- самообновление ----------

    private void selfUpdate(Path blob, Path target) throws IOException {
        try {
            Files.copy(blob, target, StandardCopyOption.REPLACE_EXISTING);
            Log.info("Апдейтер обновлён, новая версия заработает со следующего запуска");
        } catch (IOException locked) {
            // Windows не даёт заменить загруженный jar — подменим его после выхода из игры.
            Path pending = home.resolve(SELF_NAME + ".new");
            Path helper = home.resolve("helper.jar");
            Files.copy(blob, pending, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(blob, helper, StandardCopyOption.REPLACE_EXISTING);
            String java = ProcessHandle.current().info().command().orElse("java");
            long pid = ProcessHandle.current().pid();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    new ProcessBuilder(java, "-cp", helper.toString(), SelfReplace.class.getName(),
                            String.valueOf(pid), pending.toString(), target.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(home.resolve("self-replace.log").toFile())
                            .start();
                } catch (IOException ignored) {
                }
            }, "lumorrix-updater-self-replace"));
            Log.info("Апдейтер заменится после закрытия игры (" + locked + ")");
        }
    }

    private void cleanupLeftovers() {
        try {
            Files.deleteIfExists(home.resolve("helper.jar"));
            Files.deleteIfExists(home.resolve("relaunch.jar"));
            Files.deleteIfExists(home.resolve("relaunch.args"));
            Files.deleteIfExists(home.resolve("relaunch.log"));
            Path pending = home.resolve(SELF_NAME + ".new");
            Path self = gameDir.resolve("mods").resolve(SELF_NAME);
            if (Files.isRegularFile(pending) && Files.isRegularFile(self)
                    && Files.mismatch(pending, self) == -1) {
                Files.delete(pending);
            }
        } catch (IOException ignored) {
        }
    }

    // ---------- вспомогательное ----------

    private Properties loadSettings() throws IOException {
        Path file = home.resolve("settings.properties");
        if (!Files.isRegularFile(file)) {
            Files.createDirectories(home);
            Files.writeString(file, """
                    # Настройки автообновления сборки Lumorrix
                    # Выключить обновление: enabled=false
                    enabled=true
                    # Откуда брать сборку
                    url=%s
                    # Убирать сторонние моды, которых нет в сборке (по умолчанию сохраняются)
                    prune_unknown_mods=false
                    # Исключения для строгой очистки (через запятую, * - любые символы)
                    # Пример: keep=xaeros*,journeymap*
                    keep=
                    # Клиентские моды Lumorrix (графика, миникарта и т.п.): true - ставить, false - только серверные.
                    # Пусто - спросить при запуске.
                    client_mods=
                    """.formatted(DEFAULT_URL), StandardCharsets.UTF_8);
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    /**
     * Клиентские моды (режим client) ставятся, только если игрок их выбрал. Выбор спрашиваем один раз
     * и запоминаем в settings.properties. Если их не выбрали — убираем из манифеста, и уже стоящие
     * у игрока удалятся как файлы, которых больше нет в сборке.
     */
    private void applyClientChoice(Manifest manifest, Properties settings) throws IOException {
        if (manifest.entries.stream().noneMatch(e -> e.mode().equals("client"))) {
            return;
        }
        String value = settings.getProperty("client_mods", "").strip();
        boolean clientMods;
        if (value.isEmpty()) {
            Boolean answer = Dialogs.askClientMods();
            if (answer == null) {
                // Окно показать нельзя (нет графики) — ставим как раньше и спросим в следующий раз.
                Log.info("Выбор клиентских модов не сделан — ставлю полную сборку");
                clientMods = true;
            } else {
                clientMods = answer;
                saveSetting("client_mods", String.valueOf(clientMods));
                Log.info("Игрок выбрал: " + (clientMods ? "серверные + клиентские моды" : "только серверные моды"));
            }
        } else {
            clientMods = Boolean.parseBoolean(value);
        }
        if (clientMods) {
            skipRemovedClientMods(manifest);
        }
        manifest.entries.removeIf(e -> e.mode().equals("client") && !clientMods);
        manifest.entries.replaceAll(e -> e.mode().equals("client")
                ? new Manifest.Entry(e.sha1(), e.size(), "sync", e.path()) : e);
    }

    /**
     * Клиентский мод, который мы ставили, а его файла больше нет, — игрок удалил его сам. Запоминаем
     * (по modId, чтобы не вернуть и новую версию) в client-removed.txt и больше не ставим.
     */
    private void skipRemovedClientMods(Manifest manifest) throws IOException {
        Path file = home.resolve("client-removed.txt");
        Set<String> removed = new java.util.LinkedHashSet<>();
        if (Files.isRegularFile(file)) {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                l = l.strip();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    removed.add(l);
                }
            }
        }
        State st = State.load(home.resolve("state.tsv"));
        boolean changed = false;
        for (Iterator<Manifest.Entry> it = manifest.entries.iterator(); it.hasNext(); ) {
            Manifest.Entry e = it.next();
            if (!e.mode().equals("client")) {
                continue;
            }
            String key = manifest.clientIds.getOrDefault(e.path(), e.path());
            Path target = resolve(e.path());
            // Игрок вернул мод (например, снова включил его в Prism) — снова ставим и обновляем его,
            // иначе он попал бы в «чужие» jar и уехал в removed/.
            if (removed.contains(key) && target != null && Files.isRegularFile(target)) {
                removed.remove(key);
                changed = true;
                Log.info("Клиентский мод " + e.path() + " возвращён игроком — снова обновляю");
            }
            if (!removed.contains(key) && target != null && !Files.exists(target)
                    && st.installed.containsKey(e.path())) {
                removed.add(key);
                changed = true;
                Log.info("Клиентский мод " + e.path() + " удалён игроком — больше не ставлю");
            }
            if (removed.contains(key)) {
                it.remove();
            }
        }
        if (changed) {
            List<String> out = new ArrayList<>();
            out.add("# Клиентские моды, которые вы удалили, — апдейтер их больше не ставит.");
            out.add("# Чтобы вернуть мод, удалите его строку (или весь файл) и перезапустите игру.");
            out.addAll(removed);
            Files.write(file, out, StandardCharsets.UTF_8);
        }
    }

    /** Выбор из установщика: записать client_mods заранее, чтобы игра не спрашивала ещё раз. */
    static void saveClientChoice(Path gameDir, boolean clientMods) throws IOException {
        Updater u = new Updater(gameDir.toAbsolutePath().normalize());
        u.loadSettings();
        u.saveSetting("client_mods", String.valueOf(clientMods));
    }

    /** Игрок закрыл окно выбора модов — установку (и запуск игры) отменяем. */
    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("Игрок закрыл окно выбора модов");
        }
    }

    /** Меняет одну строку key=value в settings.properties, не трогая комментарии и остальные настройки. */
    private void saveSetting(String key, String value) throws IOException {
        Path file = home.resolve("settings.properties");
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).strip();
            if (l.startsWith(key + "=") || l.startsWith(key + " ")) {
                lines.set(i, key + "=" + value);
                found = true;
            }
        }
        if (!found) {
            lines.add("# Клиентские моды Lumorrix (графика, миникарта и т.п.): true - ставить, false - только серверные.");
            lines.add(key + "=" + value);
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** Путь из манифеста → файл внутри папки игры; null, если путь пытается выйти за её пределы. */
    private Path resolve(String rel) {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains(":") || rel.contains("..")) {
            return null;
        }
        Path p = gameDir.resolve(rel).normalize();
        return p.startsWith(gameDir) && !p.equals(gameDir) && !p.startsWith(home) ? p : null;
    }

    private String key(Path f) {
        return gameDir.relativize(f).toString().replace('\\', '/');
    }

    private String hash(String rel, Path f) throws IOException {
        BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class);
        long mtime = a.lastModifiedTime().toMillis();
        State.Cached c = state.cache.get(rel);
        if (c != null && c.size() == a.size() && c.mtime() == mtime) {
            return c.sha1();
        }
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            String sha = HexFormat.of().formatHex(md.digest());
            state.cache.put(rel, new State.Cached(a.size(), mtime, sha));
            return sha;
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static boolean isServiceLayerJar(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return SERVICE_LAYER.stream().anyMatch(s -> z.getEntry("META-INF/services/" + s) != null);
        } catch (IOException e) {
            return false;
        }
    }

    private static List<Pattern> globs(String csv) {
        List<Pattern> out = new ArrayList<>();
        for (String g : csv.split(",")) {
            g = g.strip();
            if (g.isEmpty()) {
                continue;
            }
            StringBuilder re = new StringBuilder();
            for (char ch : g.toCharArray()) {
                re.append(switch (ch) {
                    case '*' -> ".*";
                    case '?' -> ".";
                    default -> Pattern.quote(String.valueOf(ch));
                });
            }
            if (!g.contains(".")) {
                re.append(".*");
            }
            out.add(Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE));
        }
        return out;
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f МБ", bytes / 1024.0 / 1024.0);
    }

    /** Шрифт раннего окна NeoForge — только латиница, кириллица там выводится как «????». */
    private static String mbEn(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static void deleteTree(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
