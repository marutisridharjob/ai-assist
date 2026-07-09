package com.aiassist.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import com.aiassist.audio.LiveTranscriptionService;
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

    private final java.util.prefs.Preferences prefs =
            java.util.prefs.Preferences.userNodeForPackage(MeetingConsole.class);

    private JFrame frame;
    private JTextArea transcript;
    private JLabel statusLabel;
    private JLabel captionLabel;
    private JLabel titleLabel;
    private javax.swing.JTextField titleField;
    private javax.swing.JCheckBox darkModeToggle;
    private javax.swing.JComboBox<String> modelCombo;
    private boolean updatingModels;
    private JPanel controlsPanel;
    private JPanel topPanel;
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
                          MeetingEndService meetingEndService, SessionStore sessions) {
        this.liveTranscription = liveTranscription;
        this.meetingEndService = meetingEndService;
        this.sessions = sessions;
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
                new Thread(() -> liveTranscription.selectModel(selected), "model-switch").start();
            }
        });

        titleLabel = new JLabel("Title:");
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.add(modelCombo);
        controls.add(darkModeToggle);
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(titleLabel, BorderLayout.WEST);
        top.add(titleField, BorderLayout.CENTER);
        top.add(controls, BorderLayout.EAST);
        top.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 4, 8));
        topPanel = top;
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

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(clearButton);
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
        frame.add(top, BorderLayout.NORTH);
        frame.add(scroll, BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setSize(760, 540);
        frame.setMinimumSize(new java.awt.Dimension(520, 380));
        frame.setResizable(true);
        frame.setLocationByPlatform(true);

        darkModeToggle.setSelected(prefs.getBoolean("darkMode", false));
        applyTheme(darkModeToggle.isSelected());
        frame.setVisible(true);

        refreshTimer = new Timer(1000, e -> refresh());
        refreshTimer.start();
    }

    private static final java.time.format.DateTimeFormatter LINE_TIME =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault());

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
        if (detectorCountdown-- <= 0) {
            detectorCountdown = 5;
            detectedMeetingApp = MeetingAppDetector.detectRunningMeetingApp().orElse(null);
        }
        var partials = liveTranscription.partials();
        captionLabel.setText(partials.isEmpty() ? " "
                : partials.entrySet().stream()
                        .map(e -> e.getKey() + " ▸ " + e.getValue())
                        .reduce((a, b) -> a + "   " + b)
                        .orElse(" "));
        LiveTranscriptionService.Status status = liveTranscription.status();
        if (!meetingCompleted) {
            setStatus(switch (status.state()) {
                case PREPARING -> status.detail() != null ? status.detail() : "Preparing speech model…";
                case LISTENING -> listeningMessage(status);
                case PAUSED -> "Paused — press Resume to continue";
                case ERROR -> "Audio problem: " + status.detail();
                case IDLE -> "Idle — press Start to begin";
            }, status.state() == LiveTranscriptionService.State.ERROR);
            pauseButton.setText(status.state() == LiveTranscriptionService.State.PAUSED ? "Resume" : "Pause");
            pauseButton.setEnabled(status.state() == LiveTranscriptionService.State.LISTENING
                    || status.state() == LiveTranscriptionService.State.PAUSED);
        }
        startButton.setEnabled(meetingCompleted
                || status.state() == LiveTranscriptionService.State.IDLE
                || status.state() == LiveTranscriptionService.State.ERROR);
        String sessionId = status.sessionId();
        if (sessionId == null) {
            if (!meetingCompleted) {
                stopButton.setEnabled(false);
            }
            return;
        }
        ListeningSession session;
        try {
            session = sessions.get(sessionId);
        } catch (Exception e) {
            if (!meetingCompleted) {
                stopButton.setEnabled(false);
            }
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
        // Stop only makes sense once something has actually been recorded —
        // there is nothing to draft or save from an empty meeting.
        if (!meetingCompleted) {
            stopButton.setEnabled(!utterances.isEmpty());
        }
        for (int i = renderedUtterances; i < utterances.size(); i++) {
            Utterance u = utterances.get(i);
            transcript.append("[" + LINE_TIME.format(u.capturedAt()) + "] ["
                    + u.speaker() + "] " + u.text() + "\n");
        }
        if (utterances.size() > renderedUtterances) {
            renderedUtterances = utterances.size();
            transcript.setCaretPosition(transcript.getDocument().getLength());
        }
    }

    /**
     * Live status while listening: which sources are open and how loud each
     * one currently is, plus a warning when the app has heard only silence
     * for a while — the usual macOS causes being speaker volume and the
     * Control Center Mic Mode set to "Voice Isolation", which strips the
     * meeting audio out of the microphone signal.
     */
    private String listeningMessage(LiveTranscriptionService.Status status) {
        var levels = liveTranscription.levels();
        String levelText = levels.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue() + "%")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        int loudest = levels.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        silentCycles = loudest < 3 ? silentCycles + 1 : 0;
        String message = (detectedMeetingApp != null ? detectedMeetingApp + " detected · " : "")
                + "Listening (" + String.join(", ", status.devices()) + ")"
                + (levelText.isEmpty() ? "" : " — audio level: " + levelText);
        if (liveTranscription.modelNote() != null) {
            message += " — " + liveTranscription.modelNote();
        }
        boolean hasMeetingSource = status.devices().stream().anyMatch(d -> d.contains("[meeting]"));
        if (!hasMeetingSource) {
            message += " — NO meeting-audio device detected: only the microphone is being captured. "
                    + "Remote participants will only be heard if the meeting plays on speakers. For direct "
                    + "capture (works with headphones) install BlackHole and route the meeting through it "
                    + "(see README), then press Pause and Resume to rescan devices.";
        }
        if (silentCycles >= 8) {
            message += " — hearing only silence: if a meeting is playing, raise the speaker volume, "
                    + "and on macOS set Control Center > Mic Mode to \"Standard\" (Voice Isolation "
                    + "removes the meeting audio)";
        }
        return message;
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
     * Button with a state dot in its top-right corner: green when the action
     * is currently available, red when it is not — visible at a glance which
     * of Start/Pause/Stop applies right now.
     */
    private static final class IndicatorButton extends JButton {

        private static final java.awt.Color ACTIVE = new java.awt.Color(0x2ECC71);
        private static final java.awt.Color INACTIVE = new java.awt.Color(0xE74C3C);

        private IndicatorButton(String text) {
            super(text);
            setMargin(new java.awt.Insets(4, 14, 4, 18));
        }

        @Override
        public void setEnabled(boolean enabled) {
            boolean changed = enabled != isEnabled();
            super.setEnabled(enabled);
            if (changed) {
                repaint();
            }
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int diameter = 8;
            int x = getWidth() - diameter - 5;
            int y = 5;
            g2.setColor(isEnabled() ? ACTIVE : INACTIVE);
            g2.fillOval(x, y, diameter, diameter);
            g2.setColor(g2.getColor().darker());
            g2.drawOval(x, y, diameter, diameter);
            g2.dispose();
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
        titleField.setBackground(textBg);
        titleField.setForeground(textFg);
        titleField.setCaretColor(textFg);
        for (JPanel panel : java.util.List.of(topPanel, bottomPanel, buttonsPanel, controlsPanel)) {
            panel.setBackground(panelBg);
        }
        modelCombo.setBackground(textBg);
        modelCombo.setForeground(textFg);
        frame.getContentPane().setBackground(panelBg);
        titleLabel.setForeground(textFg);
        captionLabel.setForeground(muted);
        darkModeToggle.setBackground(panelBg);
        darkModeToggle.setForeground(textFg);
        for (JButton button : java.util.List.of(startButton, pauseButton, stopButton)) {
            button.setBackground(dark ? new java.awt.Color(0x3C3C3C) : null);
            button.setForeground(dark ? textFg : null);
        }
        setStatus(lastStatusMessage, lastStatusWasError);
        frame.repaint();
    }

    /** Begins a fresh meeting (a new session), e.g. after Stop or a startup error. */
    private void startMeeting() {
        try {
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
        if (liveTranscription.status().state() == LiveTranscriptionService.State.PAUSED) {
            liveTranscription.resume();
        } else {
            liveTranscription.pause();
        }
    }

    /** Stop = the meeting is complete: draft the full notes and save the file. */
    private void stopMeeting() {
        if (meetingCompleted) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(frame,
                "End the meeting and save the notes file?", "Meeting complete",
                JOptionPane.OK_CANCEL_OPTION);
        if (choice != JOptionPane.OK_OPTION) {
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
                    transcript.append("\n" + "=".repeat(60) + "\nMEETING COMPLETE — FINAL NOTES\n"
                            + "=".repeat(60) + "\n\n" + draft.fullText() + "\n");
                    setStatus(draft.savedTo() != null
                            ? "Notes saved to " + draft.savedTo()
                            : "Meeting ended (file saving is disabled in configuration)", false);
                } catch (Exception e) {
                    meetingCompleted = false;
                    pauseButton.setEnabled(true);
                    stopButton.setEnabled(true);
                    String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    setStatus("Could not end the meeting: " + message, true);
                }
                transcript.setCaretPosition(transcript.getDocument().getLength());
            }
        }.execute();
    }

    private void onClose() {
        LiveTranscriptionService.Status status = liveTranscription.status();
        boolean meetingActive = !meetingCompleted && status.sessionId() != null
                && (status.state() == LiveTranscriptionService.State.LISTENING
                    || status.state() == LiveTranscriptionService.State.PAUSED
                    || status.state() == LiveTranscriptionService.State.PREPARING);
        if (meetingActive && hasCapturedContent(status.sessionId())) {
            int choice = JOptionPane.showConfirmDialog(frame,
                    "A meeting is still running. Save the notes before closing?",
                    "Close ai-assist", JOptionPane.YES_NO_CANCEL_OPTION);
            if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
                return;
            }
            if (choice == JOptionPane.YES_OPTION) {
                try {
                    Draft draft = meetingEndService.endCurrentLiveMeeting(null);
                    if (draft.savedTo() != null) {
                        JOptionPane.showMessageDialog(frame, "Notes saved to\n" + draft.savedTo());
                    }
                } catch (Exception e) {
                    log.warn("Could not save notes while closing: {}", e.getMessage());
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
