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

    private final java.util.prefs.Preferences prefs =
            java.util.prefs.Preferences.userNodeForPackage(MeetingConsole.class);

    private JFrame frame;
    private JTextArea transcript;
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
    private JPanel meetingTopRow;
    private JPanel extractionPanel;
    private javax.swing.JProgressBar extractionBar;
    private JLabel extractionLabel;
    private boolean modelsAvailable = true;
    private javax.swing.JTabbedPane tabs;
    private JTextArea editorArea;
    private javax.swing.JTextField filePathField;
    private JLabel editorStatus;
    private JPanel editorPanel;
    private JPanel editorFileRow;
    private JPanel editorActionsRow;
    private JPanel editorSouthRow;
    private JTextArea composeResult;
    private JTextArea composeFeed;
    private JLabel composeStatus;
    private javax.swing.JTextField composeInstructions;
    private javax.swing.JTextField editorInstructions;
    private javax.swing.JCheckBox cbGrammar;
    private javax.swing.JCheckBox cbCompact;
    private javax.swing.JCheckBox cbDetailed;
    private javax.swing.JCheckBox cbProfessional;
    private javax.swing.JCheckBox cbBullets;
    private javax.swing.JCheckBox cbEditorSummary;
    private javax.swing.JCheckBox cbComposeSummary;
    private final java.util.List<javax.swing.JCheckBox> styleCheckboxes = new java.util.ArrayList<>();
    private final java.util.List<javax.swing.JCheckBox> editorStyleChecks = new java.util.ArrayList<>();
    private JPanel editorStyleRow;
    private JPanel editorInstrRow;
    private JPanel editorOptionStack;
    private final java.util.List<javax.swing.JCheckBox> themedChecks = new java.util.ArrayList<>();
    private final java.util.List<JLabel> themedLabels = new java.util.ArrayList<>();
    private JPanel composeStylesPanel;
    private JPanel editorOptionsRow;
    private javax.swing.JSplitPane composeSplit;
    private JPanel composePanel;
    private JPanel composeTopPanel;
    private JPanel composeBottomPanel;
    private JPanel composeSouthPanel;
    private JPanel composeControlsPanel;
    private JLabel meetingIndicator;
    private JPanel indicatorPanel;
    private JPanel southWrapPanel;
    // Help tab.
    private JPanel helpPanel;
    private final java.util.List<JPanel> helpPanels = new java.util.ArrayList<>();
    private JTextArea feedbackArea;
    private javax.swing.JComboBox<Integer> ratingCombo;
    private JButton feedbackSubmit;
    private JLabel feedbackStatus;
    private boolean blinkOn;
    private JPanel bottomPanel;
    private JPanel buttonsPanel;
    private JButton startButton;
    private JButton pauseButton;
    private JButton stopButton;
    private Timer refreshTimer;
    private int renderedUtterances;
    private String renderedSessionId;
    private boolean meetingCompleted;
    private int silentCycles;
    private int detectorCountdown;
    private String detectedMeetingApp;
    private boolean darkMode;
    private String lastStatusMessage = " ";
    private boolean lastStatusWasError;

    public MeetingConsole(LiveTranscriptionService liveTranscription,
                          MeetingEndService meetingEndService, SessionStore sessions,
                          com.aiassist.draft.TextRewriteService rewriteService,
                          com.aiassist.draft.StyleRewriteService styleRewriteService) {
        this.liveTranscription = liveTranscription;
        this.meetingEndService = meetingEndService;
        this.sessions = sessions;
        this.rewriteService = rewriteService;
        this.styleRewriteService = styleRewriteService;
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
            // Native look on each OS (Aqua on macOS, Windows LAF on Windows)
            // instead of Swing's gray cross-platform default.
            javax.swing.UIManager.setLookAndFeel(
                    javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.debug("System look and feel unavailable: {}", e.getMessage());
        }
        frame = new JFrame("ai-assist — meeting notes");
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

        transcript = new JTextArea();
        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
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
        summaryPane.add(new JLabel("  Summary (press Apply):"), BorderLayout.NORTH);
        summaryPane.add(new JScrollPane(summaryArea,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),
                BorderLayout.CENTER);
        meetingSplit = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, scroll, summaryPane);
        meetingSplit.setResizeWeight(0.7);
        meetingSummaryPane = summaryPane;

        // Editable meeting title — becomes the notes file name.
        titleField = new javax.swing.JTextField();
        titleField.setToolTipText("Meeting title — used for the notes file name");
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
        modelCombo.setToolTipText("<html>Speech model. Built-in: small English (fast, 40 MB).<br>"
                + "For better accuracy in noise, download from alphacephei.com/vosk/models and unzip into ./models:<br>"
                + "· vosk-model-en-us-0.22-lgraph (128 MB, compact + notably more accurate)<br>"
                + "· vosk-model-en-us-0.22 (1.8 GB, most accurate)</html>");
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
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.add(modelCombo);
        controls.add(darkModeToggle);
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
        // Live caption: in-progress words before the recognizer finalizes them.
        captionLabel = new JLabel(" ");
        captionLabel.setForeground(java.awt.Color.GRAY);
        captionLabel.setFont(captionLabel.getFont().deriveFont(Font.ITALIC));
        startButton = new IndicatorButton("Start");
        startButton.setToolTipText("Begin a new meeting");
        startButton.addActionListener(e -> startMeeting());
        pauseButton = new IndicatorButton("Pause");
        pauseButton.setToolTipText("Temporarily stop listening without ending the meeting");
        pauseButton.addActionListener(e -> togglePause());
        stopButton = new IndicatorButton("Stop");
        stopButton.setToolTipText("Meeting complete — draft the notes and save the file");
        stopButton.addActionListener(e -> stopMeeting());

        JButton clearButton = new JButton("Clear");
        clearButton.setToolTipText("Clear the transcript display (captured content is kept for the notes)");
        clearButton.addActionListener(e -> transcript.setText(""));

        JButton applyButton = new JButton("Apply");
        applyButton.setToolTipText("Summarize the meeting so far and show it below");
        applyButton.addActionListener(e -> applyMeetingSummary());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(clearButton);
        buttons.add(applyButton);
        buttons.add(startButton);
        buttons.add(pauseButton);
        buttons.add(stopButton);
        buttonsPanel = buttons;
        // Caption, then status/errors, each on their own full-width line ABOVE
        // the buttons, wrapping instead of crowding into the button row.
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(captionLabel, BorderLayout.NORTH);
        bottom.add(statusLabel, BorderLayout.CENTER);
        bottom.add(buttons, BorderLayout.SOUTH);
        bottom.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));
        bottomPanel = bottom;

        frame.setLayout(new BorderLayout());
        frame.add(topStackPanel, BorderLayout.NORTH);
        tabs = new javax.swing.JTabbedPane();
        tabs.addTab("Meeting", meetingSplit);
        tabs.addTab("Editor", buildEditorTab());
        tabs.addTab("Compose", buildComposeTab());
        tabs.addTab("Help", buildHelpTab());
        frame.add(tabs, BorderLayout.CENTER);

        // On the Editor/Compose tabs the meeting chrome (title row, status,
        // buttons) is hidden; a blinking indicator shows a live meeting.
        meetingIndicator = new JLabel(" ");
        meetingIndicator.setFont(meetingIndicator.getFont().deriveFont(Font.BOLD));
        indicatorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        indicatorPanel.add(meetingIndicator);
        indicatorPanel.setVisible(false);
        JPanel southWrap = new JPanel();
        southWrap.setLayout(new javax.swing.BoxLayout(southWrap, javax.swing.BoxLayout.Y_AXIS));
        southWrap.add(bottom);
        southWrap.add(indicatorPanel);
        southWrapPanel = southWrap;
        tabs.addChangeListener(e -> {
            boolean meetingTab = tabs.getSelectedIndex() == 0;
            meetingTopRow.setVisible(meetingTab);
            bottomPanel.setVisible(meetingTab);
            frame.revalidate();
            frame.repaint();
        });
        frame.add(southWrap, BorderLayout.SOUTH);
        frame.setSize(760, 540);
        frame.setMinimumSize(new java.awt.Dimension(520, 380));
        frame.setResizable(true);
        frame.setLocationByPlatform(true);

        darkModeToggle.setSelected(prefs.getBoolean("darkMode", false));
        applyTheme(darkModeToggle.isSelected());
        frame.setVisible(true);

        // 250 ms so the live caption line keeps up with Vosk's partial results;
        // a 1 s poll missed most partials (they reset the moment a phrase is final).
        refreshTimer = new Timer(250, e -> refresh());
        refreshTimer.start();
    }

    private static final java.time.format.DateTimeFormatter LINE_TIME =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault());

    /**
     * Editor tab: paste content (or load a file by path), then apply an
     * offline transformation — grammar tidy, compact, or detailed rewrite.
     * Save writes back to the given path, keeping a .bak of the original.
     */
    private JPanel buildEditorTab() {
        editorArea = new JTextArea();
        editorArea.setLineWrap(true);
        editorArea.setWrapStyleWord(true);
        editorArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        editorArea.setMargin(new java.awt.Insets(8, 8, 8, 8));

        filePathField = new javax.swing.JTextField();
        filePathField.setToolTipText("Optional: full path of a text file to load and save (e.g. C:\\notes.txt or /Users/me/notes.txt)");
        JButton loadButton = new JButton("Load");
        loadButton.addActionListener(e -> loadEditorFile());
        JPanel fileRow = new JPanel(new BorderLayout(6, 0));
        fileRow.add(themedLabel("File:"), BorderLayout.WEST);
        fileRow.add(filePathField, BorderLayout.CENTER);
        JPanel fileButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        fileButtons.add(loadButton);
        fileRow.add(fileButtons, BorderLayout.EAST);
        fileRow.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 4, 8));

        editorStatus = new JLabel(" ");
        cbGrammar = themedCheck("Fix grammar");
        cbCompact = themedCheck("Make compact");
        cbDetailed = themedCheck("Make detailed");
        cbProfessional = themedCheck("Professional");
        cbBullets = themedCheck("Bullet points");
        cbEditorSummary = themedCheck("Meeting summary");
        cbEditorSummary.setToolTipText("Turn the text into a detailed meeting summary with action points");
        cbGrammar.setSelected(true);
        editorInstructions = new javax.swing.JTextField(18);
        // Row 1: the editing options.
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (var cb : java.util.List.of(cbGrammar, cbCompact, cbDetailed, cbProfessional, cbBullets, cbEditorSummary)) {
            options.add(cb);
        }
        // Row 2: every communication style as a checkbox (combinable).
        JPanel styleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        styleRow.add(themedLabel("Styles:"));
        for (var style : com.aiassist.draft.StyleRewriteService.Style.values()) {
            var check = themedCheck(style.display());
            check.putClientProperty("style", style);
            editorStyleChecks.add(check);
            styleRow.add(check);
        }
        // Row 3: free-form instructions.
        JPanel instrRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        instrRow.add(themedLabel("Instructions:"));
        instrRow.add(editorInstructions);
        JPanel optionStack = new JPanel();
        optionStack.setLayout(new javax.swing.BoxLayout(optionStack, javax.swing.BoxLayout.Y_AXIS));
        optionStack.add(options);
        optionStack.add(styleRow);
        optionStack.add(instrRow);

        JButton applyButton = new JButton("Apply");
        applyButton.setToolTipText("Apply every checked option and style plus the instructions to the text");
        applyButton.addActionListener(e -> applyEditorOptions());
        JButton downloadButton = new JButton("Download");
        downloadButton.setToolTipText("Save the corrected content to your Desktop with the same file name and format");
        downloadButton.addActionListener(e -> downloadEditorFile());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(applyButton);
        actions.add(downloadButton);

        JPanel south = new JPanel(new BorderLayout());
        south.add(optionStack, BorderLayout.NORTH);
        south.add(editorStatus, BorderLayout.CENTER);
        south.add(actions, BorderLayout.EAST);
        south.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));
        editorStyleRow = styleRow;
        editorInstrRow = instrRow;
        editorOptionStack = optionStack;

        editorPanel = new JPanel(new BorderLayout());
        editorPanel.add(fileRow, BorderLayout.NORTH);
        // Document-like view: no soft wrapping, real horizontal + vertical scroll bars.
        editorArea.setLineWrap(false);
        editorPanel.add(new JScrollPane(editorArea,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED),
                BorderLayout.CENTER);
        editorPanel.add(south, BorderLayout.SOUTH);
        editorFileRow = fileRow;
        editorActionsRow = actions;
        editorSouthRow = south;
        editorOptionsRow = options;
        return editorPanel;
    }

    private void applyEditorOptions() {
        String text = editorArea.getText();
        if (text == null || text.isBlank()) {
            setEditorStatus("Load a file or paste some text first.", true);
            return;
        }
        boolean summary = cbEditorSummary.isSelected();
        var styles = editorStyleChecks.stream()
                .filter(javax.swing.AbstractButton::isSelected)
                .map(cb -> (com.aiassist.draft.StyleRewriteService.Style) cb.getClientProperty("style"))
                .toList();
        setEditorStatus(summary ? "Summarizing…" : "Applying…", false);
        new Thread(() -> {
            try {
                String result = summary
                        ? styleRewriteService.summarizeMeeting(text, editorInstructions.getText())
                        : styleRewriteService.applyEditor(text,
                                cbGrammar.isSelected(), cbCompact.isSelected(), cbDetailed.isSelected(),
                                cbProfessional.isSelected(), cbBullets.isSelected(), styles,
                                editorInstructions.getText());
                boolean instructionsIgnored = !styleRewriteService.llmAvailable()
                        && editorInstructions.getText() != null
                        && !editorInstructions.getText().isBlank();
                SwingUtilities.invokeLater(() -> {
                    editorArea.setText(result);
                    editorArea.setCaretPosition(0);
                    setEditorStatus(summary
                            ? (styleRewriteService.llmAvailable()
                                ? "Meeting summary with action points ready. Press Download to save it."
                                : "Summary with action points ready (install local Ollama for a richer LLM summary).")
                            : (instructionsIgnored
                                ? "Applied checked options (free-form instructions need the optional local Ollama)."
                                : "Applied. Press Download to save it to your Desktop."), false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        setEditorStatus("Could not apply: " + ex.getMessage(), true));
            }
        }, "editor-apply").start();
    }

    /** Writes the corrected content to the Desktop, same name and format. */
    private void downloadEditorFile() {
        String content = editorArea.getText();
        if (content == null || content.isBlank()) {
            setEditorStatus("Nothing to download yet.", true);
            return;
        }
        try {
            String sourcePath = filePathField.getText();
            String fileName = sourcePath == null || sourcePath.isBlank()
                    ? "edited.txt"
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
            setEditorStatus("Downloaded to " + target, false);
        } catch (Exception e) {
            setEditorStatus("Could not download: " + e.getMessage(), true);
        }
    }

    private javax.swing.JCheckBox themedCheck(String text) {
        var check = new javax.swing.JCheckBox(text);
        check.setOpaque(true);
        themedChecks.add(check);
        return check;
    }

    private JLabel themedLabel(String text) {
        var label = new JLabel(text);
        themedLabels.add(label);
        return label;
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

    private void loadEditorFile() {
        String path = chooseFile(false);
        if (path == null) {
            return;
        }
        filePathField.setText(path);
        try {
            editorArea.setText(java.nio.file.Files.readString(java.nio.file.Path.of(path)));
            editorArea.setCaretPosition(0);
            setEditorStatus("Loaded " + path, false);
        } catch (Exception e) {
            setEditorStatus("Could not load: " + e.getMessage(), true);
        }
    }

    private void setEditorStatus(String message, boolean error) {
        editorStatus.setText(message);
        editorStatus.setForeground(error
                ? (darkMode ? new java.awt.Color(0xFF6B6B) : new java.awt.Color(0xB00020))
                : (darkMode ? new java.awt.Color(0xC8C8C8) : java.awt.Color.DARK_GRAY));
    }

    /**
     * Compose tab: paste content into the bottom box, pick a communication
     * style, hit Draft — the styled, grammar-corrected result appears in the
     * top box. The divider between the boxes is draggable.
     */
    private JPanel buildComposeTab() {
        composeResult = multiLineArea();
        composeFeed = multiLineArea();

        // One checkbox per communication style; several can combine.
        JPanel styleChecks = new JPanel(new java.awt.GridLayout(0, 5, 4, 0));
        for (var style : com.aiassist.draft.StyleRewriteService.Style.values()) {
            var check = themedCheck(style.display());
            check.putClientProperty("style", style);
            styleCheckboxes.add(check);
            styleChecks.add(check);
        }
        cbComposeSummary = themedCheck("Meeting summary");
        cbComposeSummary.setToolTipText("Turn your content into a detailed meeting summary with action points");
        composeInstructions = new javax.swing.JTextField(18);
        JButton applyButton = new JButton("Apply");
        applyButton.setToolTipText("Apply every checked style and the instructions to your content");
        applyButton.addActionListener(e -> composeApply());
        composeStatus = new JLabel(" ");

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.add(cbComposeSummary);
        controls.add(themedLabel("Instructions:"));
        controls.add(composeInstructions);
        controls.add(applyButton);
        JPanel south = new JPanel(new BorderLayout());
        south.add(styleChecks, BorderLayout.NORTH);
        south.add(composeStatus, BorderLayout.CENTER);
        south.add(controls, BorderLayout.EAST);
        south.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));

        // Your content on TOP, the modified result below it.
        JPanel top = new JPanel(new BorderLayout());
        top.add(themedLabel("  Your content (type or paste here):"), BorderLayout.NORTH);
        top.add(new JScrollPane(composeFeed,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),
                BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(themedLabel("  Modified:"), BorderLayout.NORTH);
        bottom.add(new JScrollPane(composeResult,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),
                BorderLayout.CENTER);
        composeSplit = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, top, bottom);
        composeSplit.setResizeWeight(0.5);

        composePanel = new JPanel(new BorderLayout());
        composePanel.add(composeSplit, BorderLayout.CENTER);
        composePanel.add(south, BorderLayout.SOUTH);
        composeTopPanel = top;
        composeBottomPanel = bottom;
        composeSouthPanel = south;
        composeControlsPanel = controls;
        composeStylesPanel = styleChecks;
        return composePanel;
    }

    private javax.swing.JTextArea multiLineArea() {
        var area = new JTextArea();
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        area.setMargin(new java.awt.Insets(8, 8, 8, 8));
        return area;
    }

    private static final String FEEDBACK_TO = "marutisridhar.job@gmail.com";

    /**
     * Help tab: About, a Help link that opens the instructions window, and a
     * Feedback form that composes an email to the author.
     */
    private JPanel buildHelpTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 16, 14, 16));

        // Section 1 — About.
        panel.add(leftRow(sectionHeading("About")));
        panel.add(leftRow(themedLabel("Architecture & Design by Maruti, version 0.1")));
        panel.add(javax.swing.Box.createVerticalStrut(18));

        // Section 2 — Help (a link that opens the instructions window).
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

        // Section 3 — Feedback.
        panel.add(leftRow(sectionHeading("Feedback")));
        feedbackArea = multiLineArea();
        JScrollPane feedbackScroll = new JScrollPane(feedbackArea);
        feedbackScroll.setPreferredSize(new java.awt.Dimension(560, 150));
        feedbackScroll.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 170));
        feedbackScroll.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        panel.add(feedbackScroll);

        ratingCombo = new javax.swing.JComboBox<>(new Integer[] {0, 1, 2, 3, 4, 5});
        JPanel ratingRow = leftRow(themedLabel("Rating:"), ratingCombo);
        panel.add(ratingRow);

        feedbackSubmit = new JButton("Submit");
        feedbackSubmit.addActionListener(e -> submitFeedback());
        feedbackStatus = new JLabel(" ");
        themedLabels.add(feedbackStatus);
        panel.add(leftRow(feedbackSubmit, feedbackStatus));

        helpPanel = panel;
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(panel, BorderLayout.NORTH);
        helpPanels.add(wrap);
        return wrap;
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
        info.setCaretPosition(0);
        info.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) {
                openInBrowser(e.getURL().toString());
            }
        });
        JScrollPane infoScroll = new JScrollPane(info);

        JTextField searchField = new JTextField(26);
        JButton searchButton = new JButton("Search");
        Runnable search = () -> highlightMatches(info, searchField.getText());
        searchButton.addActionListener(e -> search.run());
        searchField.addActionListener(e -> search.run());
        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        searchRow.setBackground(panelBg);
        JLabel searchLabel = new JLabel("Search:");
        searchLabel.setForeground(dark ? new java.awt.Color(0xE6E6E6) : java.awt.Color.BLACK);
        searchRow.add(searchLabel);
        searchRow.add(searchField);
        searchRow.add(searchButton);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bottom.setBackground(panelBg);
        bottom.add(close);

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
            try {
                String subject = "Feedback on ai-assist with rating " + rate;
                openMailClient(FEEDBACK_TO, subject, feedbackBody(message, rate));
            } catch (Exception e) {
                log.warn("Could not open the mail client: {}", e.getMessage());
            }
            SwingUtilities.invokeLater(() -> feedbackStatus.setText("Submitted"));
            sleepQuietly(1000);
            SwingUtilities.invokeLater(() -> {
                feedbackArea.setText("");
                ratingCombo.setSelectedIndex(0);
                feedbackStatus.setText(" ");
                setFeedbackControlsEnabled(true);
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

    private void openMailClient(String to, String subject, String body) throws Exception {
        String uri = "mailto:" + to + "?subject=" + urlEncode(subject) + "&body=" + urlEncode(body);
        if (java.awt.Desktop.isDesktopSupported()
                && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MAIL)) {
            java.awt.Desktop.getDesktop().mail(new java.net.URI(uri));
        } else {
            openInBrowser(uri);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value.replace("\r", ""),
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

                + "<h3 style='color:" + heading + ";'>Meeting tab</h3>"
                + "<p>Press <b>Start</b> to begin capturing the meeting — your microphone "
                + "(<b>[you]</b>) and the computer's own audio (<b>[other]</b>, what the other "
                + "participants say) are transcribed live. <b>Pause</b> suspends listening without "
                + "ending the meeting; <b>Start</b> resumes it. The live caption line under the box "
                + "shows words as they are recognized; finished phrases drop into the transcript box. "
                + "<b>Apply</b> shows a detailed summary with action points so far, without ending the "
                + "meeting. <b>Stop</b> ends the meeting and saves the summary plus the full verbatim "
                + "transcript as a timestamped rich-text file on your Desktop. <b>Clear</b> empties the "
                + "box. Pick the speech model from the dropdown; the choice is remembered.</p>"

                + "<h3 style='color:" + heading + ";'>Editor tab</h3>"
                + "<p>Paste text or press <b>Load</b> to open a file. Tick any of <b>Fix grammar</b>, "
                + "<b>Make compact</b>, <b>Make detailed</b>, <b>Professional</b>, <b>Bullet points</b>, "
                + "the communication styles, or <b>Meeting summary</b>, optionally type free-form "
                + "<b>Instructions</b>, then press <b>Apply</b>. <b>Meeting summary</b> turns the text into "
                + "a detailed summary with action points. <b>Download</b> saves the result to your Desktop.</p>"

                + "<h3 style='color:" + heading + ";'>Compose tab</h3>"
                + "<p>Type or paste into the top box, tick communication styles (or <b>Meeting summary</b>), "
                + "add optional <b>Instructions</b>, and press <b>Apply</b>; the rewritten text appears in "
                + "the Modified box below. Free-form instructions and the richest summaries use the optional "
                + "local <a href='https://ollama.com'>Ollama</a> LLM when it is enabled; otherwise everything "
                + "runs on the built-in offline rules and drafter.</p>"

                + "<h3 style='color:" + heading + ";'>Vosk — live speech recognition</h3>"
                + "<p>Live captions are produced by <b>Vosk</b>, a lightweight offline speech-to-text engine. "
                + "Drop a Vosk model (a folder or its .zip) next to the app and it is loaded automatically. "
                + "Models and details: <a href='https://alphacephei.com/vosk/'>alphacephei.com/vosk</a> and "
                + "<a href='https://alphacephei.com/vosk/models'>alphacephei.com/vosk/models</a>.</p>"

                + "<h3 style='color:" + heading + ";'>ggml / whisper.cpp — accurate transcript</h3>"
                + "<p>On <b>Stop</b>, the recorded audio can be re-transcribed by <b>Whisper</b> "
                + "(whisper.cpp) for an accurate, complete-conversation transcript. It needs one "
                + "<code>ggml-*.bin</code> model in the app folder (see the <code>models/</code> folder's "
                + "download scripts). About the format and models: "
                + "<a href='https://github.com/ggerganov/whisper.cpp'>github.com/ggerganov/whisper.cpp</a>. "
                + "Everything runs 100% locally — no audio ever leaves the machine.</p>"

                + "<h3 style='color:" + heading + ";'>Open-source licenses</h3>"
                + "<ul>"
                + "<li>Vosk speech models &amp; API — Apache-2.0 "
                + "(<a href='https://github.com/alphacep/vosk-api'>vosk-api</a>)</li>"
                + "<li>Whisper / whisper.cpp — MIT "
                + "(<a href='https://github.com/ggerganov/whisper.cpp'>whisper.cpp</a>)</li>"
                + "<li>whisper-jni (Java binding) — MIT "
                + "(<a href='https://github.com/GiviMAD/whisper-jni'>whisper-jni</a>)</li>"
                + "<li>JNA — Apache-2.0 / LGPL-2.1</li>"
                + "<li>Spring Boot &amp; Jackson — Apache-2.0</li>"
                + "<li>Java runtime (OpenJDK) — GPLv2 with Classpath Exception</li>"
                + "<li>ai-assist application code — open source</li>"
                + "</ul>"

                + "<h3 style='color:" + heading + ";'>Technology stack</h3>"
                + "<p>Java 21 &amp; Swing (desktop UI, no browser), Spring Boot (app wiring and an optional "
                + "localhost REST API), Vosk (live captions), Whisper/whisper.cpp via whisper-jni (final "
                + "transcript), and an optional local Ollama LLM for richer drafting. All processing is "
                + "offline; the internet is used only to email this feedback.</p>"

                + "<p style='color:" + link + ";'>Tip: use the Search box above to jump to any word on this page.</p>"
                + "</body></html>";
    }

    private void composeApply() {
        String feed = composeFeed.getText();
        if (feed == null || feed.isBlank()) {
            composeStatus.setText("Type or paste content into the top box first.");
            return;
        }
        boolean summary = cbComposeSummary.isSelected();
        var styles = styleCheckboxes.stream()
                .filter(javax.swing.AbstractButton::isSelected)
                .map(cb -> (com.aiassist.draft.StyleRewriteService.Style) cb.getClientProperty("style"))
                .toList();
        composeStatus.setText(summary ? "Summarizing…" : "Applying…");
        new Thread(() -> {
            try {
                String result = summary
                        ? styleRewriteService.summarizeMeeting(feed, composeInstructions.getText())
                        : styleRewriteService.applyStyles(feed, styles, composeInstructions.getText());
                boolean instructionsIgnored = !styleRewriteService.llmAvailable()
                        && composeInstructions.getText() != null
                        && !composeInstructions.getText().isBlank();
                SwingUtilities.invokeLater(() -> {
                    composeResult.setText(result);
                    composeResult.setCaretPosition(0);
                    composeStatus.setText(summary
                            ? (styleRewriteService.llmAvailable()
                                ? "Meeting summary with action points ready."
                                : "Summary with action points ready (install local Ollama for a richer LLM summary).")
                            : (instructionsIgnored
                                ? "Applied checked styles (free-form instructions need the optional local Ollama)."
                                : "Applied."));
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
        if (!meetingCompleted) {
            setStatus(switch (status.state()) {
                case PREPARING -> status.detail() != null ? status.detail() : "Preparing speech model…";
                case LISTENING -> listeningMessage(status);
                case PAUSED -> "Paused — press Start to continue";
                case ERROR -> "Audio problem: " + status.detail();
                case IDLE -> modelsAvailable
                        ? "Idle — press Start to begin"
                        : "No speech model found — place a Vosk model zip or folder next to the jar"
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
            // New meeting: seed the title field, preferring the detected app.
            if (detectedMeetingApp != null && "Live meeting notes".equals(session.topic())) {
                session.rename(detectedMeetingApp + " meeting");
            }
            titleField.setText(session.topic());
        }
        List<Utterance> utterances = session.utterances();
        for (int i = renderedUtterances; i < utterances.size(); i++) {
            Utterance u = utterances.get(i);
            transcript.append("[" + LINE_TIME.format(u.capturedAt()) + "] "
                    + sourceName(u.speaker()) + ": " + u.text() + "\n");
        }
        if (utterances.size() > renderedUtterances) {
            renderedUtterances = utterances.size();
            transcript.setCaretPosition(transcript.getDocument().getLength());
        }
    }

    /** Friendly source name: "You" for your microphone, "Others" for the room. */
    private static String sourceName(String label) {
        return switch (label) {
            case "you" -> "You";
            case "other" -> "Others";
            default -> label;
        };
    }

    /** Blinking banner on the Editor/Compose tabs while a meeting is live. */
    private void updateMeetingIndicator(LiveTranscriptionService.Status status) {
        boolean onMeetingTab = tabs.getSelectedIndex() == 0;
        boolean active = !meetingCompleted
                && (status.state() == LiveTranscriptionService.State.LISTENING
                    || status.state() == LiveTranscriptionService.State.PREPARING);
        boolean paused = !meetingCompleted
                && status.state() == LiveTranscriptionService.State.PAUSED;
        boolean show = !onMeetingTab && (active || paused);
        if (indicatorPanel.isVisible() != show) {
            indicatorPanel.setVisible(show);
            frame.revalidate();
        }
        if (!show) {
            return;
        }
        if (active) {
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
     * Button whose label text is green when the action is available right
     * now and red when it is not — no extra decorations, readable on the
     * native look and feel of both macOS and Windows.
     */
    private static final class IndicatorButton extends JButton {

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

    /** Light/dark palette applied to every part of the window. */
    private void applyTheme(boolean dark) {
        darkMode = dark;
        java.awt.Color textBg = dark ? new java.awt.Color(0x1E1E1E) : java.awt.Color.WHITE;
        java.awt.Color textFg = dark ? new java.awt.Color(0xE6E6E6) : java.awt.Color.BLACK;
        java.awt.Color panelBg = dark ? new java.awt.Color(0x2B2B2B) : new java.awt.Color(0xF2F2F2);
        java.awt.Color muted = dark ? new java.awt.Color(0x9A9A9A) : java.awt.Color.GRAY;

        transcript.setBackground(textBg);
        transcript.setForeground(textFg);
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
                editorPanel, editorFileRow, editorActionsRow, editorSouthRow, editorOptionsRow,
                editorStyleRow, editorInstrRow, editorOptionStack,
                composePanel, composeTopPanel, composeBottomPanel, composeSouthPanel,
                composeControlsPanel, composeStylesPanel, indicatorPanel, southWrapPanel)) {
            // Aqua only honors panel backgrounds when the panel is opaque.
            panel.setOpaque(true);
            panel.setBackground(panelBg);
        }
        for (JTextArea area : java.util.List.of(composeResult, composeFeed, feedbackArea)) {
            area.setBackground(textBg);
            area.setForeground(textFg);
            area.setCaretColor(textFg);
        }
        ratingCombo.setBackground(textBg);
        ratingCombo.setForeground(textFg);
        if (helpPanel != null) {
            helpPanel.setOpaque(true);
            helpPanel.setBackground(panelBg);
        }
        for (JPanel panel : helpPanels) {
            panel.setOpaque(true);
            panel.setBackground(panelBg);
        }
        for (javax.swing.JTextField field : java.util.List.of(editorInstructions, composeInstructions)) {
            field.setBackground(textBg);
            field.setForeground(textFg);
            field.setCaretColor(textFg);
        }
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
        modelCombo.setBackground(textBg);
        modelCombo.setForeground(textFg);
        tabs.setBackground(panelBg);
        tabs.setForeground(textFg);
        editorArea.setBackground(textBg);
        editorArea.setForeground(textFg);
        editorArea.setCaretColor(textFg);
        filePathField.setBackground(textBg);
        filePathField.setForeground(textFg);
        filePathField.setCaretColor(textFg);
        setEditorStatus(editorStatus.getText(), false);
        frame.getContentPane().setBackground(panelBg);
        titleLabel.setForeground(textFg);
        captionLabel.setForeground(muted);
        darkModeToggle.setBackground(panelBg);
        darkModeToggle.setForeground(textFg);
        // Start/Pause/Stop keep their green/red action colors in both themes.
        setStatus(lastStatusMessage, lastStatusWasError);
        frame.repaint();
    }

    /** Begins a fresh meeting (a new session), e.g. after Stop or a startup error. */
    private void startMeeting() {
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
        // Explicit button labels: the plain confirm dialog rendered without
        // visible buttons for some users on Windows.
        Object[] choices = {"Yes — save notes", "Cancel"};
        int choice = JOptionPane.showOptionDialog(frame,
                "End the meeting and save the notes file to your Desktop?", "Meeting complete",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
        if (choice != 0) {
            return;
        }
        meetingCompleted = true;
        pauseButton.setEnabled(false);
        stopButton.setEnabled(false);
        setStatus("Drafting final notes…", false);
        new SwingWorker<Draft, Void>() {
            @Override
            protected Draft doInBackground() {
                return meetingEndService.endCurrentLiveMeeting(null);
            }

            @Override
            protected void done() {
                try {
                    Draft draft = get();
                    // Show the saved summary on the Meeting tab.
                    summaryArea.setText(summaryText(draft));
                    summaryArea.setCaretPosition(0);
                    transcript.append("\n" + "=".repeat(60) + "\nMEETING COMPLETE — SAVED\n"
                            + "=".repeat(60) + "\n");
                    setStatus(draft.savedTo() != null
                            ? "Notes saved to " + draft.savedTo()
                            : "Meeting ended (file saving is disabled in configuration)", false);
                    if (draft.savedTo() != null) {
                        JOptionPane.showMessageDialog(frame, "Notes saved to:\n" + draft.savedTo(),
                                "Meeting complete", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception e) {
                    meetingCompleted = false;
                    pauseButton.setEnabled(true);
                    stopButton.setEnabled(true);
                    String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    setStatus("Could not end the meeting: " + message, true);
                    // Loud on purpose: Windows users reported "nothing saved"
                    // with the reason hidden in the status line.
                    JOptionPane.showMessageDialog(frame, "The notes were NOT saved:\n" + message,
                            "Could not save", JOptionPane.ERROR_MESSAGE);
                }
                transcript.setCaretPosition(transcript.getDocument().getLength());
            }
        }.execute();
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
                String text = transcriptText == null || transcriptText.isBlank()
                        ? "Nothing has been captured yet."
                        : styleRewriteService.summarizeMeeting(transcriptText, null);
                SwingUtilities.invokeLater(() -> {
                    summaryArea.setText(text);
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
