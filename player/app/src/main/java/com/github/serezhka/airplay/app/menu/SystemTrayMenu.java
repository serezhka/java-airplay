package com.github.serezhka.airplay.app.menu;

import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.SystemTray;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

import java.util.Objects;

@Slf4j
public class SystemTrayMenu {

    public SystemTrayMenu(ApplicationContext context) {
        SystemTray systemTray = SystemTray.get();
        if (systemTray == null) {
            log.warn("Unable to load SystemTray!");
            return;
        }

        systemTray.installShutdownHook();
        systemTray.setImage(Objects.requireNonNull(getClass().getResource("/menu/tray_icon.png")));
        systemTray.getMenu().add(new MenuItem("Quit", event -> {
            systemTray.shutdown();
            SpringApplication.exit(context, () -> 0);
            System.exit(0);
        }));
    }
}
