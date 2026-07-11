package com.aiassist.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

import org.springframework.stereotype.Service;

/**
 * Enumerates audio capture devices and opens capture lines, using only what
 * the operating system provides — no third-party drivers or applications.
 * Devices differ in which formats they accept (macOS in particular often
 * refuses 16 kHz directly), so opening negotiates through a list of
 * candidate formats and the recognizer adapts to whatever was granted.
 */
@Service
public class AudioDeviceService {

    /**
     * Formats to try in order: the recognizer's preferred 16 kHz mono first,
     * then the rates sound hardware actually ships with. Stereo variants are
     * last — the capture worker downmixes them to mono.
     */
    static final List<AudioFormat> CANDIDATE_FORMATS = List.of(
            pcm(16000, 1), pcm(48000, 1), pcm(44100, 1), pcm(48000, 2), pcm(44100, 2));

    private static AudioFormat pcm(float sampleRate, int channels) {
        return new AudioFormat(sampleRate, 16, channels, true, false);
    }

    public record AudioDevice(String name, String description, boolean likelyLoopback) {
    }

    /**
     * A source chosen for capture: a Java Sound device (null deviceName =
     * the OS default microphone) or, when {@code systemTap} is set, the
     * native system-audio tap helper.
     */
    public record DeviceSelection(String deviceName, String label, boolean systemTap) {

        public DeviceSelection(String deviceName, String label) {
            this(deviceName, label, false);
        }

        public String displayName() {
            return deviceName == null ? "default microphone" : deviceName;
        }
    }

    /**
     * Names that suggest a device carries system output (meeting audio):
     * built-in options (Windows Stereo Mix, PulseAudio monitors) plus the
     * open-source virtual drivers users may install (BlackHole on macOS,
     * VB-Cable on Windows).
     */
    private static final List<String> LOOPBACK_HINTS = List.of(
            "stereo mix", "monitor", "loopback", "wave out", "what u hear",
            "blackhole", "vb-audio", "cable output");

    public List<AudioDevice> listCaptureDevices() {
        List<AudioDevice> devices = new ArrayList<>();
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            if (supportsAnyCandidate(AudioSystem.getMixer(mixerInfo))) {
                String lower = (mixerInfo.getName() + " " + mixerInfo.getDescription()).toLowerCase(Locale.ROOT);
                boolean loopback = LOOPBACK_HINTS.stream().anyMatch(lower::contains);
                devices.add(new AudioDevice(mixerInfo.getName(), mixerInfo.getDescription(), loopback));
            }
        }
        return devices;
    }

    /**
     * Picks every source worth listening to: the default microphone (always,
     * labelled "mic") plus each OS loopback-style device carrying what the
     * computer is playing (labelled "other"). A configured preferred device
     * is used as the meeting source instead of auto-detection.
     */
    public List<DeviceSelection> resolveAutoDevices(String preferredDevice) {
        List<DeviceSelection> selections = new ArrayList<>();
        if (preferredDevice != null && !preferredDevice.isBlank()) {
            selections.add(new DeviceSelection(preferredDevice.strip(), "other"));
        } else {
            for (AudioDevice device : listCaptureDevices()) {
                if (device.likelyLoopback()) {
                    selections.add(new DeviceSelection(device.name(), "other"));
                }
            }
        }
        selections.add(new DeviceSelection(null, "you"));
        return selections;
    }

    /**
     * Opens a capture line on the named device (or the OS default when
     * {@code deviceName} is blank), negotiating the first format the device
     * accepts. Callers must read the granted format from the returned line.
     */
    public TargetDataLine openBestCaptureLine(String deviceName) throws LineUnavailableException {
        Mixer mixer = null;
        if (deviceName != null && !deviceName.isBlank()) {
            Optional<Mixer.Info> match = findMixer(deviceName);
            if (match.isEmpty()) {
                throw new IllegalArgumentException("No capture device matching \"" + deviceName
                        + "\". Available: " + listCaptureDevices().stream().map(AudioDevice::name).toList());
            }
            mixer = AudioSystem.getMixer(match.get());
        }
        Exception last = null;
        for (AudioFormat format : CANDIDATE_FORMATS) {
            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                TargetDataLine line = mixer != null
                        ? (TargetDataLine) mixer.getLine(info)
                        : (TargetDataLine) AudioSystem.getLine(info);
                line.open(format);
                return line;
            } catch (Exception e) {
                last = e;
            }
        }
        LineUnavailableException failure = new LineUnavailableException(
                "Device \"" + (deviceName == null || deviceName.isBlank() ? "default microphone" : deviceName)
                + "\" accepted none of the candidate formats (16/48/44.1 kHz, mono/stereo, 16-bit)."
                + (last == null ? "" : " Last error: " + last.getMessage()));
        if (last != null) {
            failure.initCause(last);
        }
        throw failure;
    }

    private boolean supportsAnyCandidate(Mixer mixer) {
        for (AudioFormat format : CANDIDATE_FORMATS) {
            if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, format))) {
                return true;
            }
        }
        return false;
    }

    private Optional<Mixer.Info> findMixer(String deviceName) {
        String wanted = deviceName.toLowerCase(Locale.ROOT);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            if (mixerInfo.getName().toLowerCase(Locale.ROOT).contains(wanted)
                    && supportsAnyCandidate(AudioSystem.getMixer(mixerInfo))) {
                return Optional.of(mixerInfo);
            }
        }
        return Optional.empty();
    }
}
