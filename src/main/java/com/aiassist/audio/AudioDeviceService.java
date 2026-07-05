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
 * Enumerates audio capture devices and opens capture lines. To transcribe a
 * Webex/Teams meeting playing on this computer, select the OS loopback
 * device (e.g. "Stereo Mix" on Windows, a PulseAudio "Monitor" source on
 * Linux, or a virtual device such as BlackHole/VB-Cable) — applications do
 * not expose their audio streams directly, but the loopback device carries
 * everything the machine is playing.
 */
@Service
public class AudioDeviceService {

    public record AudioDevice(String name, String description, boolean likelyLoopback) {
    }

    /** Names that suggest a device carries system output (meeting audio). */
    private static final List<String> LOOPBACK_HINTS = List.of(
            "stereo mix", "monitor", "loopback", "blackhole", "vb-audio", "cable output", "what u hear");

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
