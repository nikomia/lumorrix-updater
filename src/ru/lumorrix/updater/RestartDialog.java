package ru.lumorrix.updater;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Arrays;

import static ru.lumorrix.updater.Installer.TEXT;
import static ru.lumorrix.updater.Installer.TEXT_DIM;
import static ru.lumorrix.updater.Installer.font;
import static ru.lumorrix.updater.Installer.image;
import static ru.lumorrix.updater.Installer.scale;

/**
 * «Нужен перезапуск» — когда обновление принесло моды, которые NeoForge грузит раньше апдейтера
 * (sodium, crash assistant). Кнопка закрывает окно, а вызывающий закрывает игру.
 */
final class RestartDialog {
    private static final int W = 460;

    private RestartDialog() {
    }

    static void show() throws Exception {
        Runnable r = RestartDialog::showNow;
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeAndWait(r);
        }
    }

    private static void showNow() {
        JDialog dialog = new JDialog((java.awt.Frame) null, "Lumorrix", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel root = new Installer.Backdrop();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(24, 28, 20, 28));
        image("logo.png").ifPresent(logo -> {
            int w = 220;
            int h = logo.getHeight(null) * w / logo.getWidth(null);
            root.add(left(new Installer.ImageBox(scale(logo, w, h))));
        });
        root.add(Box.createVerticalStrut(18));
        root.add(left(new Installer.Label("Сборка обновлена", font(17f, true), TEXT)));
        root.add(Box.createVerticalStrut(8));
        root.add(left(new Installer.Wrap("Часть новых модов заработает только после перезапуска. "
                + "Игра сейчас закроется — запустите её снова.", font(13f, false), TEXT_DIM, W - 56)));
        root.add(Box.createVerticalStrut(18));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        Installer.Button close = new Installer.Button("Закрыть игру", true);
        close.onClick(dialog::dispose);
        buttons.add(close);
        root.add(left(buttons));
        dialog.setContentPane(root);
        image("icon.png").ifPresent(icon -> dialog.setIconImages(Arrays.asList(
                scale(icon, 16, 16), scale(icon, 32, 32), scale(icon, 48, 48), scale(icon, 64, 64))));
        dialog.pack();
        dialog.setResizable(false);
        dialog.setAlwaysOnTop(true);
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    private static <T extends JComponent> T left(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }
}
