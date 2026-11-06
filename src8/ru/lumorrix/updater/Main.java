package ru.lumorrix.updater;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;

/**
 * Точка входа при двойном клике по jar. Собирается под Java 8, чтобы на старой Java
 * игрок увидел понятное сообщение, а не молчаливую ошибку версии классов.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String v = System.getProperty("java.specification.version", "0");
        int major = v.startsWith("1.") ? Integer.parseInt(v.substring(2)) : Integer.parseInt(v.split("\\.")[0]);
        if (major < 17) {
            String msg = "Для установщика Lumorrix нужна Java 17 или новее (сейчас " + v + ").\n\n"
                    + "Можно и без установщика: поставьте NeoForge 21.1.265 (Minecraft 1.21.1)\n"
                    + "и положите lumorrix-updater.jar в папку mods — сборка скачается при запуске.";
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println(msg);
            } else {
                JOptionPane.showMessageDialog(null, msg, "Lumorrix", JOptionPane.WARNING_MESSAGE);
            }
            return;
        }
        Class.forName("ru.lumorrix.updater.Installer").getMethod("main", String[].class).invoke(null, (Object) args);
    }
}
