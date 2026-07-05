package com.aiassist.audio;

import java.util.List;

import com.aiassist.draft.Draft;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controls live capture of meeting audio (Webex, MS Teams, …) playing on
 * this computer. Pick a loopback capture device from {@code /api/audio/devices},
 * start capture, and recognized speech accumulates in the listening session
 * ready to be drafted into notes.
 */
@RestController
@RequestMapping("/api")
public class LiveController {

    private final AudioDeviceService audioDevices;
    private final LiveTranscriptionService liveTranscription;
    private final AutoPilot autoPilot;

    public LiveController(AudioDeviceService audioDevices, LiveTranscriptionService liveTranscription,
                          AutoPilot autoPilot) {
        this.audioDevices = audioDevices;
        this.liveTranscription = liveTranscription;
        this.autoPilot = autoPilot;
    }

    @GetMapping("/audio/devices")
    public List<AudioDeviceService.AudioDevice> listDevices() {
        return audioDevices.listCaptureDevices(liveTranscription.captureFormat());
    }

    public record StartLiveRequest(@Size(max = 200) String device, @Size(max = 100) String sessionId) {
    }

    @PostMapping("/live/start")
    public LiveTranscriptionService.Status start(@Valid @RequestBody(required = false) StartLiveRequest request) {
        String device = request == null ? null : request.device();
        String sessionId = request == null ? null : request.sessionId();
        return liveTranscription.start(device, sessionId);
    }

    @PostMapping("/live/stop")
    public LiveTranscriptionService.Status stop() {
        return liveTranscription.stop();
    }

    @GetMapping("/live/status")
    public LiveTranscriptionService.Status status() {
        return liveTranscription.status();
    }

    /** Latest auto-generated draft of the live meeting notes, if any yet. */
    @GetMapping("/live/draft")
    public ResponseEntity<Draft> latestDraft() {
        Draft draft = autoPilot.latestDraft();
        return draft == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(draft);
    }
}
