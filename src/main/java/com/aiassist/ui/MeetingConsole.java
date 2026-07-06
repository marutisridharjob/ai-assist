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

    private JFrame frame;
    private JTextArea transcript;
    private JLabel statusLabel;
    private JButton startButton;
    private JButton pauseButton;
    private JButton stopButton;
    private Timer refreshTimer;
    private int renderedUtterances;
    private String renderedSessionId;
    private boolean meetingCompleted;
    private int silentCycles;

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

        statusLabel = new JLabel(" ");
        startButton = new JButton("Start meeting");
        startButton.addActionListener(e -> startMeeting());
        pauseButton = new JButton("Pause");
        pauseButton.addActionListener(e -> togglePause());
        stopButton = new JButton("Stop — meeting complete");
        stopButton.addActionListener(e -> stopMeeting());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(startButton);
        buttons.add(pauseButton);
        buttons.add(stopButton);
        // Status and errors get their own full-width line ABOVE the buttons,
        // wrapping long messages instead of crowding into the button row.
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(statusLabel, BorderLayout.NORTH);
        bottom.add(buttons, BorderLayout.SOUTH);
        bottom.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));

        frame.setLayout(new BorderLayout());
        frame.add(scroll, BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setSize(760, 540);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        refreshTimer = new Timer(1000, e -> refresh());
        refreshTimer.start();
    }

    /** Pulls new utterances and capture state into the window once a second. */
    private void refresh() {
        LiveTranscriptionService.Status status = liveTranscription.status();
        if (!meetingCompleted) {
            setStatus(switch (status.state()) {
                case PREPARING -> status.detail() != null ? status.detail() : "Preparing speech model…";
                case LISTENING -> listeningMessage(status);
                case PAUSED -> "Paused — press Resume to continue";
                case ERROR -> "Audio problem: " + status.detail();
                case IDLE -> "Idle — press Start meeting to begin";
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
        if (!sessionId.equals(renderedSessionId)) {
            renderedSessionId = sessionId;
            renderedUtterances = 0;
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
        List<Utterance> utterances = session.utterances();
        // Stop only makes sense once something has actually been recorded —
        // there is nothing to draft or save from an empty meeting.
        if (!meetingCompleted) {
            stopButton.setEnabled(!utterances.isEmpty());
        }
        for (int i = renderedUtterances; i < utterances.size(); i++) {
            Utterance u = utterances.get(i);
            transcript.append("[" + u.speaker() + "] " + u.text() + "\n");
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
        String message = "Listening (" + String.join(", ", status.devices()) + ")"
                + (levelText.isEmpty() ? "" : " — audio level: " + levelText);
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
        String escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        statusLabel.setText("<html><body style='width: 640px'>" + escaped + "</body></html>");
        statusLabel.setForeground(error ? new java.awt.Color(0xB00020) : java.awt.Color.DARK_GRAY);
        // The bottom panel re-lays out on setText, taking the height the
        // wrapped message needs — the buttons keep their own row below.
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
