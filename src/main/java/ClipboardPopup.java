import org.jnativehook.GlobalScreen;
import org.jnativehook.keyboard.NativeKeyEvent;
import org.jnativehook.keyboard.NativeKeyListener;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClipboardPopup {

    private static final Object threadLock = new Object();
    private static Robot robot;
    // History elemanı: text + hash
    static class HistoryItem {
        final String text;
        final String hash;

        HistoryItem(String text) {
            this.text = text;
            this.hash = sha256(text);
        }

        static String sha256(String text) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : bytes) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) { return ""; }
        }
    }

    private static final List<HistoryItem> history = new ArrayList<>();
    private static JList<HistoryItem> list;
    private static JFrame frame;
    private static boolean ignoreNextClipboard = false;

    public static void main(String[] args) throws Exception {
        try {
            File file = new File(System.getProperty("user.home"), ".clipboard_popup.lock");
            FileOutputStream fos = new FileOutputStream(file);
            FileLock lock = fos.getChannel().tryLock();
            if (lock == null) {
                System.out.println("Uygulama zaten çalışıyor.");
                System.exit(0);
            }
            // JVM kapandığında lock otomatik temizlenir
        } catch (Exception e) {
            System.out.println("Uygulama zaten çalışıyor.");
            System.exit(0);
        }
        robot = new Robot();
        Logger.getLogger(GlobalScreen.class.getPackage().getName()).setLevel(Level.OFF);
        startClipboardWatcher();
        SwingUtilities.invokeLater(ClipboardPopup::createPopup);
        GlobalScreen.registerNativeHook();
        GlobalScreen.addNativeKeyListener(new HotkeyListener());
    }

    private static void createPopup() {
        frame = new JFrame();
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        frame.setBackground(new Color(25, 25, 25, 240));

        list = new JList<>(new DefaultListModel<>());
        list.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        list.setForeground(Color.WHITE);
        list.setBackground(new Color(40, 40, 40));
        list.setSelectionBackground(new Color(0, 120, 215));
        list.setSelectionForeground(Color.WHITE);
        list.setBorder(new EmptyBorder(5, 5, 5, 5));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() { thumbColor = new Color(100,100,100,180); }
            @Override protected JButton createDecreaseButton(int orientation) { return createZeroButton(); }
            @Override protected JButton createIncreaseButton(int orientation) { return createZeroButton(); }
            private JButton createZeroButton() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0,0)); return b; }
        });

        frame.add(scroll);
        frame.setSize(320, 300);

        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof HistoryItem item) label.setText(item.text);
                return label;
            }
        });

        list.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) pasteSelected();
            }
        });

        InputMap im = list.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = list.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "paste");
        am.put("paste", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { pasteSelected(); }});
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        am.put("close", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { frame.setVisible(false); }});
    }

    private static void showPopup() {
        updateList(); // her zaman güncel liste
        if (history.isEmpty()) return;

        Point mouse = MouseInfo.getPointerInfo().getLocation();
        frame.setLocation(mouse.x + 10, mouse.y + 10);
        frame.setVisible(true);

        if (list.getModel().getSize() > 0) {
            list.setSelectedIndex(0);
            list.ensureIndexIsVisible(0);
        }
        SwingUtilities.invokeLater(() -> list.requestFocusInWindow());
    }

    private static void updateList() {
        DefaultListModel<HistoryItem> model = (DefaultListModel<HistoryItem>) list.getModel();
        for (int i = 0; i < history.size(); i++) {
            if (i >= model.getSize()) model.addElement(history.get(i));
            else if (model.get(i) != history.get(i)) model.set(i, history.get(i));
        }
        while (model.getSize() > history.size()) model.remove(model.getSize() - 1);
        list.clearSelection();
        if (list.getModel().getSize() > 0) {
            list.setSelectedIndex(0);
            list.ensureIndexIsVisible(0);
        }

    }

    private static void pasteSelected() {
        HistoryItem selected = list.getSelectedValue();
        if (selected == null) return;

        try {

        synchronized (threadLock){
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            cb.setContents(new StringSelection(selected.text), null);

            frame.setVisible(false);


            boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
            if (isMac) {
                robot.keyPress(KeyEvent.VK_META);
                robot.keyPress(KeyEvent.VK_V);
                robot.keyRelease(KeyEvent.VK_V);
                robot.keyRelease(KeyEvent.VK_META);
            } else {
                robot.keyPress(KeyEvent.VK_CONTROL);
                robot.keyPress(KeyEvent.VK_V);
                robot.keyRelease(KeyEvent.VK_V);
                robot.keyRelease(KeyEvent.VK_CONTROL);
            }
            ignoreNextClipboard = false;
        }


        } catch (Exception e) { e.printStackTrace(); }
    }

    private static void startClipboardWatcher() {
        final Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        final String[] lastHash = {""};
        Thread.ofPlatform().start(() -> {
            while (true) {
                try {
                    Thread.sleep(500);
                    synchronized (threadLock){
                        checkClipboard(cb, lastHash);
                    }

                } catch (InterruptedException ignored) {
                }
            }
        });

    }

    private static void checkClipboard(final Clipboard cb, String[] lastHash) {
        try {
            if (ignoreNextClipboard) return;

            if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                String data = (String) cb.getData(DataFlavor.stringFlavor);
                if (data != null) {
                    String hash = HistoryItem.sha256(data);
                    if (!hash.equals(lastHash[0])) {
                        // Eğer hash zaten varsa sil, yeni öğeyi en üste ekle
                        history.removeIf(item -> item.hash.equals(hash));
                        history.addFirst(new HistoryItem(data));
                        if (history.size() > 20) history.removeLast();
                        lastHash[0] = hash;

                        // Popup açıksa listede güncelle ve en üstte seç
                        if (frame != null && frame.isVisible()) {
                            SwingUtilities.invokeLater(() -> {
                                updateList();
                                if (list.getModel().getSize() > 0) {
                                    list.setSelectedIndex(0);
                                    list.ensureIndexIsVisible(0);
                                }
                            });
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }


    private static class HotkeyListener implements NativeKeyListener {
        boolean ctrl, alt, shift, v;

        public void nativeKeyPressed(NativeKeyEvent e) {
            switch (e.getKeyCode()) {
                case NativeKeyEvent.VC_CONTROL -> ctrl = true;
                case NativeKeyEvent.VC_ALT -> alt = true;
                case NativeKeyEvent.VC_SHIFT -> shift = true;
                case NativeKeyEvent.VC_V -> v = true;
            }
            if (ctrl && alt && shift && v) SwingUtilities.invokeLater(ClipboardPopup::showPopup);
        }

        public void nativeKeyReleased(NativeKeyEvent e) {
            switch (e.getKeyCode()) {
                case NativeKeyEvent.VC_CONTROL -> ctrl = false;
                case NativeKeyEvent.VC_ALT -> alt = false;
                case NativeKeyEvent.VC_SHIFT -> shift = false;
                case NativeKeyEvent.VC_V -> v = false;
            }
        }

        public void nativeKeyTyped(NativeKeyEvent e) {}
    }
}
