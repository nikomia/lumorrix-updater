package ru.lumorrix.updater;

import java.lang.reflect.Method;

/**
 * Полоска прогресса в раннем окне загрузки NeoForge. Его шрифт умеет только латиницу,
 * поэтому у каждого текста два варианта: русский (установщик, лог) и английский (окно игры). Классы FML берём через рефлексию:
 * если окна нет (сервер, другой загрузчик), просто молчим.
 */
final class Progress {
    /** Слушатель для окна установщика (вне игры): текст и доля 0..1; для message — доля -1. */
    interface Listener {
        void progress(String text, double fraction);
    }

    static volatile Listener listener;
    private static final int STEPS = 1000;
    private final Object meter;
    private final Method setAbsolute;
    private final Method label;
    private final Method popBar;

    private Progress(Object meter, Method setAbsolute, Method label, Method popBar) {
        this.meter = meter;
        this.setAbsolute = setAbsolute;
        this.label = label;
        this.popBar = popBar;
    }

    static Progress open(String title, String titleEn) {
        try {
            ClassLoader cl = Progress.class.getClassLoader();
            Class<?> manager = Class.forName("net.neoforged.fml.loading.progress.StartupNotificationManager", true, cl);
            Class<?> meterClass = Class.forName("net.neoforged.fml.loading.progress.ProgressMeter", true, cl);
            Object meter = manager.getMethod("prependProgressBar", String.class, int.class).invoke(null, titleEn, STEPS);
            return new Progress(meter,
                    meterClass.getMethod("setAbsolute", int.class),
                    meterClass.getMethod("label", String.class),
                    manager.getMethod("popBar", meterClass));
        } catch (Throwable t) {
            return new Progress(null, null, null, null);
        }
    }

    /** Сообщение в списке сообщений раннего окна. */
    static void message(String text, String textEn) {
        Listener l = listener;
        if (l != null) {
            l.progress(text, -1);
            return;
        }
        try {
            Class<?> manager = Class.forName("net.neoforged.fml.loading.progress.StartupNotificationManager",
                    true, Progress.class.getClassLoader());
            manager.getMethod("addModMessage", String.class).invoke(null, textEn);
        } catch (Throwable ignored) {
        }
    }

    void update(String text, String textEn, double fraction) {
        Listener l = listener;
        if (l != null) {
            l.progress(text, fraction);
        }
        if (meter == null) {
            return;
        }
        try {
            label.invoke(meter, textEn);
            setAbsolute.invoke(meter, (int) Math.max(0, Math.min(STEPS, Math.round(fraction * STEPS))));
        } catch (Throwable ignored) {
        }
    }

    void close() {
        if (meter == null) {
            return;
        }
        try {
            popBar.invoke(null, meter);
        } catch (Throwable ignored) {
        }
    }
}
