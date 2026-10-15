package com.github.serezhka.airplay.player.common.app.menu;

import com.github.serezhka.airplay.client.cast.MirrorCaster;
import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;
import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.Separator;
import dorkbox.systemTray.SystemTray;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tray: Cast to… opens a Swing picker (dorkbox submenus do not refresh reliably),
 * Disconnect, Quit.
 */
@Slf4j
public class SystemTrayMenu {

    private final ApplicationContext context;
    private final MirrorCaster mirrorCaster;
    private final ExecutorService castExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "airplay-cast");
        t.setDaemon(true);
        return t;
    });

    private final SystemTray systemTray;
    private final MenuItem disconnectItem;

    public SystemTrayMenu(ApplicationContext context, MirrorCaster mirrorCaster) {
        this.context = context;
        this.mirrorCaster = mirrorCaster;
        systemTray = SystemTray.get();
        if (systemTray == null) {
            log.warn("Unable to load SystemTray!");
            disconnectItem = null;
            return;
        }

        systemTray.setImage(Objects.requireNonNull(getClass().getResource("/menu/tray_icon.png")));
        systemTray.setStatus("java-airplay");

        systemTray.getMenu().add(new MenuItem("Cast to…", e ->
                SwingUtilities.invokeLater(this::openCastDialog)));

        disconnectItem = new MenuItem("Disconnect cast", e -> castExecutor.execute(() -> {
            mirrorCaster.disconnect();
            SwingUtilities.invokeLater(this::refreshStatus);
        }));
        disconnectItem.setEnabled(false);
        systemTray.getMenu().add(disconnectItem);

        systemTray.getMenu().add(new Separator());
        systemTray.getMenu().add(new MenuItem("Quit", event -> {
            castExecutor.execute(mirrorCaster::disconnect);
            castExecutor.shutdownNow();
            systemTray.shutdown();
            SpringApplication.exit(context, () -> 0);
            System.exit(0);
        }));
    }

    private void openCastDialog() {
        JDialog dialog = new JDialog((java.awt.Frame) null, "Cast videotestsrc", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        DefaultListModel<ServiceDiscovery.Info> model = new DefaultListModel<>();
        JList<ServiceDiscovery.Info> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(8);

        JLabel status = new JLabel(" ");
        JButton refresh = new JButton("Refresh");
        JButton connect = new JButton("Connect");
        JButton cancel = new JButton("Cancel");
        connect.setEnabled(false);

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                connect.setEnabled(list.getSelectedValue() != null);
            }
        });

        Runnable doRefresh = () -> {
            refresh.setEnabled(false);
            connect.setEnabled(false);
            status.setText("Searching…");
            castExecutor.execute(() -> {
                try {
                    List<ServiceDiscovery.Info> devices = mirrorCaster.discover();
                    log.info("Cast dialog refresh: {} device(s): {}", devices.size(), devices);
                    SwingUtilities.invokeLater(() -> {
                        model.clear();
                        for (ServiceDiscovery.Info d : devices) {
                            model.addElement(d);
                        }
                        status.setText(devices.isEmpty()
                                ? "No devices found"
                                : devices.size() + " device(s)");
                        refresh.setEnabled(true);
                    });
                } catch (Exception ex) {
                    log.warn("Discovery failed: {}", ex.toString());
                    SwingUtilities.invokeLater(() -> {
                        status.setText("Discovery failed");
                        refresh.setEnabled(true);
                        JOptionPane.showMessageDialog(dialog, ex.getMessage(), "Cast",
                                JOptionPane.ERROR_MESSAGE);
                    });
                }
            });
        };

        refresh.addActionListener(e -> doRefresh.run());
        cancel.addActionListener(e -> dialog.dispose());
        connect.addActionListener(e -> {
            ServiceDiscovery.Info device = list.getSelectedValue();
            if (device == null) {
                return;
            }
            dialog.dispose();
            castExecutor.execute(() -> connectTo(device));
        });

        JPanel south = new JPanel(new BorderLayout(8, 8));
        south.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(refresh);
        buttons.add(connect);
        buttons.add(cancel);
        south.add(buttons, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(new JLabel("Select an AirPlay receiver:"), BorderLayout.NORTH);
        root.add(new JScrollPane(list), BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        root.setPreferredSize(new Dimension(480, 320));

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        doRefresh.run();
        dialog.setVisible(true);
    }

    private void connectTo(ServiceDiscovery.Info device) {
        setStatus("Connecting to " + device.name() + "…");
        try {
            AtomicReference<String> pinChoice = new AtomicReference<>();
            AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> {
                int needPin = JOptionPane.showConfirmDialog(null,
                        "Does " + device.name() + " require a PIN?\n"
                                + "Yes = show PIN on TV (if supported), then enter it.\n"
                                + "No = legacy path (this project's receiver).",
                        "Cast",
                        JOptionPane.YES_NO_CANCEL_OPTION);
                if (needPin == JOptionPane.CANCEL_OPTION || needPin == JOptionPane.CLOSED_OPTION) {
                    cancelled.set(true);
                    return;
                }
                if (needPin == JOptionPane.YES_OPTION) {
                    new Thread(() -> mirrorCaster.requestPinDisplay(device), "airplay-pin-start").start();
                    String pin = (String) JOptionPane.showInputDialog(
                            null,
                            "Enter the PIN shown on " + device.name(),
                            "Cast PIN",
                            JOptionPane.QUESTION_MESSAGE,
                            null,
                            null,
                            "");
                    if (pin == null) {
                        cancelled.set(true);
                        return;
                    }
                    pinChoice.set(pin.trim());
                } else {
                    pinChoice.set("");
                }
            });
            if (Boolean.TRUE.equals(cancelled.get())) {
                SwingUtilities.invokeLater(this::refreshStatus);
                return;
            }
            mirrorCaster.connect(device, pinChoice.get());
            SwingUtilities.invokeLater(this::refreshStatus);
        } catch (Exception ex) {
            log.warn("Cast to {} failed: {}", device, ex.toString());
            mirrorCaster.disconnect();
            SwingUtilities.invokeLater(() -> {
                refreshStatus();
                JOptionPane.showMessageDialog(null,
                        "Cast failed:\n" + ex.getMessage(),
                        "Cast",
                        JOptionPane.ERROR_MESSAGE);
            });
        }
    }

    private void refreshStatus() {
        if (disconnectItem == null || systemTray == null) {
            return;
        }
        if (mirrorCaster.isConnected()) {
            String name = mirrorCaster.connectedDevice().map(ServiceDiscovery.Info::name).orElse("device");
            disconnectItem.setEnabled(true);
            systemTray.setStatus("Casting → " + name);
        } else {
            disconnectItem.setEnabled(false);
            systemTray.setStatus("java-airplay");
        }
    }

    private void setStatus(String text) {
        if (systemTray != null) {
            systemTray.setStatus(text);
        }
    }
}
