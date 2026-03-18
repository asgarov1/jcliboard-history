import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ClipboardWatcher {

    private static final int MAX_HISTORY    = 100;
    private static final int POPUP_MAX_H    = 600; // max popup height in px before scrolling
    private static final int ITEM_H         = 54;
    private static final int HEADER_H       = 44;
    private static final int SEARCH_H       = 42;

    private static final List<String> history = new ArrayList<>();
    private static String lastValue = "";
    private static JDialog popupWindow;
    private static JTextField searchField;
    private static volatile boolean suppressSearch = false;
    private static JPanel listPanel;
    private static JScrollPane listScroll;

    private static volatile PrintWriter triggerReplyWriter = null;

    // History file lives next to the .class / .java file
    private static final Path HISTORY_FILE  = Paths.get("clipboard_history.txt");
    private static final Path TRIGGER_FILE  =
            Paths.get(System.getProperty("user.home"), ".clipboard_trigger");

    // ─── Entry point ─────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("trigger")) {
            sendSocketTrigger();
            return;
        }

        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

        loadHistory();

        startClipboardPoller();
        setupSystemTray();
        buildPopupWindow();
        startSocketListener();
        startFileTriggerWatcher();

        System.out.println("Clipboard Manager started. History: " + history.size() + " entries.");
        System.out.println("  Trigger via command: java ClipboardWatcher trigger");
        System.out.println("  Trigger via file   : touch " + TRIGGER_FILE);
        System.out.println("  Bind either to a hotkey in GNOME/KDE: Settings → Keyboard → Custom Shortcuts");
        Thread.sleep(Long.MAX_VALUE);
    }

    // ─── Persistence ─────────────────────────────────────────────────────────

    private static void loadHistory() {
        if (!Files.exists(HISTORY_FILE)) return;
        try {
            List<String> lines = Files.readAllLines(HISTORY_FILE, StandardCharsets.UTF_8);
            // File format: entries separated by a sentinel line
            String sentinel = "---ENTRY---";
            List<String> current = new ArrayList<>();
            for (String line : lines) {
                if (line.equals(sentinel)) {
                    if (!current.isEmpty()) {
                        String entry = String.join("\n", current);
                        history.add(entry);
                        current.clear();
                    }
                } else {
                    current.add(line);
                }
            }
            if (!current.isEmpty()) history.add(String.join("\n", current));
            if (!history.isEmpty()) lastValue = history.get(0);
            System.out.println("Loaded " + history.size() + " history entries.");
        } catch (Exception e) {
            System.err.println("Could not load history: " + e.getMessage());
        }
    }

    private static synchronized void saveHistory() {
        try {
            String sentinel = "---ENTRY---";
            StringBuilder sb = new StringBuilder();
            synchronized (history) {
                for (String entry : history) {
                    sb.append(entry).append("\n").append(sentinel).append("\n");
                }
            }
            Files.writeString(HISTORY_FILE, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("Could not save history: " + e.getMessage());
        }
    }

    // ─── Clipboard polling ───────────────────────────────────────────────────

    private static void startClipboardPoller() {
        Thread t = new Thread(() -> {
            // Use 150ms normally; after detecting a new value we burst-poll at 50ms
            // for a short window — this catches Citrix/RDP clipboard sync that arrives
            // in multiple rounds (first round may be empty or partial).
            int sleepMs = 150;
            int burstRemaining = 0;

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
                            saveHistory();
                            System.out.println("Copied: " + data.substring(0, Math.min(60, data.length())).replace("\n", "↵"));
                            // Burst-poll for 1 second after a new value — catches
                            // Citrix sending a better/complete version shortly after
                            burstRemaining = 20; // 20 x 50ms = 1s burst
                        }
                    }
                } catch (IllegalStateException ignored) {
                    // clipboard temporarily locked — burst-poll to catch the release
                    burstRemaining = 10;
                } catch (Exception ignored) {}

                if (burstRemaining > 0) {
                    burstRemaining--;
                    sleepMs = 50;
                } else {
                    sleepMs = 150;
                }
                try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ─── Socket trigger ──────────────────────────────────────────────────────

    private static void startSocketListener() {
        Thread t = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
                int port = server.getLocalPort();
                File portFile = new File(System.getProperty("java.io.tmpdir"), "clipboard_watcher.port");
                try (PrintWriter pw = new PrintWriter(portFile)) { pw.print(port); }
                portFile.deleteOnExit();
                System.out.println("  Socket on localhost:" + port);

                while (true) {
                    Socket s = server.accept();
                    BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
                    PrintWriter reply = new PrintWriter(s.getOutputStream(), true);
                    String msg = br.readLine();
                    if ("toggle".equals(msg)) {
                        if (popupWindow != null && popupWindow.isVisible()) {
                            dismissPopup();
                            reply.println("closed");
                            s.close();
                        } else {
                            triggerReplyWriter = reply;
                            showPopup();
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

    private static void sendSocketTrigger() {
        File portFile = new File(System.getProperty("java.io.tmpdir"), "clipboard_watcher.port");
        try {
            int port = Integer.parseInt(Files.readString(portFile.toPath()).trim());
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
                 PrintWriter pw = new PrintWriter(s.getOutputStream(), true);
                 BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
                pw.println("toggle");
                br.readLine();
            }
        } catch (Exception e) {
            System.err.println("Could not connect — is ClipboardWatcher running? " + e.getMessage());
        }
    }

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

    // ─── Popup: build (once) ─────────────────────────────────────────────────

    private static void buildPopupWindow() {
        SwingUtilities.invokeLater(() -> {
            popupWindow = new JDialog((Frame) null);
            popupWindow.setUndecorated(true);
            popupWindow.setAlwaysOnTop(true);
            popupWindow.setFocusableWindowState(true);

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(new Color(18, 18, 24));
            root.setBorder(new CompoundBorder(
                    new LineBorder(new Color(80, 80, 120), 1),
                    new EmptyBorder(0, 0, 0, 0)
            ));

            // ── Header bar ──────────────────────────────────────────────────
            JPanel header = new JPanel(new BorderLayout());
            header.setBackground(new Color(28, 28, 38));
            header.setBorder(new EmptyBorder(10, 16, 10, 12));

            JPanel titleBlock = new JPanel();
            titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
            titleBlock.setOpaque(false);

            JLabel title = new JLabel("📋  Clipboard History");
            title.setFont(new Font("Segoe UI", Font.BOLD, 13));
            title.setForeground(new Color(200, 200, 230));
            title.setAlignmentX(Component.LEFT_ALIGNMENT);

            JTextPane subtitle = new JTextPane();
            subtitle.setText("Bei Fragen: asgarov1@gmail.com");
            subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            subtitle.setForeground(new Color(100, 100, 140));
            subtitle.setBackground(new Color(28, 28, 38)); // match header bg
            subtitle.setEditable(false);
            subtitle.setOpaque(true);
            subtitle.setBorder(new EmptyBorder(3, 0, 0, 0));
            subtitle.setMargin(new Insets(0, 0, 0, 0));
            subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            subtitle.setHighlighter(new javax.swing.text.DefaultHighlighter());
            subtitle.setSelectedTextColor(new Color(210, 215, 240));
            subtitle.setSelectionColor(new Color(80, 60, 160));

            titleBlock.add(title);
            titleBlock.add(subtitle);
            header.add(titleBlock, BorderLayout.WEST);

            JPanel hints = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            hints.setOpaque(false);

            JLabel hintCopy = new JLabel("click to copy");
            hintCopy.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            hintCopy.setForeground(new Color(100, 100, 130));
            hintCopy.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hintCopy.setForeground(new Color(140, 200, 255)); }
                @Override public void mouseExited(MouseEvent e)  { hintCopy.setForeground(new Color(100, 100, 130)); }
            });

            JLabel sep = new JLabel("·");
            sep.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            sep.setForeground(new Color(60, 60, 80));

            JLabel hintClose = new JLabel("Esc to close");
            hintClose.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            hintClose.setForeground(new Color(100, 100, 130));
            hintClose.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            hintClose.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hintClose.setForeground(new Color(180, 160, 255)); }
                @Override public void mouseExited(MouseEvent e)  { hintClose.setForeground(new Color(100, 100, 130)); }
                @Override public void mouseClicked(MouseEvent e) { dismissPopup(); }
            });

            hints.add(hintCopy);
            hints.add(sep);
            hints.add(hintClose);
            header.add(hints, BorderLayout.EAST);

            // ── Search bar ──────────────────────────────────────────────────
            JPanel searchBar = new JPanel(new BorderLayout());
            searchBar.setBackground(new Color(22, 22, 32));
            searchBar.setBorder(new EmptyBorder(6, 12, 6, 12));

            searchField = new JTextField();
            searchField.setFocusable(true);
            searchField.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            // Bright enough to read against the dark background
            searchField.setForeground(new Color(210, 215, 240));
            searchField.setBackground(new Color(30, 30, 44));
            searchField.setCaretColor(new Color(160, 140, 255));
            // Use only an EmptyBorder — no LineBorder means no OS/L&F focus ring
            searchField.setBorder(new EmptyBorder(5, 10, 5, 10));
            // Override the L&F focus border that Metal/GTK themes paint inside the field
            searchField.setUI(new javax.swing.plaf.basic.BasicTextFieldUI() {
                @Override protected void paintSafely(Graphics g) {
                    // paint background manually since we removed the border
                    g.setColor(searchField.getBackground());
                    g.fillRect(0, 0, searchField.getWidth(), searchField.getHeight());
                    super.paintSafely(g);
                }
            });
            // Single focus listener: handles placeholder text + accent border
            searchField.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) {
                    if (searchField.getText().equals("Search…")) {
                        searchField.setText("");
                        searchField.setForeground(new Color(210, 215, 240));
                    }
                    searchField.setBorder(new CompoundBorder(
                            new MatteBorder(0, 0, 1, 0, new Color(120, 100, 220)),
                            new EmptyBorder(5, 10, 4, 10)));
                    searchField.repaint();
                }
                @Override public void focusLost(FocusEvent e) {
                    if (searchField.getText().isEmpty()) {
                        searchField.setText("Search…");
                        searchField.setForeground(new Color(80, 80, 110));
                    }
                    searchField.setBorder(new EmptyBorder(5, 10, 5, 10));
                    searchField.repaint();
                }
            });
            // Placeholder text
            searchField.setText("Search…");
            searchField.setForeground(new Color(80, 80, 110));
            searchField.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e)  { if (!suppressSearch) refreshList(); }
                public void removeUpdate(DocumentEvent e)  { if (!suppressSearch) refreshList(); }
                public void changedUpdate(DocumentEvent e) { if (!suppressSearch) refreshList(); }
            });
            // Esc from search field also closes
            searchField.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) dismissPopup();
                }
            });

            searchBar.add(searchField, BorderLayout.CENTER);

            // ── List (scrollable) ────────────────────────────────────────────
            listPanel = new JPanel();
            listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
            listPanel.setBackground(new Color(18, 18, 24));

            listScroll = new JScrollPane(listPanel);
            listScroll.setBorder(BorderFactory.createEmptyBorder());
            listScroll.setBackground(new Color(18, 18, 24));
            listScroll.getViewport().setBackground(new Color(18, 18, 24));
            listScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
            listScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            listScroll.getVerticalScrollBar().setUnitIncrement(16);
            listScroll.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
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

            // ── Top section: header + search ─────────────────────────────────
            JPanel top = new JPanel(new BorderLayout());
            top.add(header, BorderLayout.NORTH);
            top.add(searchBar, BorderLayout.SOUTH);

            root.add(top, BorderLayout.NORTH);
            root.add(listScroll, BorderLayout.CENTER);

            // ── Escape key (input map + direct listener) ─────────────────────
            KeyStroke esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(esc, "dismiss");
            root.getActionMap().put("dismiss", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { dismissPopup(); }
            });
            popupWindow.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) dismissPopup();
                }
            });

            // ── Close on focus lost ──────────────────────────────────────────
            // Covers: clicking outside, Alt+Tab, switching to another app, etc.
            popupWindow.addWindowFocusListener(new WindowAdapter() {
                @Override public void windowLostFocus(WindowEvent e) {
                    dismissPopup();
                }
            });

            // ── Click outside (fallback for window managers that don't fire windowLostFocus) ──
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

    // ─── Popup: show ─────────────────────────────────────────────────────────

    private static void showPopup() {
        SwingUtilities.invokeLater(() -> {
            if (popupWindow == null) return;

            // Flush clipboard at show-time — catches Citrix/remote clipboard lag
            // where the host X11 clipboard is updated after a short delay
            flushClipboardNow();

            // Reset search field without triggering the DocumentListener
            suppressSearch = true;
            searchField.setText("Search…");
            searchField.setForeground(new Color(80, 80, 110));
            suppressSearch = false;

            refreshList(); // populates listPanel synchronously now

            // Size after refreshList so listPanel.getComponentCount() is accurate
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int w = 560;
            int listCount = listPanel.getComponentCount();
            int naturalH = HEADER_H + SEARCH_H + listCount * ITEM_H + 8;
            int h = Math.min(naturalH, POPUP_MAX_H);
            h = Math.max(h, HEADER_H + SEARCH_H + ITEM_H + 8); // at least 1 row

            popupWindow.setSize(w, h);
            popupWindow.setLocation((screen.width - w) / 2, 60);
            popupWindow.setVisible(true);
            popupWindow.toFront();

            // Reset scroll to top
            SwingUtilities.invokeLater(() ->
                    listScroll.getVerticalScrollBar().setValue(0));

            // Focus the search field after the window is fully shown.
            // A nested invokeLater is required on Linux/X11: the first pass
            // makes the window visible, the second pass (next EDT cycle) is
            // when the OS actually grants focus to child components.
            SwingUtilities.invokeLater(() -> {
                popupWindow.toFront();
                searchField.requestFocusInWindow();
            });

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

    /**
     * Reads the clipboard right now and adds to history if it's new.
     * Called at popup-show time to catch clipboard managers (Citrix, RDP, etc.)
     * that sync to X11 with a delay — by the time the user triggers the popup,
     * the poller may not have seen the latest value yet.
     */
    private static void flushClipboardNow() {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable t = cb.getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String data = (String) t.getTransferData(DataFlavor.stringFlavor);
                if (data != null && !data.isEmpty() && !data.equals(lastValue)) {
                    lastValue = data;
                    synchronized (history) {
                        history.add(0, data);
                        if (history.size() > MAX_HISTORY)
                            history.remove(history.size() - 1);
                    }
                    saveHistory();
                    System.out.println("Flushed at show-time: " + data.substring(0, Math.min(60, data.length())).replace("\n", "↵"));
                }
            }
        } catch (Exception ignored) {}
    }

    /** Rebuild the list panel based on current search text. Must be called on the EDT. */
    private static void refreshList() {
        if (listPanel == null) return;
        String query = searchField.getText().trim().toLowerCase();
        boolean isPlaceholder = query.equals("search…") || query.isEmpty();

        List<String> filtered;
        synchronized (history) {
            filtered = isPlaceholder
                    ? new ArrayList<>(history)
                    : history.stream()
                    .filter(e -> e.toLowerCase().contains(query))
                    .collect(Collectors.toList());
        }

        listPanel.removeAll();
        if (filtered.isEmpty()) {
            JLabel empty = new JLabel(isPlaceholder ? "  Nothing copied yet…" : "  No matches found");
            empty.setFont(new Font("Segoe UI", Font.ITALIC, 12));
            empty.setForeground(new Color(100, 100, 130));
            empty.setBorder(new EmptyBorder(16, 16, 16, 16));
            listPanel.add(empty);
        } else {
            for (int i = 0; i < filtered.size(); i++)
                listPanel.add(createHistoryItem(i, filtered.get(i)));
        }
        listPanel.revalidate();
        listPanel.repaint();

        // Resize popup height to fit new filtered count, still capped
        if (popupWindow != null && popupWindow.isVisible()) {
            int count = Math.max(1, filtered.isEmpty() ? 1 : filtered.size());
            int naturalH = HEADER_H + SEARCH_H + count * ITEM_H + 8;
            int h = Math.min(naturalH, POPUP_MAX_H);
            Dimension cur = popupWindow.getSize();
            popupWindow.setSize(cur.width, h);
        }
    }

    /** Hides the popup and signals the trigger process to exit. */
    private static void dismissPopup() {
        if (popupWindow == null || !popupWindow.isVisible()) return;
        Timer fadeOut = new Timer(16, null);
        fadeOut.addActionListener(e -> {
            float op = popupWindow.getOpacity() - 0.10f;
            if (op <= 0f) {
                popupWindow.setOpacity(0f);
                popupWindow.setVisible(false);
                fadeOut.stop();
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

    // ─── History row ─────────────────────────────────────────────────────────

    private static JPanel createHistoryItem(int index, String text) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ITEM_H));
        row.setPreferredSize(new Dimension(500, ITEM_H));
        row.setBackground(new Color(18, 18, 24));
        row.setBorder(new EmptyBorder(5, 12, 0, 12));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel badge = new JLabel(String.valueOf(index + 1));
        badge.setFont(new Font("Segoe UI", Font.BOLD, 10));
        badge.setForeground(new Color(120, 100, 200));
        badge.setPreferredSize(new Dimension(22, 40));
        badge.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(new Color(26, 26, 36));
        card.setBorder(new CompoundBorder(
                new LineBorder(new Color(45, 45, 65), 1),
                new EmptyBorder(6, 10, 6, 10)
        ));

        String preview = text.replaceAll("\\s+", " ").trim();
        if (preview.length() > 76) preview = preview.substring(0, 73) + "…";

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
                flashConfirm(card, label, text);
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

    private static void flashConfirm(JPanel card, JLabel label, String text) {
        Color original = card.getBackground();
        String origText = label.getText();
        card.setBackground(new Color(30, 55, 35));
        label.setForeground(new Color(100, 220, 130));
        label.setText("✓  Copied (" + text.length() + " chars)");
        Timer t = new Timer(700, e -> {
            card.setBackground(original);
            label.setForeground(new Color(200, 210, 230));
            label.setText(origText);
            dismissPopup();
        });
        t.setRepeats(false);
        t.start();
    }

    // ─── System tray ─────────────────────────────────────────────────────────

    private static void setupSystemTray() {
        if (!SystemTray.isSupported()) return;
        SwingUtilities.invokeLater(() -> {
            try {
                TrayIcon trayIcon = new TrayIcon(createTrayIconImage(), "Clipboard Manager");
                trayIcon.setImageAutoSize(true);

                PopupMenu menu = new PopupMenu();
                MenuItem showItem  = new MenuItem("Show History");
                MenuItem clearItem = new MenuItem("Clear History");
                MenuItem exitItem  = new MenuItem("Exit");

                showItem.addActionListener(e -> showPopup());
                clearItem.addActionListener(e -> {
                    synchronized (history) { history.clear(); }
                    lastValue = "";
                    saveHistory();
                });
                exitItem.addActionListener(e -> System.exit(0));

                menu.add(showItem);
                menu.add(clearItem);
                menu.addSeparator();
                menu.add(exitItem);
                trayIcon.setPopupMenu(menu);
                trayIcon.addActionListener(e -> showPopup());
                SystemTray.getSystemTray().add(trayIcon);
            } catch (Exception e) { e.printStackTrace(); }
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