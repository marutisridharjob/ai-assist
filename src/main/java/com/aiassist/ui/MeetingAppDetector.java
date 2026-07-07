package com.aiassist.ui;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Best-effort detection of a running meeting application by scanning the
 * process list (plain JDK {@link ProcessHandle}, no extra permissions).
 * Used only for friendlier status text and a sensible default meeting title.
 */
final class MeetingAppDetector {

    private static final Map<String, String> KNOWN_APPS = Map.of(
            "teams", "Microsoft Teams",
            "webex", "Webex",
            "zoom", "Zoom",
            "slack", "Slack");

    private MeetingAppDetector() {
    }

    static Optional<String> detectRunningMeetingApp() {
        try {
            return ProcessHandle.allProcesses()
                    .map(p -> p.info().command().orElse(""))
                    .map(command -> command.toLowerCase(Locale.ROOT))
                    .map(MeetingAppDetector::match)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<String> match(String command) {
        for (Map.Entry<String, String> app : KNOWN_APPS.entrySet()) {
            if (command.contains(app.getKey())) {
                return Optional.of(app.getValue());
            }
        }
        return Optional.empty();
    }
}
