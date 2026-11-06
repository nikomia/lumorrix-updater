package ru.lumorrix.updater;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Окно установщика: запускается двойным кликом по lumorrix-updater.jar.
 * Все элементы рисуются сами, в стиле сайта lumorrix.ru (тёмная тема, градиент #2a7bff → #b514e0).
 */
final class Installer {
    // Токены сайта (site/assets/css/base.css)
    static final Color APP_BG = new Color(0x0A0A10);
    static final Color CARD = new Color(0x191922);
    static final Color CARD_HOVER = new Color(0x22222D);
    static final Color INPUT = new Color(0x171720);
    static final Color TRACK = new Color(0x25252F);
    static final Color LINE = new Color(255, 255, 255, 26);
    static final Color TEXT = new Color(0xF4F4F5);
    static final Color TEXT_DIM = new Color(0xA1A1AA);
    static final Color TEXT_FAINT = new Color(0x71717A);
    static final Color ACCENT_1 = new Color(0x2A7BFF);
    static final Color ACCENT_2 = new Color(0xB514E0);
    static final Color ACCENT_H1 = new Color(0x5B9BFF);
    static final Color ACCENT_H2 = new Color(0xD946EF);
    static final Color OK = new Color(0x34D399);
    static final Color DANGER = new Color(0xEF4444);
    static final int WIN_W = 520;

    private final List<Install.Launcher> launchers = Install.detect();
    private final List<Choice> choices = new ArrayList<>();
    private final List<Pill> ramPills = new ArrayList<>();
    private final Toggle predownload = new Toggle("Сразу скачать сборку (~540 МБ)",
            "чтобы первый запуск игры был быстрым");
    private final Bar bar = new Bar();
    private final Label status = new Label(" ", font(12.5f, false), TEXT_DIM);
    private final Button install = new Button("Установить", true);
    private final Button close = new Button("Закрыть", false);
    private final StringBuilder details = new StringBuilder();
    private final JPanel progressBox = new JPanel();
    private Choice selected;
    private int ramGb;
    private boolean busy;
    private JFrame frame;

    /** java -jar lumorrix-updater.jar [--update <папка игры>] */
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--update")) {
            Updater u = new Updater(Path.of(args[1]).toAbsolutePath().normalize());
            u.run();
            System.exit(u.ok() ? 0 : 1);
            return;
        }
        // Свои элементы рисуются сами; системный стиль нужен только окну выбора папки.
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> new Installer().show());
    }

    private void show() {
        frame = new JFrame("Lumorrix — установка сборки");
        // Закрытие окна завершает и загрузку: иначе её потоки держат процесс и созданную папку.
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setContentPane(buildContent());
        image("icon.png").ifPresent(icon -> frame.setIconImages(Arrays.asList(
                scale(icon, 16, 16), scale(icon, 32, 32), scale(icon, 48, 48), scale(icon, 64, 64))));
        frame.getContentPane().setBackground(APP_BG);
        frame.pack();
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    JComponent buildContent() {
        JPanel root = new Backdrop();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(26, 28, 22, 28));

        image("logo.png").ifPresent(logo -> {
            int w = 340;
            int h = logo.getHeight(null) * w / logo.getWidth(null);
            JComponent img = new ImageBox(scale(logo, w, h));
            root.add(center(img));
        });
        root.add(Box.createVerticalStrut(10));
        root.add(center(new Label("Установщик сборки  ·  Minecraft " + Install.MC_VERSION + "  ·  NeoForge "
                + Install.NEOFORGE, font(12.5f, false), TEXT_FAINT)));
        root.add(Box.createVerticalStrut(22));

        root.add(left(new Label("КУДА УСТАНОВИТЬ", font(11f, true), TEXT_FAINT)));
        root.add(Box.createVerticalStrut(8));
        if (launchers.isEmpty()) {
            root.add(left(new Note("Не нашёл ни одного знакомого лаунчера.",
                    "Выберите папку игры вручную или положите этот файл в папку mods инстанса с NeoForge "
                            + Install.NEOFORGE + " (Minecraft " + Install.MC_VERSION + ").")));
            root.add(Box.createVerticalStrut(8));
        }
        for (Install.Launcher l : launchers) {
            Choice c = new Choice(l);
            choices.add(c);
            root.add(left(c));
            root.add(Box.createVerticalStrut(8));
        }
        Choice custom = new Choice(new Install.Launcher(Install.Kind.CUSTOM, "Своя папка", null,
                "Положит апдейтер и сборку в выбранную папку игры"));
        custom.onClick(() -> {
            if (busy) {
                return;
            }
            if (selected == custom || custom.launcher.dir() == null) {
                pickFolder(custom);
            }
            if (custom.launcher.dir() != null) {
                select(custom);
            }
        });
        choices.add(custom);
        root.add(left(custom));
        root.add(Box.createVerticalStrut(8));

        root.add(Box.createVerticalStrut(10));
        root.add(left(new Label("ПАМЯТЬ ДЛЯ ИГРЫ", font(11f, true), TEXT_FAINT)));
        root.add(Box.createVerticalStrut(8));
        JPanel pills = row(FlowLayout.LEFT, 6);
        int def = defaultRam();
        int max = maxRam();
        for (int gb : new int[]{4, 6, 8, 10, 12, 16}) {
            Pill p = new Pill(gb);
            // Больше, чем есть в компьютере (минус ~2 ГБ на Windows), выбрать нельзя: игра не запустится
            // или всё будет тормозить. 4 ГБ — минимум сборки, его оставляем всегда.
            if (gb > max && gb != 4) {
                p.setEnabled(false);
                p.setToolTipText("В компьютере всего " + totalRamGb() + " ГБ памяти");
            }
            ramPills.add(p);
            pills.add(p);
        }
        root.add(left(pills));
        selectRam(def);

        root.add(Box.createVerticalStrut(16));
        root.add(left(predownload));

        // Прогресс и статус появляются только во время установки.
        progressBox.setOpaque(false);
        progressBox.setLayout(new BoxLayout(progressBox, BoxLayout.Y_AXIS));
        progressBox.add(Box.createVerticalStrut(18));
        progressBox.add(left(bar));
        progressBox.add(Box.createVerticalStrut(8));
        progressBox.add(left(status));
        progressBox.setVisible(false);
        root.add(left(progressBox));
        root.add(Box.createVerticalStrut(22));

        JPanel buttons = row(FlowLayout.RIGHT, 8);
        close.onClick(() -> System.exit(0));
        install.onClick(this::start);
        buttons.add(close);
        buttons.add(install);
        root.add(left(buttons));

        if (!launchers.isEmpty()) {
            select(choices.get(0));
        } else {
            install.setEnabled(false);
            predownload.setEnabled(false);
        }
        return root;
    }

    // ---------- состояние ----------

    private void select(Choice c) {
        if (busy) {
            return;
        }
        selected = c;
        choices.forEach(Component::repaint);
        Install.Launcher l = c.launcher;
        boolean can = l.canPredownload();
        install.setEnabled(true);
        predownload.setEnabled(can);
        predownload.setSub(can ? "чтобы первый запуск игры был быстрым"
                : "для " + l.name() + " сборка скачается при первом запуске игры");
    }

    /** Выбор папки игры для варианта «Своя папка» (системный диалог Windows). */
    private void pickFolder(Choice c) {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser(
                c.launcher.dir() != null ? c.launcher.dir().toFile() : Install.minecraftDir().getParent().toFile());
        fc.setDialogTitle("Папка игры для Lumorrix");
        fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        fc.setAcceptAllFileFilterUsed(false);
        if (fc.showDialog(frame, "Выбрать") == javax.swing.JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
            Path dir = Install.gameDirFor(fc.getSelectedFile().toPath().toAbsolutePath().normalize());
            c.launcher = new Install.Launcher(Install.Kind.CUSTOM, "Своя папка", dir, c.launcher.hint());
            c.repaint();
        }
    }

    private void selectRam(int gb) {
        if (busy) {
            return;
        }
        ramGb = gb;
        ramPills.forEach(Component::repaint);
    }

    private void start() {
        if (selected == null || busy) {
            return;
        }
        Install.Launcher l = selected.launcher;
        // Выбор модов спрашиваем до установки: закрыли окно — ничего не ставим. Для лаунчеров, где папка
        // игры заранее не известна (mrpack, CurseForge), его задаст сама игра при первом запуске.
        Boolean clientMods = null;
        if (l.canPredownload()) {
            try {
                clientMods = ModsDialog.ask();
            } catch (Exception e) {
                clientMods = Boolean.TRUE;
            }
            if (clientMods == null) {
                return;
            }
        }
        Boolean chosenMods = clientMods;
        int gb = ramGb;
        boolean download = predownload.isEnabled() && predownload.on;
        setBusy(true);
        progressBox.setVisible(true);
        bar.setIndeterminate(true);
        frame.pack();
        Thread t = new Thread(() -> {
            try {
                Install.Result r = new Install(this::log).install(l, gb);
                if (chosenMods != null && r.gameDir() != null) {
                    Updater.saveClientChoice(r.gameDir(), chosenMods);
                }
                String extra = "";
                if (download && r.gameDir() != null) {
                    log("Скачиваю сборку…");
                    Progress.listener = (text, fraction) -> SwingUtilities.invokeLater(() -> {
                        status.setText(text);
                        if (fraction >= 0) {
                            bar.setIndeterminate(false);
                            bar.setValue(fraction);
                        }
                    });
                    Updater u = new Updater(r.gameDir());
                    u.run();
                    Progress.listener = null;
                    if (!u.ok()) {
                        extra = "\n\nСборку сейчас скачать не удалось — она докачается сама при запуске игры.";
                    }
                }
                String msg = r.message() + extra;
                SwingUtilities.invokeLater(() -> {
                    bar.setIndeterminate(false);
                    bar.setValue(1);
                    status.setColor(OK);
                    status.setText("Готово");
                    setBusy(false);
                    dialog("Lumorrix установлен", msg, false);
                });
            } catch (Throwable ex) {
                Progress.listener = null;
                log("Ошибка: " + ex);
                SwingUtilities.invokeLater(() -> {
                    bar.setIndeterminate(false);
                    status.setColor(DANGER);
                    status.setText("Не получилось установить");
                    setBusy(false);
                    dialog("Не получилось установить", String.valueOf(ex.getMessage()), true);
                });
            }
        }, "lumorrix-installer");
        t.setDaemon(true);
        t.start();
    }

    private void setBusy(boolean b) {
        busy = b;
        install.setEnabled(!b && selected != null);
        close.setEnabled(!b);
        predownload.setLocked(b);
        status.setColor(TEXT_DIM);
        choices.forEach(Component::repaint);
        ramPills.forEach(Component::repaint);
    }

    private void log(String line) {
        details.append(line).append('\n');
        SwingUtilities.invokeLater(() -> status.setText(line.length() > 78 ? line.substring(0, 75) + "…" : line));
    }

    /** Половина ОЗУ, но в пределах 4–8 ГБ: сборке из 158 модов нужно ~6. */
    private static int defaultRam() {
        int total = totalRamGb();
        return total <= 0 ? 6 : total >= 24 ? 8 : total >= 12 ? 6 : 4;
    }

    /** Сколько можно отдать игре: всё ОЗУ минус 2 ГБ системе. Не удалось узнать — без ограничений. */
    private static int maxRam() {
        int total = totalRamGb();
        return total <= 0 ? Integer.MAX_VALUE : total - 2;
    }

    /** ОЗУ компьютера в ГБ с округлением (8 ГБ Windows показывает как ~7,9); 0 — не удалось узнать. */
    private static int totalRamGb() {
        try {
            long bytes = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                    .getTotalMemorySize();
            return (int) Math.round(bytes / (1024.0 * 1024 * 1024));
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------- диалог ----------

    private void dialog(String title, String text, boolean error) {
        JDialog d = new JDialog(frame, title, true);
        JPanel p = new Backdrop();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(22, 24, 18, 24));
        p.add(left(new Label(title, font(17f, true), error ? DANGER : TEXT)));
        p.add(Box.createVerticalStrut(10));
        for (String line : text.split("\n")) {
            p.add(left(new Wrap(line.isEmpty() ? " " : line, font(13f, false), TEXT_DIM, 420)));
        }
        if (error && details.length() > 0) {
            p.add(Box.createVerticalStrut(10));
            String[] lines = details.toString().split("\n");
            for (int i = Math.max(0, lines.length - 6); i < lines.length; i++) {
                p.add(left(new Wrap(lines[i], new Font(Font.MONOSPACED, Font.PLAIN, 11), TEXT_FAINT, 420)));
            }
        }
        p.add(Box.createVerticalStrut(18));
        JPanel b = row(FlowLayout.RIGHT, 0);
        Button ok = new Button("Понятно", true);
        ok.onClick(d::dispose);
        b.add(ok);
        p.add(left(b));
        d.setContentPane(p);
        d.pack();
        d.setResizable(false);
        d.setLocationRelativeTo(frame);
        d.setVisible(true);
    }

    // ---------- компоненты ----------

    static Font font(float size, boolean bold) {
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        List<String> have = Arrays.asList(families);
        String family = have.contains("Inter") ? "Inter" : have.contains("Segoe UI") ? "Segoe UI" : Font.SANS_SERIF;
        if (bold && have.contains("Segoe UI Semibold") && family.equals("Segoe UI")) {
            return new Font("Segoe UI Semibold", Font.PLAIN, 1).deriveFont(size);
        }
        return new Font(family, bold ? Font.BOLD : Font.PLAIN, 1).deriveFont(size);
    }

    static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g2;
    }

    static Paint accent(int w, int h, boolean hover) {
        return new GradientPaint(0, 0, hover ? ACCENT_H1 : ACCENT_1, w, h, hover ? ACCENT_H2 : ACCENT_2);
    }

    private static <T extends JComponent> T left(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    /** Центрируем рисованием внутри компонента: смешивать выравнивания в BoxLayout нельзя — колонка поедет. */
    private static <T extends JComponent> T center(T c) {
        c.putClientProperty("center", Boolean.TRUE);
        return left(c);
    }

    private static JPanel row(int align, int gap) {
        JPanel p = new JPanel(new FlowLayout(align, gap, 0));
        p.setOpaque(false);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        return p;
    }

    /** Фон окна: тёмный, с мягким фиолетовым свечением сверху, как на сайте. */
    static final class Backdrop extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            g2.setColor(APP_BG);
            g2.fillRect(0, 0, getWidth(), getHeight());
            float r = Math.max(getWidth(), 1) * 0.75f;
            g2.setPaint(new RadialGradientPaint(new Point2D.Float(getWidth() / 2f, -r * 0.35f), r,
                    new float[]{0f, 1f}, new Color[]{new Color(120, 60, 255, 46), new Color(120, 60, 255, 0)}));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }

    static final class Label extends JComponent {
        private String text;
        private final Font f;
        private Color color;

        Label(String text, Font f, Color color) {
            this.text = text;
            this.f = f;
            this.color = color;
        }

        void setText(String t) {
            text = t;
            revalidate();
            repaint();
        }

        void setColor(Color c) {
            color = c;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(f);
            return new Dimension(Math.max(fm.stringWidth(text), 1), fm.getHeight());
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            g2.setFont(f);
            g2.setColor(color);
            FontMetrics fm = g2.getFontMetrics();
            int x = Boolean.TRUE.equals(getClientProperty("center")) ? (getWidth() - fm.stringWidth(text)) / 2 : 0;
            g2.drawString(text, x, fm.getAscent());
            g2.dispose();
        }
    }

    /** Текст с переносом по словам. */
    static final class Wrap extends JComponent {
        private final List<String> lines = new ArrayList<>();
        private final Font f;
        private final Color color;

        Wrap(String text, Font f, Color color, int width) {
            this.f = f;
            this.color = color;
            FontMetrics fm = getFontMetrics(f);
            StringBuilder cur = new StringBuilder();
            for (String word : text.split(" ")) {
                String next = cur.length() == 0 ? word : cur + " " + word;
                if (fm.stringWidth(next) > width && cur.length() > 0) {
                    lines.add(cur.toString());
                    cur = new StringBuilder(word);
                } else {
                    cur = new StringBuilder(next);
                }
            }
            lines.add(cur.toString());
            setPreferredSize(new Dimension(width, fm.getHeight() * lines.size()));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, fm.getHeight() * lines.size()));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            g2.setFont(f);
            g2.setColor(color);
            FontMetrics fm = g2.getFontMetrics();
            for (int i = 0; i < lines.size(); i++) {
                g2.drawString(lines.get(i), 0, fm.getAscent() + i * fm.getHeight());
            }
            g2.dispose();
        }
    }

    static final class ImageBox extends JComponent {
        private final Image img;

        ImageBox(Image img) {
            this.img = img;
            Dimension d = new Dimension(img.getWidth(null), img.getHeight(null));
            setPreferredSize(d);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, d.height));
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.drawImage(img, (getWidth() - img.getWidth(null)) / 2, 0, null);
        }
    }

    /** Общая основа для карточек и кнопок: наведение и клик. */
    abstract static class Clickable extends JComponent {
        boolean hover;
        private Runnable action = () -> { };

        Clickable() {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (isEnabled()) {
                        action.run();
                    }
                }
            });
        }

        void onClick(Runnable r) {
            action = r;
        }

        @Override
        public void setEnabled(boolean e) {
            super.setEnabled(e);
            setCursor(Cursor.getPredefinedCursor(e ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            repaint();
        }
    }

    /** Карточка лаунчера: радиокружок, название, подсказка, путь. */
    final class Choice extends Clickable {
        Install.Launcher launcher;
        private final Font titleFont = font(14f, true);
        private final Font hintFont = font(12f, false);
        private final Font pathFont = font(11f, false);

        Choice(Install.Launcher l) {
            this.launcher = l;
            onClick(() -> select(this));
            setPreferredSize(new Dimension(WIN_W - 56, 68));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));
        }

        @Override
        protected void paintComponent(Graphics g) {
            boolean on = selected == this;
            Graphics2D g2 = smooth(g);
            if (busy && !on) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
            }
            int w = getWidth();
            int h = getHeight();
            g2.setColor(hover && !busy ? CARD_HOVER : CARD);
            g2.fillRoundRect(0, 0, w - 1, h - 1, 14, 14);
            if (on) {
                g2.setPaint(accent(w, h, false));
                g2.setStroke(new BasicStroke(1.6f));
                g2.drawRoundRect(1, 1, w - 3, h - 3, 13, 13);
            } else {
                g2.setColor(LINE);
                g2.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);
            }
            // радиокружок
            int cx = 16;
            int cy = h / 2 - 9;
            if (on) {
                g2.setPaint(accent(18, 18, false));
                g2.fillOval(cx, cy, 18, 18);
                g2.setColor(Color.WHITE);
                g2.fillOval(cx + 6, cy + 6, 6, 6);
            } else {
                g2.setColor(new Color(255, 255, 255, 60));
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawOval(cx, cy, 18, 18);
            }
            int x = 48;
            g2.setFont(titleFont);
            g2.setColor(TEXT);
            g2.drawString(launcher.name(), x, 24);
            g2.setFont(hintFont);
            g2.setColor(TEXT_DIM);
            g2.drawString(fit(g2, launcher.hint(), w - x - 14), x, 42);
            g2.setFont(pathFont);
            g2.setColor(TEXT_FAINT);
            String path = launcher.dir() == null ? "Нажмите, чтобы выбрать папку…"
                    : launcher.dir() + (on && launcher.kind() == Install.Kind.CUSTOM ? "   ·   нажмите ещё раз, чтобы сменить" : "");
            g2.drawString(fit(g2, path, w - x - 14), x, 58);
            g2.dispose();
        }
    }

    static String fit(Graphics2D g2, String s, int width) {
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(s) <= width) {
            return s;
        }
        while (s.length() > 1 && fm.stringWidth(s + "…") > width) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    /** Карточка-заметка, когда лаунчеров не нашлось. */
    static final class Note extends JPanel {
        Note(String title, String text) {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
            add(left(new Label(title, font(14f, true), TEXT)));
            add(Box.createVerticalStrut(4));
            add(left(new Wrap(text, font(12.5f, false), TEXT_DIM, WIN_W - 90)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            g2.setColor(CARD);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.setColor(LINE);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.dispose();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** «Таблетка» выбора памяти. */
    final class Pill extends Clickable {
        final int gb;
        private final Font f = font(13f, true);

        Pill(int gb) {
            this.gb = gb;
            onClick(() -> selectRam(gb));
            setPreferredSize(new Dimension(70, 34));
        }

        @Override
        protected void paintComponent(Graphics g) {
            boolean on = ramGb == gb;
            Graphics2D g2 = smooth(g);
            if (!isEnabled()) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
            } else if (busy && !on) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
            }
            int w = getWidth();
            int h = getHeight();
            if (on) {
                g2.setPaint(accent(w, h, hover));
                g2.fillRoundRect(0, 0, w, h, 10, 10);
            } else {
                g2.setColor(hover && !busy ? CARD_HOVER : INPUT);
                g2.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);
                g2.setColor(LINE);
                g2.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);
            }
            g2.setFont(f);
            g2.setColor(on ? Color.WHITE : TEXT_DIM);
            String s = gb + " ГБ";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(s, (w - fm.stringWidth(s)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** Переключатель с подписью и пояснением. */
    static final class Toggle extends Clickable {
        boolean on = true;
        private boolean locked;
        private final String title;
        private String sub;
        private final Font titleFont = font(13.5f, true);
        private final Font subFont = font(12f, false);

        Toggle(String title, String sub) {
            this.title = title;
            this.sub = sub;
            onClick(() -> {
                if (!locked) {
                    on = !on;
                    repaint();
                }
            });
            setPreferredSize(new Dimension(WIN_W - 56, 38));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        }

        void setSub(String s) {
            sub = s;
            repaint();
        }

        void setLocked(boolean l) {
            locked = l;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            boolean active = isEnabled() && on;
            if (!isEnabled() || locked) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
            }
            int tw = 38;
            int th = 22;
            int ty = 2;
            if (active) {
                g2.setPaint(accent(tw, th, hover));
                g2.fillRoundRect(0, ty, tw, th, th, th);
            } else {
                g2.setColor(TRACK);
                g2.fillRoundRect(0, ty, tw, th, th, th);
            }
            g2.setColor(Color.WHITE);
            int knob = th - 6;
            g2.fillOval(active ? tw - knob - 3 : 3, ty + 3, knob, knob);
            g2.setFont(titleFont);
            g2.setColor(TEXT);
            g2.drawString(title, tw + 12, 16);
            g2.setFont(subFont);
            g2.setColor(TEXT_FAINT);
            g2.drawString(sub, tw + 12, 33);
            g2.dispose();
        }
    }

    /** Тонкая полоска прогресса с градиентом; бегущий отрезок, пока доля неизвестна. */
    static final class Bar extends JComponent {
        private double value;
        private boolean indeterminate;
        private final javax.swing.Timer timer = new javax.swing.Timer(16, e -> repaint());

        Bar() {
            setPreferredSize(new Dimension(WIN_W - 56, 6));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        }

        void setValue(double v) {
            value = Math.max(0, Math.min(1, v));
            repaint();
        }

        void setIndeterminate(boolean b) {
            indeterminate = b;
            if (b) {
                timer.start();
            } else {
                timer.stop();
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(TRACK);
            g2.fillRoundRect(0, 0, w, h, h, h);
            g2.setPaint(accent(w, 0, false));
            if (indeterminate) {
                int seg = w / 4;
                int x = (int) ((System.currentTimeMillis() / 3) % (w + seg)) - seg;
                g2.setClip(0, 0, w, h);
                g2.fillRoundRect(x, 0, seg, h, h, h);
            } else if (value > 0) {
                g2.fillRoundRect(0, 0, Math.max(h, (int) (w * value)), h, h, h);
            }
            g2.dispose();
        }
    }

    /** Кнопки как на сайте: .btn-primary (градиент) и .btn-secondary. */
    static final class Button extends Clickable {
        private final String text;
        private final boolean primary;
        private final Font f;

        Button(String text, boolean primary) {
            this.text = text;
            this.primary = primary;
            this.f = font(primary ? 14f : 13f, true);
            FontMetrics fm = getFontMetrics(f);
            setPreferredSize(new Dimension(fm.stringWidth(text) + (primary ? 44 : 32), primary ? 40 : 36));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            if (!isEnabled()) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
            }
            int w = getWidth();
            int h = getHeight();
            int y = 0;
            if (primary) {
                g2.setPaint(accent(w, h, hover && isEnabled()));
                g2.fillRoundRect(0, y, w, h, 12, 12);
                g2.setColor(new Color(255, 255, 255, 60));
                g2.drawLine(8, y + 1, w - 8, y + 1);
            } else {
                g2.setColor(hover && isEnabled() ? CARD_HOVER : INPUT);
                g2.fillRoundRect(0, y, w - 1, h - 1, 10, 10);
                g2.setColor(hover && isEnabled() ? new Color(255, 255, 255, 46) : LINE);
                g2.drawRoundRect(0, y, w - 1, h - 1, 10, 10);
            }
            g2.setFont(f);
            g2.setColor(primary ? Color.WHITE : hover && isEnabled() ? TEXT : TEXT_DIM);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, (w - fm.stringWidth(text)) / 2, y + (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    // ---------- картинки ----------

    static Optional<Image> image(String name) {
        try (InputStream in = Install.resource(name)) {
            return Optional.of(ImageIO.read(in));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Качественное уменьшение: несколько шагов по половине, чтобы пиксель-арт не рассыпался. */
    static Image scale(Image src, int w, int h) {
        BufferedImage cur = toBuffered(src);
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = draw(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return draw(cur, w, h);
    }

    private static BufferedImage toBuffered(Image src) {
        if (src instanceof BufferedImage b) {
            return b;
        }
        return draw(src, src.getWidth(null), src.getHeight(null));
    }

    private static BufferedImage draw(Image src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
