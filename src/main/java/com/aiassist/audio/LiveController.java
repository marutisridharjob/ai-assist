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
 * Optional local REST control of live capture — everything here is also driven by
 * the desktop window. Capture defaults to the microphone plus any OS
 * loopback device so an active Webex/Teams/any-platform meeting is heard.
 */
@RestController
@RequestMapping("/api")
public class LiveController {

    private final AudioDeviceService audioDevices;
    private final LiveTranscriptionService liveTranscription;

    public LiveController(AudioDeviceService audioDevices, LiveTranscriptionService liveTranscription) {
        this.audioDevices = audioDevices;
        this.liveTranscription = liveTranscription;
    }

    @GetMapping("/audio/devices")
    public List<AudioDeviceService.AudioDevice> listDevices() {
        return audioDevices.listCaptureDevices();
    }

    public record StartLiveRequest(List<@Size(max = 200) String> devices,
                                   @Size(max = 100) String sessionId) {
    }

    @PostMapping("/live/start")
    public LiveTranscriptionService.Status start(@Valid @RequestBody(required = false) StartLiveRequest request) {
        List<String> devices = request == null ? null : request.devices();
        String sessionId = request == null ? null : request.sessionId();
        return liveTranscription.start(devices, sessionId);
    }

    @PostMapping("/live/pause")
    public LiveTranscriptionService.Status pause() {
        return liveTranscription.pause();
    }

    @PostMapping("/live/resume")
    public LiveTranscriptionService.Status resume() {
        return liveTranscription.resume();
    }

    @PostMapping("/live/stop")
    public LiveTranscriptionService.Status stop() {
        return liveTranscription.stop();
    }

    @GetMapping("/live/status")
    public LiveTranscriptionService.Status status() {
        return liveTranscription.status();
    }
}
