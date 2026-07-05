package com.aiassist.draft;

import com.aiassist.listen.ListeningSession;
import com.aiassist.listen.SessionStore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The "draft" half of the assistant: turn a listening session's transcript
 * (or ad-hoc notes) into a detailed structured draft.
 */
@RestController
@RequestMapping("/api")
public class DraftController {

    private final SessionStore store;
    private final ContentDrafter drafter;
    private final MeetingEndService meetingEndService;

    public DraftController(SessionStore store, ContentDrafter drafter, MeetingEndService meetingEndService) {
        this.store = store;
        this.drafter = drafter;
        this.meetingEndService = meetingEndService;
    }

    public record SessionDraftRequest(DraftOptions.ContentType contentType, DraftOptions.Tone tone) {
    }

    public record AdHocDraftRequest(@Size(max = 200) String topic,
                                    @NotBlank @Size(max = 100_000) String notes,
                                    DraftOptions.ContentType contentType,
                                    DraftOptions.Tone tone) {
    }

    /**
     * Preview a draft from everything captured so far. Nothing is written to
     * disk — the notes file is only saved when the meeting is ended.
     */
    @PostMapping("/sessions/{id}/draft")
    public Draft draftFromSession(@PathVariable String id,
                                  @Valid @RequestBody(required = false) SessionDraftRequest request) {
        ListeningSession session = store.get(id);
        String transcript = session.transcript();
        if (transcript.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Session " + id + " has no captured utterances to draft from");
        }
        DraftOptions options = request == null
                ? DraftOptions.defaults()
                : new DraftOptions(request.contentType(), request.tone());
        return drafter.draft(session.topic(), transcript, options);
    }

    /**
     * Marks the meeting as ended: stops live capture for this session, locks
     * it against further input, drafts the complete end-to-end content, and
     * saves the timestamped notes file (the only moment a file is written).
     */
    @PostMapping("/sessions/{id}/end")
    public Draft endMeeting(@PathVariable String id,
                            @Valid @RequestBody(required = false) SessionDraftRequest request) {
        return meetingEndService.endMeeting(id, toOptions(request));
    }

    /** Ends whichever meeting the live capture is currently feeding. */
    @PostMapping("/live/end")
    public Draft endLiveMeeting(@Valid @RequestBody(required = false) SessionDraftRequest request) {
        return meetingEndService.endCurrentLiveMeeting(toOptions(request));
    }

    private DraftOptions toOptions(SessionDraftRequest request) {
        return request == null ? null : new DraftOptions(request.contentType(), request.tone());
    }

    /** Draft directly from supplied notes, without a listening session. */
    @PostMapping("/draft")
    public Draft draftAdHoc(@Valid @RequestBody AdHocDraftRequest request) {
        String topic = request.topic() == null || request.topic().isBlank() ? "Untitled" : request.topic().strip();
        return drafter.draft(topic, request.notes(), new DraftOptions(request.contentType(), request.tone()));
    }
}
