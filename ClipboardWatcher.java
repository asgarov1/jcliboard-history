import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class ClipboardWatcher {

    private static final int MAX_HISTORY = 10;
    private static final List<String> history = new ArrayList<>();
    private static String lastValue = "";
    private static JWindow popupWindow;

    // When the popup was opened by a trigger process, we hold its socket open
    // and send "closed" on it when the popup is dismissed — causing that process to exit.
    private static volatile PrintWriter triggerReplyWriter = null;

    private static final Path TRIGGER_FILE =
            Paths.get(System.getProperty("user.home"), ".clipboard_trigger");

    public static void main(String[] args) throws Exception {

        // ── If run with "trigger" argument, signal the running instance ──────
        if (args.length > 0 && args[0].equals("trigger")) {
            sendSocketTrigger();
            return;
        }

        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        // FlavorListener is unreliable on Linux (known JDK bug) -- misses rapid copies
        // and sometimes never fires depending on the desktop environment.
        // Polling every 200ms is the standard workaround and catches everything.
        Thread clipboardPoller = new Thread(() -> {
            while (true) {
                try {
                    Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    Transferable contents = clipboard.getContents(null);
                    if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        String data = (String) contents.getTransferData(DataFlavor.stringFlavor);
                        if (data != null && !data.isEmpty() && !data.equals(lastValue)) {
                            lastValue = data;
                            synchronized (history) {
                                history.add(0, data);
                                if (history.size() > MAX_HISTORY)
                                    history.remove(history.size() - 1);
                            }
                            System.out.println("Copied: " + data.substring(0, Math.min(60, data.length())));
                        }
                    }
                } catch (IllegalStateException e) {
                    // Clipboard temporarily unavailable (another app holds it) -- just retry
                } catch (Exception ignored) {}
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
        });
        clipboardPoller.setDaemon(true);
        clipboardPoller.start();

        setupSystemTray();
        buildPopupWindow();
        startSocketListener();
        startFileTriggerWatcher();

        System.out.println("Clipboard Manager started.");
        System.out.println("  Trigger via command: java ClipboardWatcher trigger");
        System.out.println("  Trigger via file   : touch " + TRIGGER_FILE);
        System.out.println("  Bind either to a hotkey in GNOME/KDE: Settings → Keyboard → Custom Shortcuts");
        Thread.sleep(Long.MAX_VALUE);
    }

    // ─── Socket listener ─────────────────────────────────────────────────────
    // The trigger process sends "toggle" and then BLOCKS waiting for "closed".
    // We store its writer and send "closed" when the popup is dismissed.

    private static void startSocketListener() {
        Thread t = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
                int port = server.getLocalPort();
                File portFile = new File(System.getProperty("java.io.tmpdir"), "clipboard_watcher.port");
                try (PrintWriter pw = new PrintWriter(portFile)) { pw.print(port); }
                portFile.deleteOnExit();
                System.out.println("  Socket on localhost:" + port + " (port in " + portFile + ")");

                while (true) {
                    // Accept connection but don't close it yet — keep writer for dismiss signal
                    Socket s = server.accept();
                    BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
                    PrintWriter reply = new PrintWriter(s.getOutputStream(), true);
                    String msg = br.readLine();
                    if ("toggle".equals(msg)) {
                        if (popupWindow != null && popupWindow.isVisible()) {
                            dismissPopup(); // already open → just close (no trigger process waiting)
                            reply.println("closed");
                            s.close();
                        } else {
                            // Store the reply writer; dismissPopup() will send "closed" and close it
                            triggerReplyWriter = reply;
                            showPopup();
                            // Socket stays open until dismissPopup() fires
                        }
                    } else {
                        reply.println("unknown");
                        s.close();
                    }
                }
            } catch (Exception e) {
                System.err.println("Socket listener failed: " + e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // Trigger process: send toggle, then block until "closed" is received, then exit.
    private static void sendSocketTrigger() {
        File portFile = new File(System.getProperty("java.io.tmpdir"), "clipboard_watcher.port");
        try {
            int port = Integer.parseInt(Files.readString(portFile.toPath()).trim());
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
                 PrintWriter pw  = new PrintWriter(s.getOutputStream(), true);
                 BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
                pw.println("toggle");
                br.readLine(); // blocks until main process sends "closed"
                // process exits naturally here
            }
        } catch (Exception e) {
            System.err.println("Could not connect — is ClipboardWatcher running? " + e.getMessage());
        }
    }

    // ─── File trigger watcher ────────────────────────────────────────────────

    private static void startFileTriggerWatcher() {
        Thread t = new Thread(() -> {
            try {
                WatchService watcher = FileSystems.getDefault().newWatchService();
                TRIGGER_FILE.getParent().register(watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                while (true) {
                    WatchKey key = watcher.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        if (changed.getFileName().toString()
                                .equals(TRIGGER_FILE.getFileName().toString())) {
                            Files.deleteIfExists(TRIGGER_FILE);
                            if (popupWindow != null && popupWindow.isVisible()) dismissPopup();
                            else showPopup();
                        }
                    }
                    key.reset();
                }
            } catch (Exception e) {
                System.err.println("File watcher failed: " + e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ─── Popup Window ────────────────────────────────────────────────────────

    private static void buildPopupWindow() {
        SwingUtilities.invokeLater(() -> {
            popupWindow = new JWindow();
            popupWindow.setAlwaysOnTop(true);
            popupWindow.setFocusableWindowState(true);

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(new Color(18, 18, 24));
            root.setBorder(new CompoundBorder(
                    new LineBorder(new Color(80, 80, 120), 1),
                    new EmptyBorder(0, 0, 8, 0)
            ));

            // ── Header ──
            JPanel header = new JPanel(new BorderLayout());
            header.setBackground(new Color(28, 28, 38));
            header.setBorder(new EmptyBorder(10, 16, 10, 12));

            JLabel title = new JLabel("📋  Clipboard History");
            title.setFont(new Font("Segoe UI", Font.BOLD, 13));
            title.setForeground(new Color(200, 200, 230));
            header.add(title, BorderLayout.WEST);

            // Two independently hoverable hint labels
            JPanel hints = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            hints.setOpaque(false);

            JLabel hintCopy = new JLabel("click to copy");
            hintCopy.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            hintCopy.setForeground(new Color(100, 100, 130));
            hintCopy.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    hintCopy.setForeground(new Color(140, 200, 255));
                }
                @Override public void mouseExited(MouseEvent e) {
                    hintCopy.setForeground(new Color(100, 100, 130));
                }
            });

            JLabel sep = new JLabel("·");
            sep.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            sep.setForeground(new Color(60, 60, 80));

            JLabel hintClose = new JLabel("Esc to close");
            hintClose.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            hintClose.setForeground(new Color(100, 100, 130));
            hintClose.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            hintClose.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    hintClose.setForeground(new Color(180, 160, 255));
                }
                @Override public void mouseExited(MouseEvent e) {
                    hintClose.setForeground(new Color(100, 100, 130));
                }
                @Override public void mouseClicked(MouseEvent e) {
                    dismissPopup();
                }
            });

            hints.add(hintCopy);
            hints.add(sep);
            hints.add(hintClose);
            header.add(hints, BorderLayout.EAST);

            root.add(header, BorderLayout.NORTH);

            // ── List Panel ──
            JPanel listPanel = new JPanel();
            listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
            listPanel.setBackground(new Color(18, 18, 24));

            JScrollPane scroll = new JScrollPane(listPanel);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setBackground(new Color(18, 18, 24));
            scroll.getViewport().setBackground(new Color(18, 18, 24));
            scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
                @Override protected void configureScrollBarColors() {
                    thumbColor = new Color(70, 70, 100);
                    trackColor = new Color(25, 25, 35);
                }
                @Override protected JButton createDecreaseButton(int o) { return zeroButton(); }
                @Override protected JButton createIncreaseButton(int o) { return zeroButton(); }
                private JButton zeroButton() {
                    JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b;
                }
            });

            root.add(scroll, BorderLayout.CENTER);

            // ── Escape key ──
            // Input map on the root panel (works when a child has focus)
            KeyStroke esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(esc, "dismiss");
            root.getActionMap().put("dismiss", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { dismissPopup(); }
            });

            // Direct KeyListener on the JWindow itself -- catches Esc even when
            // no child component has focus (common on Linux/X11)
            popupWindow.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) dismissPopup();
                }
            });

            // ── Click outside ──
            Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
                if (popupWindow.isVisible() && event instanceof MouseEvent) {
                    MouseEvent me = (MouseEvent) event;
                    if (me.getID() == MouseEvent.MOUSE_PRESSED &&
                            !popupWindow.getBounds().contains(me.getLocationOnScreen())) {
                        dismissPopup();
                    }
                }
            }, AWTEvent.MOUSE_EVENT_MASK);

            popupWindow.setContentPane(root);
        });
    }

    private static void showPopup() {
        SwingUtilities.invokeLater(() -> {
            if (popupWindow == null) return;

            JScrollPane scroll = (JScrollPane)
                    ((BorderLayout) popupWindow.getContentPane().getLayout())
                            .getLayoutComponent(BorderLayout.CENTER);
            JPanel listPanel = (JPanel) scroll.getViewport().getView();
            listPanel.removeAll();

            synchronized (history) {
                if (history.isEmpty()) {
                    JLabel empty = new JLabel("  Nothing copied yet…");
                    empty.setFont(new Font("Segoe UI", Font.ITALIC, 12));
                    empty.setForeground(new Color(100, 100, 130));
                    empty.setBorder(new EmptyBorder(16, 16, 16, 16));
                    listPanel.add(empty);
                } else {
                    for (int i = 0; i < history.size(); i++)
                        listPanel.add(createHistoryItem(i, history.get(i)));
                }
            }

            listPanel.revalidate();
            listPanel.repaint();

            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int w = 520;
            int itemH = Math.max(1, Math.min(history.size(), MAX_HISTORY));
            int h = Math.min(44 + itemH * 58 + 8, screen.height - 120);
            popupWindow.setSize(w, h);
            popupWindow.setLocation((screen.width - w) / 2, 60);
            popupWindow.setVisible(true);
            popupWindow.toFront();
            popupWindow.requestFocus();
            // Request focus on the window AND the root panel so both
            // the KeyListener and the input map bindings work on Linux
            popupWindow.getRootPane().requestFocusInWindow();

            popupWindow.setOpacity(0f);
            Timer fadeIn = new Timer(16, null);
            fadeIn.addActionListener(e -> {
                float op = popupWindow.getOpacity() + 0.08f;
                if (op >= 1f) { popupWindow.setOpacity(1f); fadeIn.stop(); }
                else popupWindow.setOpacity(op);
            });
            fadeIn.start();
        });
    }

    /** Hides the popup with fade-out, then signals the trigger process to exit. */
    private static void dismissPopup() {
        if (popupWindow == null || !popupWindow.isVisible()) return;
        Timer fadeOut = new Timer(16, null);
        fadeOut.addActionListener(e -> {
            float op = popupWindow.getOpacity() - 0.10f;
            if (op <= 0f) {
                popupWindow.setOpacity(0f);
                popupWindow.setVisible(false);
                fadeOut.stop();
                // Signal waiting trigger process (if any) to exit
                PrintWriter reply = triggerReplyWriter;
                if (reply != null) {
                    reply.println("closed");
                    triggerReplyWriter = null;
                }
            } else {
                popupWindow.setOpacity(op);
            }
        });
        fadeOut.start();
    }

    // ─── History Row ─────────────────────────────────────────────────────────

    private static JPanel createHistoryItem(int index, String text) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        row.setPreferredSize(new Dimension(480, 54));
        row.setBackground(new Color(18, 18, 24));
        row.setBorder(new EmptyBorder(5, 12, 0, 12));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel badge = new JLabel(String.valueOf(index + 1));
        badge.setFont(new Font("Segoe UI", Font.BOLD, 10));
        badge.setForeground(new Color(120, 100, 200));
        badge.setPreferredSize(new Dimension(18, 40));
        badge.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(new Color(26, 26, 36));
        card.setBorder(new CompoundBorder(
                new LineBorder(new Color(45, 45, 65), 1),
                new EmptyBorder(6, 10, 6, 10)
        ));

        String preview = text.replaceAll("\\s+", " ").trim();
        if (preview.length() > 72) preview = preview.substring(0, 69) + "…";

        JLabel label = new JLabel(preview);
        label.setFont(new Font("Monospaced", Font.PLAIN, 11));
        label.setForeground(new Color(200, 210, 230));
        card.add(label, BorderLayout.CENTER);

        JLabel copyBtn = new JLabel("⎘");
        copyBtn.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        copyBtn.setForeground(new Color(80, 80, 110));
        copyBtn.setBorder(new EmptyBorder(0, 6, 0, 0));
        card.add(copyBtn, BorderLayout.EAST);

        row.add(badge, BorderLayout.WEST);
        row.add(card, BorderLayout.CENTER);

        Color normalBg = new Color(26, 26, 36);
        Color hoverBg  = new Color(36, 34, 52);

        MouseAdapter hover = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                card.setBackground(hoverBg);
                copyBtn.setForeground(new Color(140, 120, 240));
            }
            @Override public void mouseExited(MouseEvent e) {
                card.setBackground(normalBg);
                copyBtn.setForeground(new Color(80, 80, 110));
            }
            @Override public void mouseClicked(MouseEvent e) {
                copyToClipboard(text);
                flashConfirm(card, label);
            }
        };
        row.addMouseListener(hover);
        card.addMouseListener(hover);
        label.addMouseListener(hover);
        copyBtn.addMouseListener(hover);

        return row;
    }

    private static void copyToClipboard(String text) {
        StringSelection sel = new StringSelection(text);
        lastValue = text;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
    }

    private static void flashConfirm(JPanel card, JLabel label) {
        Color original = card.getBackground();
        String origText = label.getText();
        card.setBackground(new Color(30, 55, 35));
        label.setForeground(new Color(100, 220, 130));
        label.setText("✓  Copied!");
        Timer t = new Timer(700, e -> {
            card.setBackground(original);
            label.setForeground(new Color(200, 210, 230));
            label.setText(origText);
            dismissPopup();
        });
        t.setRepeats(false);
        t.start();
    }

    // ─── System Tray ─────────────────────────────────────────────────────────

    private static void setupSystemTray() {
        if (!SystemTray.isSupported()) return;
        SwingUtilities.invokeLater(() -> {
            try {
                TrayIcon trayIcon = new TrayIcon(createTrayIconImage(), "Clipboard Manager");
                trayIcon.setImageAutoSize(true);

                PopupMenu menu = new PopupMenu();
                MenuItem showItem = new MenuItem("Show History");
                showItem.addActionListener(e -> showPopup());
                MenuItem clearItem = new MenuItem("Clear History");
                clearItem.addActionListener(e -> {
                    synchronized (history) { history.clear(); }
                    lastValue = "";
                });
                MenuItem exitItem = new MenuItem("Exit");
                exitItem.addActionListener(e -> System.exit(0));

                menu.add(showItem);
                menu.add(clearItem);
                menu.addSeparator();
                menu.add(exitItem);
                trayIcon.setPopupMenu(menu);
                trayIcon.addActionListener(e -> showPopup());
                SystemTray.getSystemTray().add(trayIcon);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static Image createTrayIconImage() {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(120, 100, 220));
        g.fillRoundRect(1, 1, 14, 14, 4, 4);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 9));
        g.drawString("C", 4, 11);
        g.dispose();
        return img;
    }
}