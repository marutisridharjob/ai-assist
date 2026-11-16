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
public class MeetingController {

    private final AudioDeviceService audioDevices;
    private final MeetingCaptureService capture;

    public MeetingController(AudioDeviceService audioDevices, MeetingCaptureService capture) {
        this.audioDevices = audioDevices;
        this.capture = capture;
    }

    @GetMapping("/audio/devices")
    public List<AudioDeviceService.AudioDevice> listDevices() {
        return audioDevices.listCaptureDevices();
    }

    public record StartLiveRequest(List<@Size(max = 200) String> devices,
                                   @Size(max = 100) String sessionId) {
    }

    @PostMapping("/live/start")
    public MeetingCaptureService.Status start(@Valid @RequestBody(required = false) StartLiveRequest request) {
        List<String> devices = request == null ? null : request.devices();
        String sessionId = request == null ? null : request.sessionId();
        return capture.start(devices, sessionId);
    }

    @PostMapping("/live/pause")
    public MeetingCaptureService.Status pause() {
        return capture.pause();
    }

    @PostMapping("/live/resume")
    public MeetingCaptureService.Status resume() {
        return capture.resume();
    }

    @PostMapping("/live/stop")
    public MeetingCaptureService.Status stop() {
        return capture.stop();
    }

    @GetMapping("/live/status")
    public MeetingCaptureService.Status status() {
        return capture.status();
    }
}
