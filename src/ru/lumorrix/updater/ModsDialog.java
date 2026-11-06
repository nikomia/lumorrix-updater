package ru.lumorrix.updater;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.Arrays;

import static ru.lumorrix.updater.Installer.ACCENT_2;
import static ru.lumorrix.updater.Installer.CARD;
import static ru.lumorrix.updater.Installer.CARD_HOVER;
import static ru.lumorrix.updater.Installer.LINE;
import static ru.lumorrix.updater.Installer.TEXT;
import static ru.lumorrix.updater.Installer.TEXT_DIM;
import static ru.lumorrix.updater.Installer.TEXT_FAINT;
import static ru.lumorrix.updater.Installer.accent;
import static ru.lumorrix.updater.Installer.font;
import static ru.lumorrix.updater.Installer.image;
import static ru.lumorrix.updater.Installer.scale;
import static ru.lumorrix.updater.Installer.smooth;

/**
 * Окно первого запуска «Какие моды установить?» — в стиле установщика: те же фон, карточки и кнопки.
 */
final class ModsDialog {
    private static final int W = 520;

    private final JDialog dialog = new JDialog((java.awt.Frame) null, "Lumorrix — установка сборки", true);
    private Option full;
    private Option selected;
    private Boolean result;

    private ModsDialog() {
    }

    /** true — серверные + клиентские, false — только серверные, null — окно закрыли. */
    static Boolean ask() throws Exception {
        Boolean[] out = new Boolean[1];
        Runnable show = () -> out[0] = new ModsDialog().show();
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeAndWait(show);
        }
        return out[0];
    }

    private Boolean show() {
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(build());
        image("icon.png").ifPresent(icon -> dialog.setIconImages(Arrays.asList(
                scale(icon, 16, 16), scale(icon, 32, 32), scale(icon, 48, 48), scale(icon, 64, 64))));
        dialog.pack();
        dialog.setResizable(false);
        dialog.setAlwaysOnTop(true);
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        return result;
    }

    private JComponent build() {
        JPanel root = new Installer.Backdrop();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(26, 28, 22, 28));

        image("logo.png").ifPresent(logo -> {
            int w = 300;
            int h = logo.getHeight(null) * w / logo.getWidth(null);
            root.add(left(new Installer.ImageBox(scale(logo, w, h))));
        });
        root.add(Box.createVerticalStrut(10));
        Installer.Label sub = new Installer.Label("Первый запуск  ·  выбор модов", font(12.5f, false), TEXT_FAINT);
        sub.putClientProperty("center", Boolean.TRUE);
        root.add(left(sub));
        root.add(Box.createVerticalStrut(22));

        root.add(left(new Installer.Label("КАКИЕ МОДЫ УСТАНОВИТЬ", font(11f, true), TEXT_FAINT)));
        root.add(Box.createVerticalStrut(8));
        full = new Option("Серверные + клиентские", "РЕКОМЕНДУЕТСЯ",
                "Всё для игры на сервере и улучшения для вас",
                "Sodium, миникарта, динамический свет, модели мобов, анимации рук");
        Option server = new Option("Только серверные", null,
                "Минимум для игры на сервере Lumorrix",
                "Если у вас свои моды на графику или карту");
        selected = full;
        root.add(left(full));
        root.add(Box.createVerticalStrut(8));
        root.add(left(server));
        root.add(Box.createVerticalStrut(14));

        root.add(left(new Installer.Wrap("Серверные моды ставятся всегда — без них на сервер не зайти. "
                + "Выбор можно поменять потом в config/lumorrix/updater/settings.properties (строка client_mods).",
                font(12f, false), TEXT_FAINT, W - 56)));
        root.add(Box.createVerticalStrut(18));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        Installer.Button go = new Installer.Button("Продолжить", true);
        go.onClick(() -> {
            result = selected == full;
            dialog.dispose();
        });
        buttons.add(go);
        root.add(left(buttons));
        return root;
    }

    private static <T extends JComponent> T left(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    /** Карточка варианта: радиокружок, название, необязательный значок, две строки пояснения. */
    private final class Option extends Installer.Clickable {
        private final String title;
        private final String badge;
        private final String hint;
        private final String detail;
        private final Font titleFont = font(14f, true);
        private final Font badgeFont = font(9.5f, true);
        private final Font hintFont = font(12f, false);
        private final Font detailFont = font(11f, false);

        Option(String title, String badge, String hint, String detail) {
            this.title = title;
            this.badge = badge;
            this.hint = hint;
            this.detail = detail;
            onClick(() -> {
                selected = this;
                dialog.repaint();
            });
            setPreferredSize(new Dimension(W - 56, 68));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));
        }

        @Override
        protected void paintComponent(Graphics g) {
            boolean on = selected == this;
            Graphics2D g2 = smooth(g);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(hover ? CARD_HOVER : CARD);
            g2.fillRoundRect(0, 0, w - 1, h - 1, 14, 14);
            if (on) {
                g2.setPaint(accent(w, h, false));
                g2.setStroke(new BasicStroke(1.6f));
                g2.drawRoundRect(1, 1, w - 3, h - 3, 13, 13);
            } else {
                g2.setColor(LINE);
                g2.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);
            }
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
            g2.drawString(title, x, 24);
            if (badge != null) {
                int bx = x + g2.getFontMetrics().stringWidth(title) + 10;
                g2.setFont(badgeFont);
                FontMetrics fm = g2.getFontMetrics();
                int bw = fm.stringWidth(badge) + 12;
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f));
                g2.setPaint(accent(bw, 16, false));
                g2.fillRoundRect(bx, 11, bw, 16, 8, 8);
                g2.setComposite(AlphaComposite.SrcOver);
                g2.setColor(ACCENT_2.brighter());
                g2.drawString(badge, bx + 6, 11 + (16 - fm.getHeight()) / 2 + fm.getAscent());
            }
            g2.setFont(hintFont);
            g2.setColor(TEXT_DIM);
            g2.drawString(Installer.fit(g2, hint, w - x - 14), x, 42);
            g2.setFont(detailFont);
            g2.setColor(TEXT_FAINT);
            g2.drawString(Installer.fit(g2, detail, w - x - 14), x, 58);
            g2.dispose();
        }
    }
}
