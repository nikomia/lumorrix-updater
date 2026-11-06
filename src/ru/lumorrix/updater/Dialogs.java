package ru.lumorrix.updater;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;

/** Простое окно поверх экрана загрузки. Если графики нет — только запись в лог. */
final class Dialogs {
    private Dialogs() {
    }

    static void info(String title, String text) {
        Log.info(text.replace('\n', ' '));
        try {
            JOptionPane pane = new JOptionPane(text, JOptionPane.INFORMATION_MESSAGE);
            JDialog dialog = pane.createDialog(title);
            dialog.setAlwaysOnTop(true);
            dialog.setVisible(true);
            dialog.dispose();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Первый запуск: ставить ли клиентские моды Lumorrix. true — серверные + клиентские, false — только
     * серверные, null — показать окно нельзя. Окно закрыли — {@link Updater.Cancelled}.
     */
    static Boolean askClientMods() {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        Boolean answer;
        try {
            System.setProperty("awt.useSystemAAFontSettings", "on");
            System.setProperty("swing.aatext", "true");
            answer = ModsDialog.ask();
        } catch (Throwable t) {
            Log.warn("Не удалось показать выбор клиентских модов: " + t);
            return null;
        }
        if (answer == null) {
            throw new Updater.Cancelled();
        }
        return answer;
    }
}
