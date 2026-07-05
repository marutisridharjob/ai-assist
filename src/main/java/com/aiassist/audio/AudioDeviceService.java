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
 * The meeting side of a Webex/Teams call is heard either through an OS
 * loopback-style capture device when the sound driver provides one (e.g.
 * "Stereo Mix" on many Windows machines — enabled in Sound settings, not
 * installed), or simply through the microphone when the meeting plays over
 * the speakers.
 */
@Service
public class AudioDeviceService {

    public record AudioDevice(String name, String description, boolean likelyLoopback) {
    }

    /** A device chosen for capture; a null deviceName means the OS default microphone. */
    public record DeviceSelection(String deviceName, String label) {

        public String displayName() {
            return deviceName == null ? "default microphone" : deviceName;
        }
    }

    /** Names that suggest a device carries system output (meeting audio). */
    private static final List<String> LOOPBACK_HINTS = List.of(
            "stereo mix", "monitor", "loopback", "wave out", "what u hear");

    public List<AudioDevice> listCaptureDevices(AudioFormat format) {
        List<AudioDevice> devices = new ArrayList<>();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            if (mixer.isLineSupported(info)) {
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
     * computer is playing (labelled "meeting"). A configured preferred device
     * is used as the meeting source instead of auto-detection.
     */
    public List<DeviceSelection> resolveAutoDevices(AudioFormat format, String preferredDevice) {
        List<DeviceSelection> selections = new ArrayList<>();
        if (preferredDevice != null && !preferredDevice.isBlank()) {
            selections.add(new DeviceSelection(preferredDevice.strip(), "meeting"));
        } else {
            for (AudioDevice device : listCaptureDevices(format)) {
                if (device.likelyLoopback()) {
                    selections.add(new DeviceSelection(device.name(), "meeting"));
                }
            }
        }
        selections.add(new DeviceSelection(null, "mic"));
        return selections;
    }

    /**
     * Opens a capture line on the named device, or on the default device when
     * {@code deviceName} is blank. Matching is case-insensitive on substring
     * so UI values and config values are forgiving.
     */
    public TargetDataLine openCaptureLine(String deviceName, AudioFormat format) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (deviceName != null && !deviceName.isBlank()) {
            Optional<Mixer.Info> match = findMixer(deviceName, info);
            if (match.isEmpty()) {
                throw new IllegalArgumentException("No capture device matching \"" + deviceName
                        + "\". Available: " + listCaptureDevices(format).stream().map(AudioDevice::name).toList());
            }
            TargetDataLine line = (TargetDataLine) AudioSystem.getMixer(match.get()).getLine(info);
            line.open(format);
            return line;
        }
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        return line;
    }

    private Optional<Mixer.Info> findMixer(String deviceName, DataLine.Info info) {
        String wanted = deviceName.toLowerCase(Locale.ROOT);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            if (mixerInfo.getName().toLowerCase(Locale.ROOT).contains(wanted)
                    && AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
                return Optional.of(mixerInfo);
            }
        }
        return Optional.empty();
    }
}
