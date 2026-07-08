// ai-assist native system-audio tap for Windows (Vista and later).
//
// Captures everything the PC is playing - the meeting audio - via WASAPI
// loopback on the default output device, using the proven open-source
// `wasapi` crate (MIT). Works with any headphones; no Stereo Mix, no
// virtual cable. Windows converts to the requested 16 kHz mono 16-bit
// format (AUTOCONVERTPCM), which is exactly what the recognizer wants.
//
// Protocol on stdout: one ASCII header line "AI_ASSIST_TAP 16000" followed
// by raw 16-bit little-endian mono PCM. Exits when stdin closes.

use std::collections::VecDeque;
use std::error::Error;
use std::io::{Read, Write};
use std::process::exit;

use wasapi::*;

const SAMPLE_RATE: usize = 16000;
const CHUNK_BYTES: usize = 4096;

fn run() -> Result<(), Box<dyn Error>> {
    initialize_mta().ok()?;

    // Exit when the parent app closes our stdin (or kills us).
    std::thread::spawn(|| {
        let mut byte = [0u8; 1];
        let _ = std::io::stdin().read(&mut byte);
        exit(0);
    });

    // Loopback: open the default RENDER device in capture direction; the
    // wasapi crate sets AUDCLNT_STREAMFLAGS_LOOPBACK for that combination.
    let device = get_default_device(&Direction::Render)?;
    let mut audio_client = device.get_iaudioclient()?;
    let format = WaveFormat::new(16, 16, &SampleType::Int, SAMPLE_RATE, 1, None);
    let (_default_period, min_period) = audio_client.get_periods()?;
    audio_client.initialize_client(
        &format,
        min_period,
        &Direction::Capture,
        &ShareMode::Shared,
        true, // auto-convert from the device mix format to 16 kHz mono s16
    )?;
    let event = audio_client.set_get_eventhandle()?;
    let capture = audio_client.get_audiocaptureclient()?;

    let stdout = std::io::stdout();
    let mut out = stdout.lock();
    out.write_all(format!("AI_ASSIST_TAP {}\n", SAMPLE_RATE).as_bytes())?;
    out.flush()?;

    audio_client.start_stream()?;
    let mut queue: VecDeque<u8> = VecDeque::with_capacity(CHUNK_BYTES * 8);
    let mut chunk = vec![0u8; CHUNK_BYTES];
    loop {
        capture.read_from_device_to_deque(&mut queue)?;
        while queue.len() >= chunk.len() {
            for byte in chunk.iter_mut() {
                *byte = queue.pop_front().unwrap();
            }
            out.write_all(&chunk)?;
        }
        // Loopback delivers no events while the PC plays silence; a timeout
        // is normal there, so just keep waiting.
        let _ = event.wait_for_event(1000);
    }
}

fn main() {
    if let Err(error) = run() {
        eprintln!(
            "system-audio-tap failed: {} (is an output device present and working?)",
            error
        );
        exit(3);
    }
}
