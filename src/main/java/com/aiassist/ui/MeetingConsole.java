package com.aiassist.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import com.aiassist.audio.LiveTranscriptionService;
import com.aiassist.draft.AttributedTranscript;
import com.aiassist.draft.Draft;
import com.aiassist.draft.MeetingEndService;
import com.aiassist.listen.ListeningSession;
import com.aiassist.listen.SessionStore;
import com.aiassist.listen.Utterance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The app's own window — no browser involved. A scrollable text box shows
 * the running transcript as it is recognized; Pause suspends listening;
 * Stop marks the meeting complete, drafts the full notes, and saves the
 * timestamped file. Closing via the corner button asks what to do if a
 * meeting is still running. Built on Swing, which ships with the JDK, so
 * the only resources used are the OS's own.
 */
@Component
public class MeetingConsole {

    private static final Logger log = LoggerFactory.getLogger(MeetingConsole.class);

    private final LiveTranscriptionService liveTranscription;
    private final MeetingEndService meetingEndService;
    private final SessionStore sessions;
    private final com.aiassist.draft.TextRewriteService rewriteService;
    private final com.aiassist.draft.StyleRewriteService styleRewriteService;
    private final com.aiassist.feedback.FeedbackMailSender feedbackMailSender;
    private javax.swing.JDialog modelNoticeDialog;
    private javax.swing.JEditorPane modelNoticePane;

    private final java.util.prefs.Preferences prefs =
            java.util.prefs.Preferences.userNodeForPackage(MeetingConsole.class);

    private JFrame frame;
    private javax.swing.JTextPane transcript;
    private JTextArea summaryArea;
    private javax.swing.JSplitPane meetingSplit;
    private JPanel meetingSummaryPane;
    private JLabel statusLabel;
    private JLabel captionLabel;
    private JLabel titleLabel;
    private javax.swing.JTextField titleField;
    private javax.swing.JCheckBox darkModeToggle;
    private javax.swing.JComboBox<String> modelCombo;
    private boolean updatingModels;
    private JPanel controlsPanel;
    private JPanel topPanel;
    private JPanel topStackPanel;
    private JPanel meetingTabPanel;
    private JPanel meetingTopRow;
    private JPanel extractionPanel;
    private javax.swing.JProgressBar extractionBar;
    private JLabel extractionLabel;
    private boolean modelsAvailable = true;
    private javax.swing.JTabbedPane tabs;
    private javax.swing.JTextField filePathField;
    private JPanel editorFileRow; // the Assist tab's file row
    private JTextArea composeResult;
    private JTextArea composeFeed;
    private JLabel composeStatus;
    private javax.swing.JTextField composeInstructions;
    private OptionChecks composeChecks;
    private JPanel composeInstrRow;
    private JPanel composeOptionStack;
    private final java.util.List<javax.swing.JCheckBox> themedChecks = new java.util.ArrayList<>();
    private final java.util.List<JLabel> themedLabels = new java.util.ArrayList<>();
    private final java.util.List<JButton> themedButtons = new java.util.ArrayList<>();
    private javax.swing.JSplitPane composeSplit;
    private JPanel composePanel;
    private JPanel composeTopPanel;
    private JPanel composeBottomPanel;
    private JPanel composeSouthPanel;
    private JPanel composeControlsPanel;
    private JLabel meetingIndicator;
    private JPanel indicatorPanel;
    private JPanel southWrapPanel;
    // Opt-in auto-start: when a meeting app is detected, a cancelable countdown
    // begins and then starts capture. Off by default.
    private javax.swing.JCheckBox autoStartToggle;
    private JPanel autoStartPanel;
    private JLabel autoStartLabel;
    private long autoStartDeadline;      // 0 = no countdown running
    private String autoStartHandledApp;  // app already prompted for this episode
    private long autoStartCooldownUntil;  // don't re-prompt before this time
    // Serialises system-audio monitor start/stop off the UI thread.
    private final java.util.concurrent.ExecutorService monitorControl =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "monitor-control");
                t.setDaemon(true);
                return t;
            });
    private volatile boolean monitorWanted;
    // Settings tab.
    private JPanel helpPanel;
    private JScrollPane helpScroll;
    private final java.util.List<JPanel> helpPanels = new java.util.ArrayList<>();
    private JTextArea feedbackArea;
    private javax.swing.JComboBox<Integer> ratingCombo;
    private JButton feedbackSubmit;
    private JLabel feedbackStatus;
    private JLabel feedbackCount;
    private JTextArea submittedArea;   // read-only copy of the last submitted feedback
    private JPanel submittedPanel;
    private static final int FEEDBACK_MAX_CHARS = 3000;
    private boolean blinkOn;
    private JPanel bottomPanel;
    private JPanel buttonsPanel;
    private JLabel notesLink;                   // clickable link to the just-saved notes file
    private java.nio.file.Path lastSavedNotes;  // shown until the next meeting starts / app closes
    private JPanel statusStackPanel;
    private java.util.List<JPanel> meetingButtonRows = java.util.List.of();
    /** One button size across the whole app, so every button matches. */
    private static final java.awt.Dimension BUTTON_SIZE = new java.awt.Dimension(66, 28);
    private JButton startButton;
    private JButton pauseButton;
    private JButton stopButton;
    private Timer refreshTimer;
    private int renderedUtterances;
    private String renderedSessionId;
    private boolean transcriptHeaderWritten;
    private java.time.Instant meetingStartAt;
    // Serialises background note-drafting so Stop returns instantly and
    // back-to-back meetings save their notes in order.
    private final java.util.concurrent.ExecutorService notesExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "notes-finish");
                t.setDaemon(true);
                return t;
            });
    private boolean meetingCompleted;
    private volatile boolean savingNotes;
    private int silentCycles;
    private int detectorCountdown;
    private String detectedMeetingApp;
    private boolean darkMode;
    private String lastStatusMessage = " ";
    private boolean lastStatusWasError;

    public MeetingConsole(LiveTranscriptionService liveTranscription,
                          MeetingEndService meetingEndService, SessionStore sessions,
                          com.aiassist.draft.TextRewriteService rewriteService,
                          com.aiassist.draft.StyleRewriteService styleRewriteService,
                          com.aiassist.feedback.FeedbackMailSender feedbackMailSender) {
        this.liveTranscription = liveTranscription;
        this.meetingEndService = meetingEndService;
        this.sessions = sessions;
        this.rewriteService = rewriteService;
        this.styleRewriteService = styleRewriteService;
        this.feedbackMailSender = feedbackMailSender;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void open() {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                log.info("No display available; running without the desktop window (REST API stays available)");
                return;
            }
            SwingUtilities.invokeLater(this::build);
        } catch (Throwable e) {
            // AWT throws Errors (not Exceptions) when a display is configured but
            // unreachable; the window is optional and must never break startup.
            log.warn("Could not open the desktop window ({}); REST API stays available", e.getMessage());
        }
    }

    /** Meeting-notes icon (page + red recording dot), drawn at runtime. */
    private static java.awt.image.BufferedImage notesIcon(int size) {
        var image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        int m = Math.max(1, size / 10);
        int arc = size / 5;
        g.setColor(java.awt.Color.WHITE);
        g.fillRoundRect(m, m / 2, size - 2 * m, size - m, arc, arc);
        g.setColor(new java.awt.Color(0x4A4A4A));
        g.setStroke(new java.awt.BasicStroke(Math.max(1f, size / 24f)));
        g.drawRoundRect(m, m / 2, size - 2 * m, size - m, arc, arc);
        for (int i = 1; i <= 3; i++) {
            int y = m / 2 + i * (size - m) / 5;
            g.drawLine(2 * m, y, size - 3 * m, y);
        }
        int dot = size / 3;
        g.setColor(new java.awt.Color(0xE74C3C));
        g.fillOval(size - dot - m, size - dot - m, dot, dot);
        g.dispose();
        return image;
    }

    private void build() {
        try {
            // Swing's own built-in cross-platform look-and-feel (Metal): the
            // same on Windows and macOS, no third-party UI dependency. It also
            // honours the explicit colors we set for the Dark toggle.
            javax.swing.UIManager.setLookAndFeel(
                    javax.swing.UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {
            log.debug("Cross-platform look and feel unavailable: {}", e.getMessage());
        }
        frame = new JFrame("ai-assist — Meeting");
        var icons = java.util.List.of(notesIcon(16), notesIcon(32), notesIcon(64), notesIcon(128));
        frame.setIconImages(icons);
        try {
            if (java.awt.Taskbar.isTaskbarSupported()
                    && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                java.awt.Taskbar.getTaskbar().setIconImage(icons.get(3)); // macOS dock
            }
        } catch (Exception ignored) {
            // dock icon is cosmetic
        }
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onClose();
            }
        });

        transcript = new javax.swing.JTextPane();
        transcript.setEditable(false);
        transcript.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        transcript.setMargin(new java.awt.Insets(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(transcript,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        // Summary area below the transcript, filled by Apply (and on Stop).
        summaryArea = new JTextArea();
        summaryArea.setEditable(false);
        summaryArea.setLineWrap(true);
        summaryArea.setWrapStyleWord(true);
        summaryArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        summaryArea.setMargin(new java.awt.Insets(8, 8, 8, 8));
        JPanel summaryPane = new JPanel(new BorderLayout());
        summaryPane.add(themedLabel("  Summary (click Apply):"), BorderLayout.NORTH);
        summaryPane.add(new JScrollPane(summaryArea,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),
                BorderLayout.CENTER);
        meetingSplit = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, scroll, summaryPane);
        meetingSplit.setResizeWeight(0.7);
        meetingSummaryPane = summaryPane;

        // Editable meeting title — becomes the notes file name.
        titleField = new javax.swing.JTextField("Minutes of meeting");
        roundTextField(titleField);
        titleField.setToolTipText("Meeting title — used for the notes file name (a timestamp is added on save)");
        titleField.addActionListener(e -> applyTitle());
        titleField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                applyTitle();
            }
        });
        darkModeToggle = new javax.swing.JCheckBox("Dark");
        darkModeToggle.setToolTipText("Switch between light and dark mode");
        darkModeToggle.addActionListener(e -> {
            applyTheme(darkModeToggle.isSelected());
            prefs.putBoolean("darkMode", darkModeToggle.isSelected());
        });

        // Model picker: built-in default plus any Vosk model unpacked into
        // the ./models folder next to the app. Reloaded each time it opens.
        modelCombo = new javax.swing.JComboBox<>();
        modelCombo.setToolTipText("<html><b>Live-caption speech model — pick one built for real time.</b><br>"
                + "Live captions must decode faster than you speak (two streams at once), so use a "
                + "streaming model:<br>"
                + "· <b>vosk-model-small-en-us-0.15</b> (40 MB) — fastest, great on most machines<br>"
                + "· <b>vosk-model-en-us-0.22-lgraph</b> (128 MB) — real-time and more accurate "
                + "(recommended)<br>"
                + "· vosk-model-en-us-0.22 (1.8 GB) — most accurate but <b>too heavy for real-time</b> "
                + "on most CPUs; it drops most live captions. The accurate saved transcript uses "
                + "Whisper regardless, so a lighter live model here does not reduce your notes' quality."
                + "</html>");
        populateModels();
        modelCombo.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                liveTranscription.rescanModelZips(); // zips dropped after launch
                populateModels();
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        });
        modelCombo.addActionListener(e -> {
            if (updatingModels) {
                return;
            }
            String selected = (String) modelCombo.getSelectedItem();
            if (selected == null) {
                return;
            }
            if (selected.endsWith(UNPACKING_SUFFIX)) {
                populateModels(); // not usable yet; snap back to the active model
                return;
            }
            if (!selected.equals(liveTranscription.activeModelName())) {
                // selectModel persists the choice in the service (survives restarts).
                new Thread(() -> liveTranscription.selectModel(selected), "model-switch").start();
            }
        });
        // The dropdown reflects activeModelName(), which the service restores
        // from the saved preference — so the previous choice shows on launch.

        titleLabel = new JLabel("Title:");
        autoStartToggle = new javax.swing.JCheckBox("Auto-start");
        autoStartToggle.setToolTipText("<html>Start recording automatically when you join a meeting: "
                + "the app watches for a meeting application (Microsoft Teams, Webex, Zoom, Slack) and "
                + "waits until the meeting is actually <b>playing audio</b> (the other participants "
                + "talking) before a short, cancelable countdown starts capture.<br>"
                + "It uses the system audio only — never your microphone. It listens on the built-in "
                + "tap (macOS/Windows) or a loopback/monitor device (Linux); if no system-audio source "
                + "exists it falls back to app detection.</html>");
        autoStartToggle.setSelected(prefs.getBoolean("autoStart", true));
        autoStartToggle.addActionListener(e -> {
            prefs.putBoolean("autoStart", autoStartToggle.isSelected());
            if (!autoStartToggle.isSelected()) {
                cancelAutoStartPrompt();
                setMonitorWanted(false);
            }
        });
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.add(themedLabel("Model:"));
        controls.add(modelCombo);
        controls.add(autoStartToggle);
        // Dark mode now lives on the Help tab (see buildHelpTab).
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(titleLabel, BorderLayout.WEST);
        top.add(titleField, BorderLayout.CENTER);
        top.add(controls, BorderLayout.EAST);
        // Shown while dropped model zips are being extracted on first start.
        extractionBar = new javax.swing.JProgressBar();
        extractionBar.setIndeterminate(true);
        extractionLabel = themedLabel(" ");
        extractionPanel = new JPanel(new BorderLayout(6, 0));
        extractionPanel.add(extractionLabel, BorderLayout.WEST);
        extractionPanel.add(extractionBar, BorderLayout.CENTER);
        extractionPanel.setVisible(false);
        JPanel topStack = new JPanel();
        topStack.setLayout(new javax.swing.BoxLayout(topStack, javax.swing.BoxLayout.Y_AXIS));
        topStack.add(top);
        topStack.add(extractionPanel);
        topStackPanel = topStack;
        top.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 4, 8));
        topPanel = topStack;
        meetingTopRow = top;
        controlsPanel = controls;

        statusLabel = new JLabel(" ");
        // Small and muted — status sits quietly in the lower-left corner.
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        // Live caption: in-progress words before the recognizer finalizes them.
        captionLabel = new JLabel(" ");
        captionLabel.setForeground(java.awt.Color.GRAY);
        captionLabel.setFont(captionLabel.getFont().deriveFont(Font.PLAIN, 11f));
        startButton = new IndicatorButton("Start");
        startButton.setToolTipText("Begin a new meeting");
        startButton.addActionListener(e -> startMeeting());
        pauseButton = new IndicatorButton("Pause");
        pauseButton.setToolTipText("Temporarily stop listening without ending the meeting");
        pauseButton.addActionListener(e -> togglePause());
        stopButton = new IndicatorButton("Stop");
        stopButton.setToolTipText("Meeting complete — draft the notes and save the file");
        stopButton.addActionListener(e -> stopMeeting());
        themedButtons.add(startButton);
        themedButtons.add(pauseButton);
        themedButtons.add(stopButton);

        JButton clearButton = button("Clear");
        clearButton.setToolTipText("Clear the transcript and summary display (captured content is kept for the notes)");
        clearButton.addActionListener(e -> {
            transcript.setText("");
            summaryArea.setText("");
        });

        JButton applyButton = button("Apply");
        applyButton.setToolTipText("Summarize the meeting so far and show it below");
        applyButton.addActionListener(e -> applyMeetingSummary());

        // Two rows: Clear/Apply on top, Start/Pause/Stop below, with a gap.
        JPanel buttonsTop = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonsTop.add(sized(clearButton));
        buttonsTop.add(sized(applyButton));
        JPanel buttonsBottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonsBottom.add(sized(startButton));
        buttonsBottom.add(sized(pauseButton));
        buttonsBottom.add(sized(stopButton));
        JPanel buttons = new JPanel();
        buttons.setLayout(new javax.swing.BoxLayout(buttons, javax.swing.BoxLayout.Y_AXIS));
        buttons.add(buttonsTop);
        buttons.add(javax.swing.Box.createVerticalStrut(14));
        buttons.add(buttonsBottom);
        buttonsPanel = buttons;
        meetingButtonRows = java.util.List.of(buttonsTop, buttonsBottom);
        // Caption, then status/errors, each on their own full-width line ABOVE
        // the buttons, wrapping instead of crowding into the button row.
        // Clickable link to the last saved notes file (shown after Stop→Save,
        // until the next meeting starts or the app closes).
        notesLink = linkLabel("Open saved notes");
        notesLink.setVisible(false);
        notesLink.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (lastSavedNotes != null) {
                    openFile(lastSavedNotes);
                }
            }
        });
        // Status + the saved-notes link, left-aligned in the lower-left corner.
        JPanel statusStack = new JPanel(new BorderLayout());
        statusStack.add(statusLabel, BorderLayout.NORTH);
        statusStack.add(notesLink, BorderLayout.CENTER);
        statusStackPanel = statusStack;
        // Buttons ride on the right just above the status; the status stays
        // quietly in the lower-left corner of the tab.
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(captionLabel, BorderLayout.NORTH);
        bottom.add(buttons, BorderLayout.CENTER);
        bottom.add(statusStack, BorderLayout.SOUTH);
        bottom.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));
        bottomPanel = bottom;

        frame.setLayout(new BorderLayout());
        // The Meeting tab now carries its own title row and buttons, so every
        // tab is self-contained and the three tabs line up consistently (no
        // shared bar floating above the tab strip).
        JPanel meetingTab = new JPanel(new BorderLayout());
        meetingTab.add(topStackPanel, BorderLayout.NORTH);
        meetingTab.add(meetingSplit, BorderLayout.CENTER);
        meetingTab.add(bottom, BorderLayout.SOUTH);
        meetingTabPanel = meetingTab;
        tabs = new javax.swing.JTabbedPane();
        tabs.addTab("Meeting", meetingTab);
        tabs.addTab("Compose", buildAssistTab());
        tabs.addTab("Settings", buildHelpTab());
        frame.add(tabs, BorderLayout.CENTER);

        // On the Editor/Compose tabs the meeting chrome (title row, status,
        // buttons) is hidden; a blinking indicator shows a live meeting.
        meetingIndicator = new JLabel(" ");
        meetingIndicator.setFont(meetingIndicator.getFont().deriveFont(Font.PLAIN, 11f));
        indicatorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        indicatorPanel.add(meetingIndicator);
        indicatorPanel.setVisible(false);
        // Cancelable auto-start countdown bar (hidden unless armed and a meeting
        // app is detected).
        autoStartLabel = themedLabel(" ");
        autoStartLabel.setFont(autoStartLabel.getFont().deriveFont(Font.PLAIN, 12f));
        JButton autoStartNow = button("Start now");
        autoStartNow.addActionListener(e -> {
            cancelAutoStartPrompt();
            startMeeting();
        });
        JButton autoStartNotNow = button("Not now");
        autoStartNotNow.addActionListener(e -> dismissAutoStart());
        autoStartPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        autoStartPanel.add(autoStartLabel);
        autoStartPanel.add(sized(autoStartNow));
        autoStartPanel.add(sized(autoStartNotNow));
        autoStartPanel.setVisible(false);
        // Only the cross-tab bits live at the frame bottom now: the auto-start
        // countdown prompt and the "meeting in progress" indicator (shown while
        // you are on another tab). The meeting's own buttons ride inside its tab.
        JPanel southWrap = new JPanel();
        southWrap.setLayout(new javax.swing.BoxLayout(southWrap, javax.swing.BoxLayout.Y_AXIS));
        southWrap.add(autoStartPanel);
        southWrap.add(indicatorPanel);
        southWrapPanel = southWrap;
        tabs.addChangeListener(e -> {
            // App name stays "ai-assist"; the active tab is the only suffix.
            frame.setTitle("ai-assist — " + tabs.getTitleAt(tabs.getSelectedIndex()));
            updateTabColors();
            frame.revalidate();
            frame.repaint();
        });
        frame.add(southWrap, BorderLayout.SOUTH);
        frame.setMinimumSize(new java.awt.Dimension(720, 520));
        frame.setSize(720, 520); // open at the minimum size
        frame.setResizable(true);
        frame.setLocationRelativeTo(null); // center on screen instead of the corner

        darkModeToggle.setSelected(prefs.getBoolean("darkMode", false));
        applyTheme(darkModeToggle.isSelected());
        frame.setVisible(true);

        // Make sure the installed app's user folders exist, place first-run
        // desktop shortcuts, then tell the user which models still need downloading.
        com.aiassist.setup.UserPaths.meetingNotesDir();
        com.aiassist.setup.UserPaths.modelsDir();
        com.aiassist.setup.DesktopShortcuts.createOnceOnFirstRun();
        maybeShowModelNotice(false);

        // 250 ms so the live caption line keeps up with Vosk's partial results;
        // a 1 s poll missed most partials (they reset the moment a phrase is final).
        refreshTimer = new Timer(250, e -> refresh());
        refreshTimer.start();
    }


    /** Writes the modified content to the Desktop, off the UI thread. */
    private void downloadAssistFile() {
        String content = composeResult.getText();
        if (content == null || content.isBlank()) {
            composeStatus.setText("Nothing to save yet — click Apply first.");
            return;
        }
        String sourcePath = filePathField.getText();
        composeStatus.setText("Saving…");
        notesExecutor.submit(() -> {
            try {
                String fileName = sourcePath == null || sourcePath.isBlank()
                        ? "assist.txt"
                        : java.nio.file.Path.of(sourcePath.strip()).getFileName().toString();
                java.nio.file.Path desktop = java.nio.file.Path.of(
                        System.getProperty("user.home"), "Desktop");
                java.nio.file.Files.createDirectories(desktop);
                java.nio.file.Path target = desktop.resolve(fileName);
                if (java.nio.file.Files.exists(target)) {
                    int dot = fileName.lastIndexOf('.');
                    target = desktop.resolve(dot > 0
                            ? fileName.substring(0, dot) + "-edited" + fileName.substring(dot)
                            : fileName + "-edited");
                }
                java.nio.file.Files.writeString(target, content);
                final java.nio.file.Path saved = target;
                SwingUtilities.invokeLater(() -> composeStatus.setText("Saved to " + saved));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        composeStatus.setText("Could not save: " + e.getMessage()));
            }
        });
    }

    private javax.swing.JCheckBox themedCheck(String text) {
        var check = new javax.swing.JCheckBox(text);
        check.setOpaque(true);
        themedChecks.add(check);
        return check;
    }

    /**
     * The rewrite options shared by the Editor and Compose tabs: the editing
     * toggles, the communication styles, and Summary — all in one panel, laid
     * out in a tidy grid and sorted alphabetically.
     */
    private final class OptionChecks {
        final JPanel panel = new JPanel(new java.awt.GridLayout(0, 4, 12, 2));
        final javax.swing.JCheckBox grammar = themedCheck("Grammar");
        final javax.swing.JCheckBox compact = themedCheck("Compact");
        final javax.swing.JCheckBox detailed = themedCheck("Detailed");
        final javax.swing.JCheckBox professional = themedCheck("Professional");
        final javax.swing.JCheckBox bullets = themedCheck("Bullet points");
        final javax.swing.JCheckBox summary = themedCheck("Summary");
        final javax.swing.JCheckBox email = themedCheck("Email");
        final java.util.List<javax.swing.JCheckBox> styles = new java.util.ArrayList<>();

        OptionChecks() {
            summary.setToolTipText("Summarise the text as an overview, key points and action items");
            email.setToolTipText("Rewrite as a professional email (subject, greeting, body, sign-off)");
            java.util.List<javax.swing.JCheckBox> all = new java.util.ArrayList<>(java.util.List.of(
                    grammar, compact, detailed, professional, bullets, summary, email));
            for (var style : com.aiassist.draft.StyleRewriteService.Style.values()) {
                var cb = themedCheck(style.display());
                cb.putClientProperty("style", style);
                styles.add(cb);
                all.add(cb);
            }
            all.sort(java.util.Comparator.comparing(
                    javax.swing.AbstractButton::getText, String.CASE_INSENSITIVE_ORDER));
            all.forEach(panel::add);
        }

        java.util.List<com.aiassist.draft.StyleRewriteService.Style> selectedStyles() {
            return styles.stream().filter(javax.swing.AbstractButton::isSelected)
                    .map(cb -> (com.aiassist.draft.StyleRewriteService.Style) cb.getClientProperty("style"))
                    .toList();
        }

        void clear() {
            grammar.setSelected(false);
            compact.setSelected(false);
            detailed.setSelected(false);
            professional.setSelected(false);
            bullets.setSelected(false);
            summary.setSelected(false);
            email.setSelected(false);
            styles.forEach(cb -> cb.setSelected(false));
        }
    }

    /** Runs the chosen options over the text: Summary, else the editing pipeline. */
    private String runOptions(OptionChecks o, String text, String instructions) {
        String instr = instructions == null ? "" : instructions.strip();
        if (o.email.isSelected()) {
            instr = ("Rewrite the content as a professional email with a concise subject line, a "
                    + "greeting, a clear body, and a polite sign-off. " + instr).strip();
        }
        if (o.summary.isSelected()) {
            return styleRewriteService.summarizeMeeting(text, instr);
        }
        return styleRewriteService.applyEditor(text, o.grammar.isSelected(), o.compact.isSelected(),
                o.detailed.isSelected(), o.professional.isSelected(), o.bullets.isSelected(),
                o.selectedStyles(), instr);
    }

    private JLabel themedLabel(String text) {
        var label = new JLabel(text);
        themedLabels.add(label);
        return label;
    }

    /** Gives a button the app-wide uniform size, so all buttons match. */
    private static <T extends JButton> T sized(T button) {
        button.setPreferredSize(BUTTON_SIZE);
        button.setMinimumSize(BUTTON_SIZE);
        return button;
    }

    /**
     * Makes a curved (rounded) button and registers it so the theme keeps it
     * in step with light/dark mode. Use this for every main-window button.
     */
    private JButton button(String text) {
        RoundedButton b = new RoundedButton(text);
        themedButtons.add(b);
        return b;
    }

    /** Colours a rounded button for the current theme (foreground left alone for indicators). */
    private static void styleButton(JButton b, boolean dark) {
        b.setBackground(dark ? new java.awt.Color(0x3C4043) : new java.awt.Color(0xE8E8E8));
        if (!(b instanceof IndicatorButton)) {
            b.setForeground(dark ? new java.awt.Color(0xE6E6E6) : new java.awt.Color(0x1A1A1A));
        }
    }

    /** A curved (rounded) dialog button, coloured once for the given theme. */
    private JButton dialogButton(String text, boolean dark) {
        RoundedButton b = new RoundedButton(text);
        styleButton(b, dark);
        return b;
    }

    /** Gives a single-line text field a curved outline and a compact height. */
    private static void roundTextField(javax.swing.JTextField field) {
        field.setBorder(new RoundedBorder(10));
        field.setMargin(new java.awt.Insets(3, 8, 3, 8));
    }

    /** Gives a multi-line text area a curved outline (used inside a scroll pane). */
    private static void roundTextArea(javax.swing.text.JTextComponent area, JScrollPane scroll) {
        scroll.setBorder(new RoundedBorder(12));
    }

    /** Native file dialog: Finder sheet on macOS, Explorer dialog on Windows. */
    private String chooseFile(boolean save) {
        java.awt.FileDialog dialog = new java.awt.FileDialog(frame,
                save ? "Save file" : "Open file",
                save ? java.awt.FileDialog.SAVE : java.awt.FileDialog.LOAD);
        String current = filePathField.getText();
        if (current != null && !current.isBlank()) {
            java.io.File hint = new java.io.File(current.strip());
            dialog.setDirectory(hint.getParent());
            if (save) {
                dialog.setFile(hint.getName());
            }
        } else if (save) {
            dialog.setFile("edited.txt");
        }
        dialog.setVisible(true);
        if (dialog.getFile() == null) {
            return null;
        }
        return new java.io.File(dialog.getDirectory(), dialog.getFile()).getAbsolutePath();
    }

    private static final java.util.Set<String> ALLOWED_LOAD_TYPES =
            java.util.Set.of("txt", "doc", "docx");

    private void loadAssistFile() {
        String path = chooseFile(false);
        if (path == null) {
            return;
        }
        String ext = fileExtension(path);
        if (!ALLOWED_LOAD_TYPES.contains(ext)) {
            JOptionPane.showMessageDialog(frame,
                    "Only these file types can be loaded:\n\n    .txt, .doc, .docx\n\n"
                            + "The file you picked is a \"." + ext + "\" file.",
                    "Unsupported file type", JOptionPane.WARNING_MESSAGE);
            return;
        }
        filePathField.setText(path);
        composeStatus.setText("Loading…");
        notesExecutor.submit(() -> {
            try {
                String text = readDocument(java.nio.file.Path.of(path), ext);
                SwingUtilities.invokeLater(() -> {
                    composeFeed.setText(text);
                    composeFeed.setCaretPosition(0);
                    composeStatus.setText("Loaded " + path);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        composeStatus.setText("Could not load: " + e.getMessage()));
            }
        });
    }

    private static String fileExtension(String path) {
        String name = java.nio.file.Path.of(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    /**
     * Reads text from a .txt, .docx, or .doc file using only the JDK (no
     * document library), so it works the same on every OS. .docx is a zip of
     * XML — the text lives in word/document.xml; .doc is the legacy binary
     * format, from which we extract readable runs best-effort.
     */
    private static String readDocument(java.nio.file.Path file, String ext) throws java.io.IOException {
        switch (ext) {
            case "docx":
                return readDocx(file);
            case "doc":
                return readLegacyDoc(file);
            default:
                return java.nio.file.Files.readString(file);
        }
    }

    private static String readDocx(java.nio.file.Path file) throws java.io.IOException {
        try (var zip = new java.util.zip.ZipInputStream(java.nio.file.Files.newInputStream(file))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    String xml = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    // paragraphs and line breaks become newlines; then strip tags.
                    String text = xml.replaceAll("(?i)</w:p>", "\n")
                            .replaceAll("(?i)<w:br[^>]*/>", "\n")
                            .replaceAll("<[^>]+>", "");
                    return unescapeXml(text).strip();
                }
            }
        }
        return "";
    }

    private static String readLegacyDoc(java.nio.file.Path file) throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if (c == '\n' || c == '\r' || c == '\t' || (c >= 0x20 && c < 0x7F)) {
                run.append(c);
            } else {
                if (run.length() >= 4) {
                    out.append(run).append('\n');
                }
                run.setLength(0);
            }
        }
        if (run.length() >= 4) {
            out.append(run);
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    /**
     * Assist tab (merges the old Editor and Compose): type or paste content —
     * or Load a file — into the top box, tick options and/or write
     * instructions, click Apply, and the rewritten/summarised result appears in
     * the bottom box. Download saves that result. All processing runs off the
     * UI thread. The divider between the boxes is draggable.
     */
    private JPanel buildAssistTab() {
        composeResult = multiLineArea();
        composeFeed = multiLineArea();

        // File row: optional load of a text file into the content box. The path
        // field is curved and compact — it is only a short one-line hint.
        filePathField = new javax.swing.JTextField();
        filePathField.setToolTipText("A text file to load; the file name is reused when you Save");
        roundTextField(filePathField);
        JButton loadButton = button("Load");
        loadButton.setToolTipText("Load a .txt, .doc or .docx file into the content box");
        loadButton.addActionListener(e -> loadAssistFile());
        JPanel fileRow = new JPanel(new BorderLayout(6, 0));
        fileRow.add(themedLabel("File:"), BorderLayout.WEST);
        fileRow.add(filePathField, BorderLayout.CENTER);
        JPanel fileButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        fileButtons.add(sized(loadButton));
        fileRow.add(fileButtons, BorderLayout.EAST);
        fileRow.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 4, 8));

        composeChecks = new OptionChecks();
        composeInstructions = new javax.swing.JTextField(36);
        roundTextField(composeInstructions);
        JPanel instrRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        instrRow.add(themedLabel("Additional instructions:"));
        instrRow.add(composeInstructions);
        JPanel optionStack = new JPanel();
        optionStack.setLayout(new javax.swing.BoxLayout(optionStack, javax.swing.BoxLayout.Y_AXIS));
        optionStack.add(composeChecks.panel);
        optionStack.add(javax.swing.Box.createVerticalStrut(12)); // space before instructions
        optionStack.add(instrRow);

        JButton clearButton = button("Clear");
        clearButton.setToolTipText("Clear both boxes and unselect all options");
        clearButton.addActionListener(e -> clearCompose());
        JButton applyButton = button("Apply");
        applyButton.setToolTipText("Apply the checked options and instructions — click again any time to regenerate");
        applyButton.addActionListener(e -> composeApply());
        JButton saveButton = button("Save");
        saveButton.setToolTipText("Save the result to a file");
        saveButton.addActionListener(e -> downloadAssistFile());
        composeStatus = new JLabel(" ");
        composeStatus.setFont(composeStatus.getFont().deriveFont(java.awt.Font.PLAIN, 11f));
        // Button order: Clear, Apply, Save.
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        controls.add(sized(clearButton));
        controls.add(sized(applyButton));
        controls.add(sized(saveButton));

        JPanel south = new JPanel(new BorderLayout());
        south.add(optionStack, BorderLayout.NORTH);
        south.add(composeStatus, BorderLayout.CENTER);
        south.add(controls, BorderLayout.EAST);
        south.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));
        composeInstrRow = instrRow;
        composeOptionStack = optionStack;

        // Your content on TOP, the modified result below it.
        JPanel top = new JPanel(new BorderLayout());
        top.add(themedLabel("  Your content (type, paste, or Load a file):"), BorderLayout.NORTH);
        top.add(new JScrollPane(composeFeed,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),
                BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(themedLabel("  After modification:"), BorderLayout.NORTH);
        bottom.add(new JScrollPane(composeResult,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),
                BorderLayout.CENTER);
        composeSplit = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, top, bottom);
        composeSplit.setResizeWeight(0.5);

        composePanel = new JPanel(new BorderLayout());
        composePanel.add(fileRow, BorderLayout.NORTH);
        composePanel.add(composeSplit, BorderLayout.CENTER);
        composePanel.add(south, BorderLayout.SOUTH);
        composeTopPanel = top;
        composeBottomPanel = bottom;
        composeSouthPanel = south;
        composeControlsPanel = controls;
        editorFileRow = fileRow;
        return composePanel;
    }

    private void clearCompose() {
        composeFeed.setText("");
        composeResult.setText("");
        composeInstructions.setText("");
        composeChecks.clear();
        composeStatus.setText(" ");
    }

    private javax.swing.JTextArea multiLineArea() {
        var area = new JTextArea();
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        area.setMargin(new java.awt.Insets(8, 8, 8, 8));
        return area;
    }

    /**
     * Help tab: About, a Help link that opens the instructions window, and a
     * Feedback form that sends an email to the author.
     */
    private JScrollPane buildHelpTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 16, 14, 16));

        // Section 1 — About.
        panel.add(leftRow(sectionHeading("About")));
        panel.add(leftRow(themedLabel("Architecture & Design by Maruti, version 0.1")));
        panel.add(javax.swing.Box.createVerticalStrut(18));

        // Section 2 — Appearance (the Dark-mode toggle, moved off the top bar).
        panel.add(leftRow(sectionHeading("Appearance")));
        darkModeToggle.setText("Dark mode");
        panel.add(leftRow(darkModeToggle));
        panel.add(javax.swing.Box.createVerticalStrut(18));

        // Section 3 — Help (a link that opens the instructions window).
        panel.add(leftRow(sectionHeading("Help")));
        JLabel instructionsLink = linkLabel("Instructions to use ai-assist");
        instructionsLink.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                showInstructionsWindow();
            }
        });
        panel.add(leftRow(instructionsLink));
        panel.add(javax.swing.Box.createVerticalStrut(18));

        // Section 4 — Feedback.
        panel.add(leftRow(sectionHeading("Feedback")));
        feedbackArea = multiLineArea();
        ((javax.swing.text.AbstractDocument) feedbackArea.getDocument())
                .setDocumentFilter(new LengthLimitFilter(FEEDBACK_MAX_CHARS));
        JScrollPane feedbackScroll = new JScrollPane(feedbackArea);
        feedbackScroll.setPreferredSize(new java.awt.Dimension(560, 96));
        roundTextArea(feedbackArea, feedbackScroll);
        feedbackCount = themedLabel(FEEDBACK_MAX_CHARS + " characters left");
        feedbackCount.setFont(feedbackCount.getFont().deriveFont(11f));
        feedbackArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void update() {
                int left = FEEDBACK_MAX_CHARS - feedbackArea.getText().length();
                feedbackCount.setText(left + " characters left");
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
        });
        JPanel countRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        countRow.add(feedbackCount);
        JPanel feedbackBox = new JPanel(new BorderLayout());
        feedbackBox.add(feedbackScroll, BorderLayout.CENTER);
        feedbackBox.add(countRow, BorderLayout.SOUTH);
        feedbackBox.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 124));
        feedbackBox.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        helpPanels.add(feedbackBox);
        helpPanels.add(countRow);
        panel.add(feedbackBox);

        // A line of space between the text box and the rating.
        panel.add(javax.swing.Box.createVerticalStrut(10));
        ratingCombo = new javax.swing.JComboBox<>(new Integer[] {0, 1, 2, 3, 4, 5});
        JPanel ratingRow = leftRow(themedLabel("Over all rating to ai-assist :"), ratingCombo);
        panel.add(ratingRow);

        // Breathing room between the rating and the buttons.
        panel.add(javax.swing.Box.createVerticalStrut(14));

        JButton feedbackClear = button("Clear");
        feedbackClear.setToolTipText("Clear the feedback box and reset the rating");
        feedbackClear.addActionListener(e -> {
            feedbackArea.setText("");
            ratingCombo.setSelectedIndex(0);
            feedbackStatus.setText(" ");
        });
        feedbackSubmit = button("Submit");
        feedbackSubmit.addActionListener(e -> submitFeedback());
        feedbackStatus = new JLabel(" ");
        feedbackStatus.setFont(feedbackStatus.getFont().deriveFont(java.awt.Font.PLAIN, 11f));
        themedLabels.add(feedbackStatus);
        panel.add(leftRow(sized(feedbackClear), sized(feedbackSubmit), feedbackStatus));

        // A read-only copy of the last submitted feedback, shown after Submit.
        panel.add(javax.swing.Box.createVerticalStrut(14));
        submittedArea = multiLineArea();
        submittedArea.setEditable(false);
        JScrollPane submittedScroll = new JScrollPane(submittedArea);
        submittedScroll.setPreferredSize(new java.awt.Dimension(560, 96));
        submittedScroll.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 110));
        roundTextArea(submittedArea, submittedScroll);
        submittedPanel = new JPanel(new BorderLayout());
        submittedPanel.add(leftRow(themedLabel("Submitted:")), BorderLayout.NORTH);
        submittedPanel.add(submittedScroll, BorderLayout.CENTER);
        submittedPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        submittedPanel.setVisible(false);
        helpPanels.add(submittedPanel);
        panel.add(submittedPanel);

        helpPanel = panel;
        // Wrap the whole tab in a scroll pane so the buttons are always
        // reachable even when the window is at its small default size.
        helpScroll = new JScrollPane(panel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        helpScroll.setBorder(null);
        helpScroll.getVerticalScrollBar().setUnitIncrement(16);
        return helpScroll;
    }

    /** A bold, slightly larger heading label that also follows the theme. */
    private JLabel sectionHeading(String text) {
        JLabel label = themedLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
        return label;
    }

    /** A blue, underlined, hand-cursor label that behaves like a hyperlink. */
    private JLabel linkLabel(String text) {
        JLabel label = new JLabel("<html><u>" + text + "</u></html>");
        label.setForeground(new java.awt.Color(0x3B82F6));
        label.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        return label;
    }

    /** Left-aligned row that plays nicely inside the vertical BoxLayout. */
    private JPanel leftRow(java.awt.Component... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 40));
        for (java.awt.Component c : components) {
            row.add(c);
        }
        helpPanels.add(row);
        return row;
    }

    /** Caps a text component at a maximum number of characters. */
    private static final class LengthLimitFilter extends javax.swing.text.DocumentFilter {
        private final int max;

        LengthLimitFilter(int max) {
            this.max = max;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String text,
                                 javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException {
            replace(fb, offset, 0, text, attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text,
                            javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException {
            if (text == null) {
                text = "";
            }
            int room = max - (fb.getDocument().getLength() - length);
            if (room <= 0) {
                return;
            }
            String toInsert = text.length() > room ? text.substring(0, room) : text;
            fb.replace(offset, length, toInsert, attr);
        }
    }

    /**
     * Opens the instructions window: a searchable, read-only information box
     * plus a Close button. The search box highlights every match in the text.
     */
    private void showInstructionsWindow() {
        boolean dark = darkMode;
        java.awt.Color bg = dark ? new java.awt.Color(0x1E1E1E) : java.awt.Color.WHITE;
        java.awt.Color panelBg = dark ? new java.awt.Color(0x2B2B2B) : new java.awt.Color(0xF2F2F2);

        JDialog dialog = new JDialog(frame, "Instructions to use ai-assist", false);
        dialog.setSize(760, 600);
        dialog.setLocationRelativeTo(frame);

        JEditorPane info = new JEditorPane("text/html", instructionsHtml(dark));
        info.setEditable(false);
        info.setOpaque(true);
        info.setBackground(bg); // otherwise the pane stays white and dark-mode text is unreadable
        info.setCaretPosition(0);
        info.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) {
                openInBrowser(e.getURL().toString());
            }
        });
        JScrollPane infoScroll = new JScrollPane(info);
        infoScroll.getViewport().setBackground(bg);

        JTextField searchField = new JTextField(26);
        roundTextField(searchField);
        JButton searchButton = dialogButton("Search", dark);
        Runnable search = () -> highlightMatches(info, searchField.getText());
        searchButton.addActionListener(e -> search.run());
        searchField.addActionListener(e -> search.run());
        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        searchRow.setBackground(panelBg);
        JLabel searchLabel = new JLabel("Search:");
        searchLabel.setForeground(dark ? new java.awt.Color(0xE6E6E6) : java.awt.Color.BLACK);
        searchRow.add(searchLabel);
        searchRow.add(searchField);
        searchRow.add(sized(searchButton));

        JButton close = dialogButton("Close", dark);
        close.addActionListener(e -> dialog.dispose());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bottom.setBackground(panelBg);
        bottom.add(sized(close));

        dialog.getContentPane().setBackground(panelBg);
        dialog.setLayout(new BorderLayout());
        dialog.add(searchRow, BorderLayout.NORTH);
        dialog.add(infoScroll, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private final javax.swing.text.Highlighter.HighlightPainter searchPainter =
            new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new java.awt.Color(0xFFE082));

    /** Highlights every occurrence of the term and scrolls to the first. */
    private void highlightMatches(JEditorPane info, String term) {
        javax.swing.text.Highlighter highlighter = info.getHighlighter();
        highlighter.removeAllHighlights();
        if (term == null || term.isBlank()) {
            return;
        }
        try {
            javax.swing.text.Document doc = info.getDocument();
            String haystack = doc.getText(0, doc.getLength()).toLowerCase();
            String needle = term.strip().toLowerCase();
            int index = haystack.indexOf(needle);
            boolean first = true;
            while (index >= 0) {
                highlighter.addHighlight(index, index + needle.length(), searchPainter);
                if (first) {
                    info.setCaretPosition(index);
                    first = false;
                }
                index = haystack.indexOf(needle, index + needle.length());
            }
        } catch (javax.swing.text.BadLocationException ignored) {
            // out of range after an edit; nothing to highlight
        }
    }

    private void openInBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Exception e) {
            log.warn("Could not open {}: {}", url, e.getMessage());
        }
    }

    /** Sends the feedback as an email, with the online / offline behaviour. */
    private void submitFeedback() {
        String message = feedbackArea.getText();
        Integer rating = (Integer) ratingCombo.getSelectedItem();
        int rate = rating == null ? 0 : rating;
        setFeedbackControlsEnabled(false);
        new Thread(() -> {
            if (!hasInternet()) {
                SwingUtilities.invokeLater(() -> feedbackStatus.setText("No Internet"));
                sleepQuietly(2000);
                SwingUtilities.invokeLater(() -> {
                    feedbackStatus.setText(" ");
                    setFeedbackControlsEnabled(true);
                });
                return;
            }
            boolean sent;
            try {
                String subject = "Feedback on ai-assist with rating " + rate;
                feedbackMailSender.send(subject, feedbackBody(message, rate));
                sent = true;
            } catch (Exception e) {
                log.warn("Could not send the feedback email: {}", e.getMessage());
                sent = false;
            }
            if (!sent) {
                SwingUtilities.invokeLater(() -> feedbackStatus.setText("Send failed"));
                sleepQuietly(2500);
                SwingUtilities.invokeLater(() -> {
                    feedbackStatus.setText(" ");
                    setFeedbackControlsEnabled(true);
                });
                return;
            }
            SwingUtilities.invokeLater(() -> feedbackStatus.setText("Submitted"));
            sleepQuietly(1000);
            SwingUtilities.invokeLater(() -> {
                // Clear the input first so a successful submit always empties the
                // box (even if a later UI step were to fail).
                feedbackArea.setText("");
                ratingCombo.setSelectedIndex(0);
                feedbackStatus.setText(" ");
                setFeedbackControlsEnabled(true);
                // Then show a read-only copy of what was submitted, below the
                // buttons, until the next submission or the app closes.
                submittedArea.setText("Rating: " + rate + "/5\n\n"
                        + (message == null ? "" : message.strip()));
                submittedArea.setCaretPosition(0);
                submittedPanel.setVisible(true);
                helpPanel.revalidate();
            });
        }, "feedback-submit").start();
    }

    private void setFeedbackControlsEnabled(boolean enabled) {
        feedbackArea.setEnabled(enabled);
        feedbackSubmit.setEnabled(enabled);
        ratingCombo.setEnabled(enabled);
    }

    /** Body of the feedback email: message, rating, and machine context. */
    static String feedbackBody(String message, int rating) {
        String user = System.getProperty("user.name", "unknown");
        String ip;
        try {
            ip = java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            ip = "unknown";
        }
        String location = java.util.TimeZone.getDefault().getID()
                + " / " + java.util.Locale.getDefault().getDisplayCountry();
        return (message == null ? "" : message.strip())
                + "\n\n--"
                + "\nUser rating: " + rating + "/5"
                + "\nIP address: " + ip
                + "\nUser name: " + user
                + "\nLocation: " + location;
    }

    /** True when a well-known host is reachable within a short timeout. */
    private static boolean hasInternet() {
        for (String host : new String[] {"smtp.gmail.com", "google.com", "1.1.1.1"}) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, 443), 1500);
                return true;
            } catch (Exception ignored) {
                // try the next host
            }
        }
        return false;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shows the model-setup notice: which models are still missing, with
     * download links and where to put them. Skipped silently when everything
     * is already in place (unless {@code force}, from the Recheck button).
     */
    private void maybeShowModelNotice(boolean force) {
        com.aiassist.setup.ModelCatalog.Status status =
                com.aiassist.setup.ModelCatalog.scan(com.aiassist.audio.VoskModelManager.modelSearchRoots());
        if (!force && status.allRequiredPresent() && status.missingRecommended().isEmpty()) {
            return;
        }
        if (modelNoticeDialog != null && modelNoticeDialog.isShowing()) {
            modelNoticePane.setText(modelNoticeHtml(status));
            modelNoticePane.setCaretPosition(0);
            return;
        }
        buildModelNoticeDialog(status);
    }

    /**
     * Recheck: unpack any dropped model archives (synchronously, off the UI
     * thread), then re-scan and refresh the notice. Gives visible feedback so
     * the button clearly does something even when a large model is unpacking.
     */
    private void runRecheck(JButton recheck) {
        recheck.setEnabled(false);
        modelNoticePane.setText("<html><body style='font-family:sans-serif;font-size:12px;'>"
                + "<p>Checking… unpacking any model archives you added. This can take a minute "
                + "for a large model.</p></body></html>");
        new Thread(() -> {
            try {
                liveTranscription.unpackDroppedModelsNow();
            } catch (Exception ex) {
                log.warn("Recheck could not unpack dropped models: {}", ex.getMessage());
            }
            SwingUtilities.invokeLater(() -> {
                recheck.setEnabled(true);
                maybeShowModelNotice(true);
            });
        }, "model-recheck").start();
    }

    private void buildModelNoticeDialog(com.aiassist.setup.ModelCatalog.Status status) {
        modelNoticeDialog = new javax.swing.JDialog(frame, "ai-assist — set up your models", false);
        modelNoticePane = new JEditorPane("text/html", modelNoticeHtml(status));
        modelNoticePane.setEditable(false);
        modelNoticePane.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));
        modelNoticePane.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) {
                openInBrowser(e.getURL().toString());
            }
        });
        JScrollPane scroll = new JScrollPane(modelNoticePane);
        scroll.setPreferredSize(new java.awt.Dimension(560, 380));

        JButton openFolder = dialogButton("Open", darkMode);
        openFolder.setToolTipText("Open the models folder");
        openFolder.addActionListener(e -> openFolder(com.aiassist.setup.UserPaths.modelsDir()));
        JButton recheck = dialogButton("Recheck", darkMode);
        recheck.setToolTipText("Unpack any dropped .zip models and check again");
        recheck.addActionListener(e -> runRecheck(recheck));
        JButton close = dialogButton("Close", darkMode);
        close.addActionListener(e -> modelNoticeDialog.dispose());
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        buttons.add(sized(openFolder));
        buttons.add(sized(recheck));
        buttons.add(sized(close));

        modelNoticeDialog.getContentPane().setLayout(new BorderLayout());
        modelNoticeDialog.getContentPane().add(scroll, BorderLayout.CENTER);
        modelNoticeDialog.getContentPane().add(buttons, BorderLayout.SOUTH);
        modelNoticeDialog.pack();
        modelNoticeDialog.setLocationRelativeTo(frame);
        modelNoticeDialog.setVisible(true);
    }

    private String modelNoticeHtml(com.aiassist.setup.ModelCatalog.Status status) {
        String modelsDir = com.aiassist.setup.UserPaths.modelsDir().toString();
        StringBuilder b = new StringBuilder("<html><body style='font-family:sans-serif;font-size:12px;'>");
        if (status.allRequiredPresent()) {
            b.append("<h2>Models ready</h2><p>All required models are in place. ")
                    .append("The recommended models below are optional upgrades.</p>");
        } else {
            b.append("<h2>Set up your models</h2>")
                    .append("<p>ai-assist ships with no models. It needs at least the required model ")
                    .append("below to produce live captions and notes.</p>");
        }
        b.append("<p>Put model files in this folder:<br><b>").append(escapeHtml(modelsDir)).append("</b></p>");
        appendModelList(b, "Required — still needed", status.missingRequired());
        appendModelList(b, "Recommended — optional upgrades", status.missingRecommended());
        if (!status.present().isEmpty()) {
            b.append("<h3>Already installed</h3><ul>");
            for (var m : status.present()) {
                b.append("<li>").append(escapeHtml(m.title())).append("</li>");
            }
            b.append("</ul>");
        }
        b.append("<p style='color:#666;'>Tip: drop a Vosk <code>.zip</code> straight into the models folder — ")
                .append("the app unpacks it and moves the <code>.zip</code> to ")
                .append("<b>Documents/meeting-notes/model-backups</b> for safekeeping. ")
                .append("Press <b>Recheck</b> after adding files.</p>");
        return b.append("</body></html>").toString();
    }

    private void appendModelList(StringBuilder b, String heading, java.util.List<com.aiassist.setup.ModelCatalog.ModelSpec> models) {
        if (models.isEmpty()) {
            return;
        }
        b.append("<h3>").append(heading).append("</h3><ul>");
        for (var m : models) {
            b.append("<li><b>").append(escapeHtml(m.title())).append("</b> — ").append(escapeHtml(m.purpose()))
                    .append(".<br>File: <code>").append(escapeHtml(m.fileName())).append("</code>")
                    .append(" &nbsp; <a href='").append(m.downloadUrl()).append("'>Download page</a><br>")
                    .append(escapeHtml(m.instructions())).append("</li>");
        }
        b.append("</ul>");
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Opens a folder in the OS file manager, best-effort. */
    private void openFolder(java.nio.file.Path dir) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(dir.toFile());
            }
        } catch (Exception e) {
            log.warn("Could not open the folder {}: {}", dir, e.getMessage());
        }
    }

    /** Opens a file in the OS default application, best-effort. */
    private void openFile(java.nio.file.Path file) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(file.toFile());
            }
        } catch (Exception e) {
            log.warn("Could not open the file {}: {}", file, e.getMessage());
        }
    }

    /** Shows the clickable "open saved notes" link for a saved file. */
    private void showSavedNotesLink(String savedPath) {
        if (savedPath == null || savedPath.isBlank()) {
            return;
        }
        lastSavedNotes = java.nio.file.Path.of(savedPath);
        notesLink.setText("<html><u>Open saved notes: "
                + escapeHtml(lastSavedNotes.getFileName().toString()) + "</u></html>");
        notesLink.setToolTipText(savedPath);
        notesLink.setVisible(true);
        if (statusStackPanel != null) {
            statusStackPanel.revalidate();
        }
    }

    private void clearSavedNotesLink() {
        lastSavedNotes = null;
        if (notesLink != null) {
            notesLink.setVisible(false);
        }
    }

    /** The complete, read-only instructions shown in the Help window. */
    private String instructionsHtml(boolean dark) {
        String fg = dark ? "#E6E6E6" : "#1A1A1A";
        String link = dark ? "#6FA8FF" : "#1565C0";
        String heading = dark ? "#FFFFFF" : "#000000";
        return "<html><body style='font-family:sans-serif;font-size:12px;color:" + fg
                + ";margin:10px;'>"
                + "<h2 style='color:" + heading + ";'>ai-assist — how to use it</h2>"
                + "<p>Follow these steps in order the first time. Each one is a small, self-contained step.</p>"

                + "<h3 style='color:" + heading + ";'>Step group A — first-time setup (do once)</h3>"
                + "<ol>"
                + "<li>When the app opens, read the <b>Set up your models</b> notice.</li>"
                + "<li>Click <b>Open models folder</b> to see where model files go.</li>"
                + "<li>In the notice, click the <b>Download</b> link next to the <b>required</b> speech "
                + "model. Your browser downloads a <code>.zip</code> file.</li>"
                + "<li>Move that <code>.zip</code> into the models folder from step 2.</li>"
                + "<li>Come back to the app and press <b>Recheck</b>. The app unpacks the model and moves "
                + "the <code>.zip</code> into <code>Documents/meeting-notes/model-backups</code>.</li>"
                + "<li>(Optional) Repeat steps 3–5 for the <b>recommended</b> models to get a more accurate "
                + "transcript and richer summaries.</li>"
                + "<li>When the notice says the models are ready, close it. You are set up.</li>"
                + "</ol>"

                + "<h3 style='color:" + heading + ";'>Step group B — record a meeting (Meeting tab)</h3>"
                + "<ol>"
                + "<li>Open the <b>Meeting</b> tab.</li>"
                + "<li>The <b>Title</b> defaults to <i>Minutes of meeting</i>; change it if you like — a "
                + "timestamp is added automatically when the file is saved.</li>"
                + "<li>Pick a speech model from the <b>Model:</b> dropdown (top-right). Your choice is "
                + "remembered.</li>"
                + "<li>Press <b>Start</b> — or leave <b>Auto-start</b> ticked and the app starts by itself "
                + "when a meeting app (Teams, Webex, Zoom) is playing audio.</li>"
                + "<li>The transcript shows a single <b>Recording started</b> date/time header at the top; "
                + "individual lines are not timestamped. <b>Your own speech is shown in blue</b>; the other "
                + "participants use the normal colour.</li>"
                + "<li>Need a pause? Press <b>Pause</b>, then <b>Start</b> again to resume.</li>"
                + "<li>Want a summary so far without stopping? Click <b>Apply</b>.</li>"
                + "<li>When the meeting ends, press <b>Stop</b>, then choose <b>Save</b> (or <b>No</b> to "
                + "discard, or <b>Cancel</b> to keep going).</li>"
                + "<li>A <b>Saving…</b> indicator shows while the file is written; then an "
                + "<b>Open saved notes</b> link appears. Notes are saved in "
                + "<code>Documents/meeting-notes</code> as a timestamped file.</li>"
                + "</ol>"

                + "<h3 style='color:" + heading + ";'>Step group C — improve or summarize text (Compose tab)</h3>"
                + "<ol>"
                + "<li>Open the <b>Compose</b> tab.</li>"
                + "<li>Type or paste text into the top box, or press <b>Load</b> to open a "
                + "<code>.txt</code>, <code>.doc</code>, or <code>.docx</code> file.</li>"
                + "<li>Tick the options you want — e.g. <b>Grammar</b>, <b>Professional</b>, "
                + "<b>Bullet points</b>, <b>Summary</b>, <b>Email</b>, or a communication style.</li>"
                + "<li>(Optional) Type <b>Additional instructions</b> for anything the tick-boxes don't cover.</li>"
                + "<li>Click <b>Apply</b>. The result appears in the <b>After modification</b> box. Click "
                + "<b>Apply</b> again any time to regenerate.</li>"
                + "<li>Press <b>Save</b> to save the result, or <b>Clear</b> to start over.</li>"
                + "</ol>"

                + "<h3 style='color:" + heading + ";'>Models — what to download and where</h3>"
                + "<p>Place these in your models folder (Open the folder from the setup notice). The app "
                + "picks them up automatically; a Vosk <code>.zip</code> is unpacked for you.</p>"
                + "<ul>"
                + "<li><b>Speech (required)</b> — file <code>vosk-model-small-en-us-0.15.zip</code>, from "
                + "<a href='https://alphacephei.com/vosk/models'>alphacephei.com/vosk/models</a>.</li>"
                + "<li><b>Transcript (recommended)</b> — file <code>ggml-base.bin</code>, from the "
                + "<a href='https://github.com/NoMercy-Entertainment/nomercy-whisper-models/releases'>GitHub "
                + "mirror</a> (no Hugging Face needed).</li>"
                + "<li><b>AI model (recommended)</b> — a single GGUF instruct model such as "
                + "<code>qwen2.5-1.5b-instruct-q4_k_m.gguf</code>; see the "
                + "<a href='https://github.com/marutisridharjob/ai-assist/blob/main/models/README.md'>models "
                + "guide</a>.</li>"
                + "</ul>"

                + "<h3 style='color:" + heading + ";'>Good to know</h3>"
                + "<ul>"
                + "<li>Everything runs on your machine — no audio or text ever leaves it.</li>"
                + "<li>Switch light/dark on the <b>Settings</b> tab under <b>Appearance</b>.</li>"
                + "<li>When you are muted, room noise on your mic is ignored unless you speak up.</li>"
                + "<li>Slow steps (transcribing, drafting, saving) run in the background, so the app stays "
                + "responsive.</li>"
                + "</ul>"

                + "<h3 style='color:" + heading + ";'>Open-source licenses</h3>"
                + "<p>ai-assist and everything it bundles is open source. Licenses (click for the full text):</p>"
                + "<ul>"
                + "<li>Vosk speech engine &amp; models — "
                + "<a href='https://www.apache.org/licenses/LICENSE-2.0'>Apache-2.0</a></li>"
                + "<li>Whisper / whisper.cpp &amp; whisper-jni — "
                + "<a href='https://opensource.org/license/mit'>MIT</a></li>"
                + "<li>llama.cpp &amp; de.kherud:llama — "
                + "<a href='https://opensource.org/license/mit'>MIT</a></li>"
                + "<li>JNA — <a href='https://www.apache.org/licenses/LICENSE-2.0'>Apache-2.0</a> / "
                + "<a href='https://opensource.org/license/lgpl-2-1'>LGPL-2.1</a></li>"
                + "<li>Spring Boot &amp; Jackson — "
                + "<a href='https://www.apache.org/licenses/LICENSE-2.0'>Apache-2.0</a></li>"
                + "<li>Java runtime (OpenJDK) — "
                + "<a href='https://openjdk.org/legal/gplv2+ce.html'>GPLv2 with Classpath Exception</a></li>"
                + "<li>ai-assist source — see the "
                + "<a href='https://github.com/marutisridharjob/ai-assist'>GitHub repository</a></li>"
                + "</ul>"

                + "<p style='color:" + link + ";'>Tip: use the Search box above to jump to any word on this page.</p>"
                + "</body></html>";
    }

    private void composeApply() {
        String feed = composeFeed.getText();
        if (feed == null || feed.isBlank()) {
            composeStatus.setText("Type or paste content into the top box first.");
            return;
        }
        boolean summary = composeChecks.summary.isSelected();
        composeStatus.setText(summary ? "Summarizing…" : "Applying…");
        new Thread(() -> {
            try {
                String result = runOptions(composeChecks, feed, composeInstructions.getText());
                String llm = "  [LLM: " + styleRewriteService.llmReport() + "]";
                SwingUtilities.invokeLater(() -> {
                    composeResult.setText(result);
                    composeResult.setCaretPosition(0);
                    composeStatus.setText((summary ? "Summary ready." : "Applied.") + llm);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        composeStatus.setText("Could not apply: " + ex.getMessage()));
            }
        }, "compose-apply").start();
    }

    private static final String UNPACKING_SUFFIX = " (unpacking — wait)";

    /** Fills the model dropdown from the built-in default plus ./models. */
    private void populateModels() {
        updatingModels = true;
        try {
            modelCombo.removeAllItems();
            for (String name : liveTranscription.availableModels()) {
                modelCombo.addItem(name);
            }
            for (String name : liveTranscription.unpackingModels()) {
                modelCombo.addItem(name + UNPACKING_SUFFIX);
            }
            modelCombo.setSelectedItem(liveTranscription.activeModelName());
        } finally {
            updatingModels = false;
        }
    }

    /** Applies the title field to the current meeting (drives the file name). */
    private void applyTitle() {
        String sessionId = liveTranscription.status().sessionId();
        String title = titleField.getText();
        if (sessionId == null || title == null || title.isBlank()) {
            return;
        }
        try {
            sessions.get(sessionId).rename(title);
        } catch (Exception e) {
            // ended or unknown session; nothing to rename
        }
    }

    /** Pulls new utterances and capture state into the window once a second. */
    private void refresh() {
        // Scan for a running meeting app every ~5 s (cheap, best-effort).
        // refresh() runs ~4x/s, so 20 ticks ≈ 5 s.
        if (detectorCountdown-- <= 0) {
            detectorCountdown = 20;
            detectedMeetingApp = MeetingAppDetector.detectRunningMeetingApp().orElse(null);
            modelsAvailable = !liveTranscription.availableModels().isEmpty();
        }
        // First-start extraction: progress bar until the dropped zips are ready.
        var unpackingNow = liveTranscription.unpackingModels();
        boolean extracting = !unpackingNow.isEmpty();
        if (extractionPanel.isVisible() != extracting) {
            extractionPanel.setVisible(extracting);
            frame.revalidate();
        }
        if (extracting) {
            extractionLabel.setText("  Extracting model(s): " + String.join(", ", unpackingNow) + " ");
        }
        var partials = liveTranscription.partials();
        captionLabel.setText(partials.isEmpty() ? " "
                : "hearing…  " + partials.entrySet().stream()
                        .map(e -> sourceName(e.getKey()) + ": " + e.getValue())
                        .reduce((a, b) -> a + "    " + b)
                        .orElse(" "));
        LiveTranscriptionService.Status status = liveTranscription.status();
        updateMeetingIndicator(status);
        updateAutoStart(status);
        if (!meetingCompleted) {
            setStatus(switch (status.state()) {
                case PREPARING -> status.detail() != null ? status.detail() : "Preparing speech model…";
                case LISTENING -> listeningMessage(status);
                case PAUSED -> "Paused — press Start to continue";
                case ERROR -> "Audio problem: " + status.detail();
                case IDLE -> modelsAvailable
                        ? "Click Start to begin"
                        : "No speech model found — place a Vosk model zip or folder in the models folder"
                          + " (it extracts automatically)";
            }, status.state() == LiveTranscriptionService.State.ERROR
                    || (status.state() == LiveTranscriptionService.State.IDLE && !modelsAvailable));
            // Green text = press me now, red = not applicable:
            //   idle/finished → only Start green;
            //   listening     → Pause and Stop green, Start red;
            //   paused        → Resume (Start) and Stop green, Pause red.
            boolean capturing = status.state() == LiveTranscriptionService.State.LISTENING
                    || status.state() == LiveTranscriptionService.State.PREPARING;
            boolean paused = status.state() == LiveTranscriptionService.State.PAUSED;
            pauseButton.setEnabled(capturing);
            stopButton.setEnabled(capturing || paused);
            startButton.setText(paused ? "Resume" : "Start");
        }
        boolean startableState = meetingCompleted
                || status.state() == LiveTranscriptionService.State.IDLE
                || status.state() == LiveTranscriptionService.State.ERROR
                || status.state() == LiveTranscriptionService.State.PAUSED;
        startButton.setEnabled(startableState
                && (modelsAvailable || status.state() == LiveTranscriptionService.State.PAUSED));
        String sessionId = status.sessionId();
        if (sessionId == null) {
            return;
        }
        ListeningSession session;
        try {
            session = sessions.get(sessionId);
        } catch (Exception e) {
            return;
        }
        if (!sessionId.equals(renderedSessionId)) {
            renderedSessionId = sessionId;
            renderedUtterances = 0;
            transcriptHeaderWritten = false;
            meetingStartAt = java.time.Instant.now();
            // New meeting: seed the title field (default "Minutes of meeting").
            titleField.setText(session.topic());
        }
        List<Utterance> utterances = session.utterances();
        if (!transcriptHeaderWritten && !utterances.isEmpty()) {
            writeTranscriptHeader();
            transcriptHeaderWritten = true;
        }
        for (int i = renderedUtterances; i < utterances.size(); i++) {
            appendTranscriptEntry(utterances.get(i));
        }
        if (utterances.size() > renderedUtterances) {
            renderedUtterances = utterances.size();
            transcript.setCaretPosition(transcript.getStyledDocument().getLength());
        }
    }

    /** Date + time header written once, when the recording starts. */
    private static final java.time.format.DateTimeFormatter HEADER_TIME =
            java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm")
                    .withZone(java.time.ZoneId.systemDefault());

    /**
     * Writes a single "Recording started …" header with the date and time. The
     * individual lines below it carry no per-line timestamp — the header alone
     * marks when the meeting began.
     */
    private void writeTranscriptHeader() {
        java.time.Instant when = meetingStartAt != null ? meetingStartAt : java.time.Instant.now();
        java.awt.Color stamp = darkMode ? new java.awt.Color(0x9AA0A6) : new java.awt.Color(0x808080);
        try {
            insertStyled(transcript.getStyledDocument(),
                    "Recording started: " + HEADER_TIME.format(when) + "\n\n", stamp, 11);
        } catch (javax.swing.text.BadLocationException ignored) {
            // best-effort append
        }
    }

    /**
     * Appends one utterance to the transcript. Your own speech ("you") is shown
     * in a darker blue; the other participants' speech uses the normal text
     * colour. No per-line timestamp and no [you]/[other] tags — the colour
     * distinguishes the speaker and the header marks the start time.
     */
    private void appendTranscriptEntry(Utterance u) {
        javax.swing.text.StyledDocument doc = transcript.getStyledDocument();
        boolean you = "you".equals(u.speaker());
        java.awt.Color body = you
                ? (darkMode ? new java.awt.Color(0x6FA8FF) : new java.awt.Color(0x0D47A1))
                : (darkMode ? new java.awt.Color(0xE6E6E6) : new java.awt.Color(0x1A1A1A));
        try {
            insertStyled(doc, u.text() + "\n\n", body, 14);
        } catch (javax.swing.text.BadLocationException ignored) {
            // best-effort append
        }
    }

    private static void insertStyled(javax.swing.text.StyledDocument doc, String text,
                                     java.awt.Color color, int size) throws javax.swing.text.BadLocationException {
        var attrs = new javax.swing.text.SimpleAttributeSet();
        javax.swing.text.StyleConstants.setForeground(attrs, color);
        javax.swing.text.StyleConstants.setFontFamily(attrs, Font.SANS_SERIF);
        javax.swing.text.StyleConstants.setFontSize(attrs, size);
        doc.insertString(doc.getLength(), text, attrs);
    }

    /** Friendly source name: "You" for your microphone, "Others" for the room. */
    private static String sourceName(String label) {
        return switch (label) {
            case "you" -> "You";
            case "other" -> "Others";
            default -> label;
        };
    }

    /**
     * Status banner: a blinking "saving notes" indicator (shown on every tab
     * while the notes file is being written in the background), or the
     * meeting-in-progress / paused banner on the non-meeting tabs.
     */
    private void updateMeetingIndicator(LiveTranscriptionService.Status status) {
        boolean onMeetingTab = tabs.getSelectedIndex() == 0;
        boolean active = !meetingCompleted
                && (status.state() == LiveTranscriptionService.State.LISTENING
                    || status.state() == LiveTranscriptionService.State.PREPARING);
        boolean paused = !meetingCompleted
                && status.state() == LiveTranscriptionService.State.PAUSED;
        boolean saving = savingNotes;
        boolean meetingBanner = !onMeetingTab && (active || paused);
        boolean show = saving || meetingBanner;
        if (indicatorPanel.isVisible() != show) {
            indicatorPanel.setVisible(show);
            frame.revalidate();
        }
        if (!show) {
            return;
        }
        if (saving) {
            // Highest priority: tell the user the notes are still being written.
            blinkOn = !blinkOn;
            meetingIndicator.setText("⏳ Saving meeting notes…");
            meetingIndicator.setForeground(blinkOn
                    ? new java.awt.Color(0x2D6CDF)
                    : (darkMode ? new java.awt.Color(0x2A3A5A) : new java.awt.Color(0xAEC4EC)));
        } else if (active) {
            blinkOn = !blinkOn;
            meetingIndicator.setText("● MEETING IN PROGRESS");
            meetingIndicator.setForeground(blinkOn
                    ? new java.awt.Color(0xE74C3C)
                    : (darkMode ? new java.awt.Color(0x5A2A2A) : new java.awt.Color(0xF5C6C2)));
        } else {
            meetingIndicator.setText("❚❚ Meeting paused");
            meetingIndicator.setForeground(new java.awt.Color(0xE67E22));
        }
    }

    /**
     * Live status while listening: which sources are open and how loud each
     * one currently is, plus a warning when the app has heard only silence
     * for a while — the usual macOS causes being speaker volume and the
     * Control Center Mic Mode set to "Voice Isolation", which strips the
     * meeting audio out of the microphone signal.
     */
    /** Compact: connected source per label with its live level, nothing more. */
    private String listeningMessage(LiveTranscriptionService.Status status) {
        var levels = liveTranscription.levels();
        int loudest = levels.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        silentCycles = loudest < 3 ? silentCycles + 1 : 0;
        StringBuilder message = new StringBuilder("Listening — ");
        boolean first = true;
        for (String device : status.devices()) {
            int open = device.lastIndexOf('[');
            int close = device.lastIndexOf(']');
            String label = open >= 0 && close > open ? device.substring(open + 1, close) : device;
            if (!first) {
                message.append("  ·  ");
            }
            first = false;
            message.append(label).append(" ").append(levels.getOrDefault(label, 0)).append("%");
        }
        boolean hasOtherSource = status.devices().stream().anyMatch(d -> d.contains("[other]"));
        if (!hasOtherSource) {
            message.append("  ·  no meeting-audio source (mic only)");
        }
        if (silentCycles >= 8) {
            message.append("  ·  hearing silence — check volume / Mic Mode");
        }
        if (liveTranscription.modelNote() != null) {
            message.append("  ·  ").append(liveTranscription.modelNote());
        }
        return message.toString();
    }

    /**
     * Shows status on its own line above the buttons; errors appear in red.
     * HTML rendering makes long messages wrap across lines instead of
     * pushing into or under the buttons.
     */
    private void setStatus(String message, boolean error) {
        lastStatusMessage = message;
        lastStatusWasError = error;
        String escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        statusLabel.setText("<html><body style='width: 640px'>" + escaped + "</body></html>");
        statusLabel.setForeground(error
                ? (darkMode ? new java.awt.Color(0xFF6B6B) : new java.awt.Color(0xB00020))
                : (darkMode ? new java.awt.Color(0xC8C8C8) : java.awt.Color.DARK_GRAY));
        // The bottom panel re-lays out on setText, taking the height the
        // wrapped message needs — the buttons keep their own row below.
    }

    /**
     * A curved (rounded) button drawn the same on every OS. It paints its own
     * rounded background from {@link #getBackground()} (so the theme controls
     * the colour) with light press/hover feedback, then lets the base class
     * paint the label on top.
     */
    private static class RoundedButton extends JButton {

        private static final int ARC = 18;

        RoundedButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setBackground(new java.awt.Color(0xE8E8E8));
            setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 6, 5, 6));
            // Plain (not bold) label — Metal makes button text bold by default.
            if (getFont() != null) {
                setFont(getFont().deriveFont(java.awt.Font.PLAIN));
            }
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.Color base = getBackground();
            if (getModel().isPressed()) {
                base = base.darker();
            } else if (getModel().isRollover()) {
                base = lighten(base);
            }
            int w = getWidth();
            int h = getHeight();
            g2.setColor(base);
            g2.fillRoundRect(0, 0, w - 1, h - 1, ARC, ARC);
            g2.setColor(base.darker());
            g2.drawRoundRect(0, 0, w - 1, h - 1, ARC, ARC);
            g2.dispose();
            super.paintComponent(g);
        }

        private static java.awt.Color lighten(java.awt.Color c) {
            return new java.awt.Color(Math.min(255, c.getRed() + 16),
                    Math.min(255, c.getGreen() + 16), Math.min(255, c.getBlue() + 16));
        }
    }

    /**
     * Button whose label text is green when the action is available right
     * now and red when it is not — curved like the rest, readable on the
     * native look and feel of both macOS and Windows.
     */
    private static final class IndicatorButton extends RoundedButton {

        private static final java.awt.Color ACTIVE = new java.awt.Color(0x1E8E3E);
        private static final java.awt.Color INACTIVE = new java.awt.Color(0xC62828);

        private IndicatorButton(String text) {
            super(text);
            setForeground(INACTIVE);
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setForeground(enabled ? ACTIVE : INACTIVE);
        }
    }

    /** A simple rounded line border, matched to the field's own colours. */
    private static final class RoundedBorder implements javax.swing.border.Border {
        private final int arc;

        RoundedBorder(int arc) {
            this.arc = arc;
        }

        @Override
        public void paintBorder(java.awt.Component c, java.awt.Graphics g, int x, int y, int w, int h) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.Color line = c.getBackground() == null ? java.awt.Color.GRAY
                    : (isDarkish(c.getBackground()) ? new java.awt.Color(0x5A5A5A) : new java.awt.Color(0xB0B0B0));
            g2.setColor(line);
            g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
            g2.dispose();
        }

        @Override
        public java.awt.Insets getBorderInsets(java.awt.Component c) {
            return new java.awt.Insets(4, 8, 4, 8);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }

        private static boolean isDarkish(java.awt.Color c) {
            return (c.getRed() + c.getGreen() + c.getBlue()) / 3 < 128;
        }
    }

    /** Light/dark palette applied to every part of the window. */
    /** Colours the active tab a clear green so it stands out (esp. in dark mode). */
    private void updateTabColors() {
        if (tabs == null) {
            return;
        }
        java.awt.Color activeGreen = darkMode ? new java.awt.Color(0x69D08A) : new java.awt.Color(0x2E7D32);
        java.awt.Color normal = darkMode ? new java.awt.Color(0xC8C8C8) : new java.awt.Color(0x1A1A1A);
        int selected = tabs.getSelectedIndex();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            tabs.setForegroundAt(i, i == selected ? activeGreen : normal);
        }
    }

    private void applyTheme(boolean dark) {
        darkMode = dark;
        java.awt.Color textBg = dark ? new java.awt.Color(0x1E1E1E) : java.awt.Color.WHITE;
        java.awt.Color textFg = dark ? new java.awt.Color(0xE6E6E6) : java.awt.Color.BLACK;
        java.awt.Color panelBg = dark ? new java.awt.Color(0x2B2B2B) : new java.awt.Color(0xF2F2F2);
        java.awt.Color muted = dark ? new java.awt.Color(0x9A9A9A) : java.awt.Color.GRAY;

        transcript.setBackground(textBg);
        transcript.setForeground(textFg);
        // Re-render the transcript so the per-speaker colours match the new theme.
        if (renderedSessionId != null) {
            transcript.setText("");
            renderedUtterances = 0;
            transcriptHeaderWritten = false; // header is re-written on re-render
        }
        transcript.setCaretColor(textFg);
        summaryArea.setBackground(textBg);
        summaryArea.setForeground(textFg);
        meetingSummaryPane.setOpaque(true);
        meetingSummaryPane.setBackground(panelBg);
        meetingSplit.setBackground(panelBg);
        titleField.setBackground(textBg);
        titleField.setForeground(textFg);
        titleField.setCaretColor(textFg);
        for (JPanel panel : java.util.List.of(topPanel, bottomPanel, buttonsPanel, controlsPanel,
                editorFileRow, autoStartPanel, statusStackPanel,
                composePanel, composeTopPanel, composeBottomPanel, composeSouthPanel,
                composeChecks.panel, composeInstrRow, composeOptionStack,
                composeControlsPanel, indicatorPanel, southWrapPanel,
                meetingTabPanel, meetingTopRow, extractionPanel)) {
            // Aqua only honors panel backgrounds when the panel is opaque.
            panel.setOpaque(true);
            panel.setBackground(panelBg);
        }
        for (JPanel row : meetingButtonRows) {
            row.setOpaque(true);
            row.setBackground(panelBg);
        }
        for (JTextArea area : java.util.List.of(composeResult, composeFeed, feedbackArea, submittedArea)) {
            area.setBackground(textBg);
            area.setForeground(textFg);
            area.setCaretColor(textFg);
        }
        // Keep the dropdowns light so their down-arrow stays visible in dark mode.
        java.awt.Color comboBg = new java.awt.Color(0xF2F2F2);
        java.awt.Color comboFg = new java.awt.Color(0x1A1A1A);
        ratingCombo.setBackground(comboBg);
        ratingCombo.setForeground(comboFg);
        if (helpPanel != null) {
            helpPanel.setOpaque(true);
            helpPanel.setBackground(panelBg);
        }
        for (JPanel panel : helpPanels) {
            panel.setOpaque(true);
            panel.setBackground(panelBg);
        }
        composeInstructions.setBackground(textBg);
        composeInstructions.setForeground(textFg);
        composeInstructions.setCaretColor(textFg);
        // Every static label and checkbox follows the theme (Aqua does not
        // restyle them by itself, which left labels dark-on-dark on macOS).
        for (JLabel label : themedLabels) {
            label.setForeground(textFg);
        }
        for (javax.swing.JCheckBox check : themedChecks) {
            check.setForeground(textFg);
            check.setBackground(panelBg);
        }
        composeSplit.setBackground(panelBg);
        composeStatus.setForeground(muted);
        modelCombo.setBackground(comboBg);
        modelCombo.setForeground(comboFg);
        tabs.setBackground(panelBg);
        tabs.setForeground(textFg);
        updateTabColors();
        filePathField.setBackground(textBg);
        filePathField.setForeground(textFg);
        filePathField.setCaretColor(textFg);
        frame.getContentPane().setBackground(panelBg);
        titleLabel.setForeground(textFg);
        captionLabel.setForeground(muted);
        darkModeToggle.setBackground(panelBg);
        darkModeToggle.setForeground(textFg);
        autoStartToggle.setBackground(panelBg);
        autoStartToggle.setForeground(textFg);
        // Curved buttons follow the theme (Start/Pause/Stop keep their green/red
        // label colour; styleButton leaves indicator foregrounds alone).
        for (JButton b : themedButtons) {
            styleButton(b, dark);
        }
        if (helpScroll != null) {
            helpScroll.setOpaque(true);
            helpScroll.setBackground(panelBg);
            helpScroll.getViewport().setBackground(panelBg);
        }
        setStatus(lastStatusMessage, lastStatusWasError);
        frame.repaint();
    }

    /** Begins a fresh meeting (a new session), e.g. after Stop or a startup error. */
    private void startMeeting() {
        setMonitorWanted(false); // free the system-audio source for the meeting
        clearSavedNotesLink();   // the previous meeting's link goes away now
        try {
            if (liveTranscription.status().state() == LiveTranscriptionService.State.PAUSED
                    && !meetingCompleted) {
                liveTranscription.resume(); // Start continues a paused meeting
                setStatus("Resumed.", false);
                return;
            }
            liveTranscription.start(null, null);
            meetingCompleted = false;
            transcript.setText("");
            renderedUtterances = 0;
            renderedSessionId = null;
            setStatus("Starting a new meeting…", false);
        } catch (Exception e) {
            setStatus("Could not start: " + e.getMessage(), true);
        }
    }

    private void togglePause() {
        if (liveTranscription.status().state() == LiveTranscriptionService.State.LISTENING
                || liveTranscription.status().state() == LiveTranscriptionService.State.PREPARING) {
            liveTranscription.pause(); // resuming is Start's job
        }
    }

    /** Stop = the meeting is complete: draft the full notes and save the file. */
    private void stopMeeting() {
        if (meetingCompleted) {
            return;
        }
        // Save / No / Cancel: Save ends and writes the notes file, No ends
        // without saving, Cancel keeps the meeting running. Explicit button
        // labels because the plain confirm dialog rendered without visible
        // buttons for some users on Windows.
        Object[] choices = {"Save", "No", "Cancel"};
        int choice = JOptionPane.showOptionDialog(frame,
                "End the meeting? Save writes the notes file to your Documents/meeting-notes folder; "
                        + "No ends without saving.",
                "Meeting complete", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, choices, choices[0]);
        if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
            return; // Cancel — keep the meeting running
        }
        boolean save = (choice == 0);
        // Fast part: stop capture and grab the recording now, so the app is
        // immediately ready for another meeting.
        MeetingEndService.PendingNotes pending;
        try {
            pending = meetingEndService.stopCurrentCapture();
        } catch (Exception e) {
            String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            setStatus("Could not stop the meeting: " + message, true);
            return;
        }
        meetingCompleted = true;
        pauseButton.setEnabled(false);
        stopButton.setEnabled(false);
        startButton.setEnabled(true);
        startButton.setText("Start");
        // Re-arm auto-start for the next meeting, but stay quiet for a cooldown
        // so it doesn't immediately re-prompt while a background app (Teams) is
        // still running right after this meeting ends.
        autoStartHandledApp = null;
        autoStartCooldownUntil = System.currentTimeMillis() + AUTO_START_COOLDOWN_MS;

        if (!save) {
            // No — end the meeting and discard the recording, nothing is written.
            summaryArea.setText("Meeting ended — notes were not saved.");
            setStatus("Meeting ended — notes were not saved. Start new meeting.", false);
            MeetingEndService.PendingNotes toDiscard = pending;
            notesExecutor.submit(() -> meetingEndService.discardNotes(toDiscard));
            return;
        }

        summaryArea.setText("Transcribing and drafting the notes in the background…");
        setStatus("Meeting stopped — drafting notes in the background. Start new meeting.", false);
        savingNotes = true; // drives the "⏳ Saving meeting notes…" indicator

        // Slow part: transcribe + draft + save off the UI thread. Serialised so
        // back-to-back meetings finish in order and share the Whisper/LLM engine.
        notesExecutor.submit(() -> {
            try {
                Draft draft = meetingEndService.finishNotes(pending, null);
                SwingUtilities.invokeLater(() -> {
                    savingNotes = false;
                    showSavedNotesLink(draft.savedTo());
                    boolean anotherMeetingLive = liveTranscription.status().sessionId() != null;
                    String savedMsg = draft.savedTo() != null
                            ? "Notes saved to " + draft.savedTo()
                            : "Meeting ended (file saving is disabled in configuration)";
                    if (!anotherMeetingLive) {
                        summaryArea.setText(summaryText(draft));
                        summaryArea.setCaretPosition(0);
                        setStatus(savedMsg, false);
                    } else {
                        // A new meeting is already running; don't disturb its
                        // live view, but still tell the user where the previous
                        // meeting's notes went (a non-blocking popup).
                        log.info("Previous meeting notes saved to {}", draft.savedTo());
                        if (draft.savedTo() != null) {
                            showBackgroundNote("Previous meeting notes saved to:\n" + draft.savedTo(), false);
                        }
                    }
                });
            } catch (Exception e) {
                String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                SwingUtilities.invokeLater(() -> {
                    savingNotes = false;
                    if (liveTranscription.status().sessionId() == null) {
                        summaryArea.setText("Could not save the notes: " + message);
                        setStatus("Could not save the notes: " + message, true);
                    } else {
                        // Never let a failed save vanish silently just because a
                        // new meeting is running.
                        log.warn("Could not save previous meeting notes: {}", message);
                        showBackgroundNote("The previous meeting's notes could not be saved:\n" + message
                                + "\n\nThis meeting is still running.", true);
                    }
                });
            }
        });
    }

    private static final long AUTO_START_COUNTDOWN_MS = 10_000;
    private static final long AUTO_START_COOLDOWN_MS = 10 * 60_000;

    /**
     * Opt-in hands-free start: when a meeting app is detected and the app is
     * idle, run a short cancelable countdown and then start capture. Never
     * triggers from the microphone — only from a detected meeting app.
     */
    private void updateAutoStart(LiveTranscriptionService.Status status) {
        if (autoStartToggle == null || !autoStartToggle.isSelected()) {
            if (autoStartDeadline > 0) {
                cancelAutoStartPrompt();
            }
            setMonitorWanted(false);
            return;
        }
        String app = detectedMeetingApp;
        // Per-meeting apps (Webex/Zoom) disappear between calls; re-arm then.
        if (app == null) {
            autoStartHandledApp = null;
        }
        boolean idle = !meetingCompleted
                && !savingNotes
                && status.sessionId() == null
                && status.state() == LiveTranscriptionService.State.IDLE
                && modelsAvailable;
        // Auto-start always waits for real meeting audio (the other participants
        // talking) before triggering — never the microphone.
        boolean gate = true;

        if (autoStartDeadline > 0) {
            long remaining = autoStartDeadline - System.currentTimeMillis();
            if (!idle || app == null) {
                cancelAutoStartPrompt(); // conditions changed (or the app closed)
                return;
            }
            if (remaining <= 0) {
                cancelAutoStartPrompt();
                startMeeting(); // hands-free start
                return;
            }
            autoStartLabel.setText("▶ " + app + " detected — starting in "
                    + (remaining / 1000 + 1) + "s");
            return;
        }

        // Armed and waiting. Are we clear to consider this app?
        boolean appReady = idle && app != null
                && System.currentTimeMillis() >= autoStartCooldownUntil
                && !app.equals(autoStartHandledApp);

        // Stronger mode: keep a lightweight system-audio monitor running while
        // we wait, and only trigger once the meeting is actually playing audio.
        setMonitorWanted(gate && appReady);
        boolean audioReady = !gate || liveTranscription.systemAudioActiveWithin(3000);

        if (appReady && audioReady) {
            autoStartHandledApp = app;
            autoStartDeadline = System.currentTimeMillis() + AUTO_START_COUNTDOWN_MS;
            setMonitorWanted(false); // release the audio source before the meeting opens it
            autoStartPanel.setVisible(true);
            frame.revalidate();
        }
    }

    /** Turns the background system-audio monitor on/off, off the UI thread. */
    private void setMonitorWanted(boolean wanted) {
        if (monitorWanted == wanted) {
            return;
        }
        monitorWanted = wanted;
        monitorControl.submit(() -> {
            try {
                if (wanted) {
                    liveTranscription.startActivityMonitor();
                } else {
                    liveTranscription.stopActivityMonitor();
                }
            } catch (Exception e) {
                log.warn("System-audio monitor control failed: {}", e.getMessage());
            }
        });
    }

    private void cancelAutoStartPrompt() {
        autoStartDeadline = 0;
        if (autoStartPanel != null && autoStartPanel.isVisible()) {
            autoStartPanel.setVisible(false);
            frame.revalidate();
        }
    }

    /** "Not now": hide the prompt and stay quiet for a while. */
    private void dismissAutoStart() {
        autoStartCooldownUntil = System.currentTimeMillis() + AUTO_START_COOLDOWN_MS;
        cancelAutoStartPrompt();
    }

    /**
     * Shows a non-modal popup so a background result (e.g. a previous meeting's
     * notes finishing while a new meeting runs) is always seen, without
     * blocking the live meeting.
     */
    private void showBackgroundNote(String message, boolean error) {
        JOptionPane pane = new JOptionPane(message,
                error ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        javax.swing.JDialog dialog = pane.createDialog(frame,
                error ? "ai-assist — notes not saved" : "ai-assist — notes saved");
        dialog.setModal(false);
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
    }

    /** Summarizes the meeting so far and shows it in the summary area. */
    private void applyMeetingSummary() {
        String sessionId = liveTranscription.status().sessionId();
        if (sessionId == null && renderedSessionId == null) {
            summaryArea.setText("No meeting to summarize yet — press Start.");
            return;
        }
        String id = sessionId != null ? sessionId : renderedSessionId;
        summaryArea.setText("Summarizing…");
        new Thread(() -> {
            try {
                // Same LLM-first path as the Editor and Compose tabs, so Apply
                // produces the identical detailed summary with action points
                // whichever tab it is pressed on.
                String transcriptText = sessions.get(id).transcript();
                int words = com.aiassist.draft.StyleRewriteService.wordCount(transcriptText);
                // Guard: a tiny model will happily invent a "summary" from one
                // or two stray words. Only summarize when there is real content.
                boolean tooLittle = words < com.aiassist.draft.StyleRewriteService.MIN_WORDS_TO_SUMMARIZE;
                String text = tooLittle
                        ? "Not enough has been captured to summarize yet — press Start, speak, then Apply."
                        : styleRewriteService.summarizeMeeting(transcriptText, null);
                String footer = tooLittle ? "" : "\n\n———\n[LLM: " + styleRewriteService.llmReport()
                        + " · transcript: " + words + " words]";
                SwingUtilities.invokeLater(() -> {
                    summaryArea.setText(text + footer);
                    summaryArea.setCaretPosition(0);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        summaryArea.setText("Could not summarize: " + e.getMessage()));
            }
        }, "meeting-summary").start();
    }

    /** The summary + key points + action items part of a draft (no full transcript). */
    private String summaryText(Draft draft) {
        StringBuilder sb = new StringBuilder(draft.summary() == null ? "" : draft.summary());
        for (Draft.Section section : draft.sections()) {
            if (!AttributedTranscript.HEADING.equals(section.heading())) {
                sb.append("\n\n").append(section.heading()).append("\n").append(section.body());
            }
        }
        return sb.toString().strip();
    }

    private void onClose() {
        LiveTranscriptionService.Status status = liveTranscription.status();
        boolean meetingActive = !meetingCompleted && status.sessionId() != null
                && (status.state() == LiveTranscriptionService.State.LISTENING
                    || status.state() == LiveTranscriptionService.State.PAUSED
                    || status.state() == LiveTranscriptionService.State.PREPARING);
        if (meetingActive && hasCapturedContent(status.sessionId())) {
            int choice = JOptionPane.showOptionDialog(frame,
                    "A meeting is still running. Save the notes before closing?",
                    "Close ai-assist", JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null,
                    new Object[]{"Yes — save and close", "Close without saving", "Cancel"},
                    "Yes — save and close");
            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
                return;
            }
            if (choice == 0) {
                try {
                    Draft draft = meetingEndService.endCurrentLiveMeeting(null);
                    if (draft.savedTo() != null) {
                        JOptionPane.showMessageDialog(frame, "Notes saved to:\n" + draft.savedTo());
                    }
                } catch (Exception e) {
                    // Don't silently discard the meeting: report and abort the close.
                    JOptionPane.showMessageDialog(frame,
                            "The notes were NOT saved:\n" + e.getMessage(),
                            "Could not save", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
        }
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
        try {
            liveTranscription.stopActivityMonitor();
        } catch (Exception ignored) {
            // shutting down anyway
        }
        monitorControl.shutdownNow();
        frame.dispose();
        System.exit(0);
    }

    private boolean hasCapturedContent(String sessionId) {
        try {
            return !sessions.get(sessionId).utterances().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
