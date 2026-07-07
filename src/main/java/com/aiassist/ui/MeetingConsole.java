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

    private void build() {
        frame = new JFrame("ai-assist — meeting notes");
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

        titleLabel = new JLabel("Title:");
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(titleLabel, BorderLayout.WEST);
        top.add(titleField, BorderLayout.CENTER);
        top.add(darkModeToggle, BorderLayout.EAST);
        top.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 4, 8));
        topPanel = top;

        statusLabel = new JLabel(" ");
        // Live caption: in-progress words before the recognizer finalizes them.
        captionLabel = new JLabel(" ");
        captionLabel.setForeground(java.awt.Color.GRAY);
        captionLabel.setFont(captionLabel.getFont().deriveFont(Font.ITALIC));
        startButton = new JButton("Start");
        startButton.setToolTipText("Begin a new meeting");
        startButton.addActionListener(e -> startMeeting());
        pauseButton = new JButton("Pause");
        pauseButton.setToolTipText("Temporarily stop listening without ending the meeting");
        pauseButton.addActionListener(e -> togglePause());
        stopButton = new JButton("Stop");
        stopButton.setToolTipText("Meeting complete — draft the notes and save the file");
        stopButton.addActionListener(e -> stopMeeting());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
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
        frame.setLocationByPlatform(true);

        darkModeToggle.setSelected(prefs.getBoolean("darkMode", false));
        applyTheme(darkModeToggle.isSelected());
        frame.setVisible(true);

        refreshTimer = new Timer(1000, e -> refresh());
        refreshTimer.start();
    }

    private static final java.time.format.DateTimeFormatter LINE_TIME =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(java.time.ZoneId.systemDefault());

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
        for (JPanel panel : java.util.List.of(topPanel, bottomPanel, buttonsPanel)) {
            panel.setBackground(panelBg);
        }
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
