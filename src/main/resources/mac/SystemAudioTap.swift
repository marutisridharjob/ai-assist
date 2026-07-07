// ai-assist native system-audio tap for macOS 14.2+.
// Captures everything the Mac is playing (the meeting audio) via Apple's
// Core Audio process-tap API — the same mechanism modern commercial
// meeting-notes apps use — and streams it to stdout as 16-bit little-endian
// mono PCM, preceded by one header line: "AI_ASSIST_TAP <sampleRate>".
// Compiled on first use by the ai-assist app with the Xcode Command Line
// Tools' swiftc; no third-party code involved.

import Foundation
import CoreAudio
import AudioToolbox

func fail(_ code: Int32, _ message: String) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(code)
}

guard #available(macOS 14.2, *) else {
    fail(2, "system audio tap requires macOS 14.2 or newer")
}

// Tap the mixdown of every process except none (i.e. the whole system output).
let tapDescription = CATapDescription(stereoGlobalTapButExcludeProcesses: [])
tapDescription.name = "ai-assist system audio tap"
tapDescription.isPrivate = true

var tapID = AudioObjectID(kAudioObjectUnknown)
var status = AudioHardwareCreateProcessTap(tapDescription, &tapID)
if status != noErr {
    fail(3, "could not create the system audio tap (error \(status)). macOS asks for permission "
        + "the first time: approve 'System Audio Recording' in the prompt, or enable it under "
        + "System Settings > Privacy & Security > Screen & System Audio Recording, then retry.")
}

let aggregateDescription: [String: Any] = [
    kAudioAggregateDeviceNameKey as String: "ai-assist tap device",
    kAudioAggregateDeviceUIDKey as String: UUID().uuidString,
    kAudioAggregateDeviceIsPrivateKey as String: true,
    kAudioAggregateDeviceTapAutoStartKey as String: true,
    kAudioAggregateDeviceTapListKey as String: [
        [
            kAudioSubTapUIDKey as String: tapDescription.uuid.uuidString,
            kAudioSubTapDriftCompensationKey as String: true,
        ]
    ],
]

var aggregateID = AudioObjectID(kAudioObjectUnknown)
status = AudioHardwareCreateAggregateDevice(aggregateDescription as CFDictionary, &aggregateID)
if status != noErr {
    AudioHardwareDestroyProcessTap(tapID)
    fail(4, "could not create the aggregate capture device (error \(status))")
}

var formatAddress = AudioObjectPropertyAddress(
    mSelector: kAudioTapPropertyFormat,
    mScope: kAudioObjectPropertyScopeGlobal,
    mElement: kAudioObjectPropertyElementMain)
var format = AudioStreamBasicDescription()
var formatSize = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
status = AudioObjectGetPropertyData(tapID, &formatAddress, 0, nil, &formatSize, &format)
if status != noErr {
    fail(5, "could not read the tap's audio format (error \(status))")
}

let stdoutHandle = FileHandle.standardOutput
stdoutHandle.write("AI_ASSIST_TAP \(Int(format.mSampleRate))\n".data(using: .utf8)!)

// Convert whatever the tap delivers (Float32, N channels) to 16-bit mono PCM.
let ioBlock: AudioDeviceIOBlock = { _, inInputData, _, _, _ in
    let bufferList = UnsafeMutableAudioBufferListPointer(
        UnsafeMutablePointer(mutating: inInputData))
    guard let buffer = bufferList.first, let rawData = buffer.mData else { return }
    let channelCount = max(Int(buffer.mNumberChannels), 1)
    let sampleCount = Int(buffer.mDataByteSize) / MemoryLayout<Float32>.size
    let frames = sampleCount / channelCount
    if frames == 0 { return }
    let samples = rawData.bindMemory(to: Float32.self, capacity: sampleCount)
    var pcm = Data(count: frames * 2)
    pcm.withUnsafeMutableBytes { (raw: UnsafeMutableRawBufferPointer) in
        let out = raw.bindMemory(to: Int16.self)
        for frame in 0..<frames {
            var sum: Float32 = 0
            for channel in 0..<channelCount {
                sum += samples[frame * channelCount + channel]
            }
            var value = sum / Float32(channelCount)
            if value > 1.0 { value = 1.0 }
            if value < -1.0 { value = -1.0 }
            out[frame] = Int16(value * 32767.0)
        }
    }
    stdoutHandle.write(pcm)
}

var ioProcID: AudioDeviceIOProcID?
status = AudioDeviceCreateIOProcIDWithBlock(&ioProcID, aggregateID, nil, ioBlock)
if status != noErr {
    fail(6, "could not install the audio callback (error \(status))")
}
status = AudioDeviceStart(aggregateID, ioProcID)
if status != noErr {
    fail(7, "could not start the capture device (error \(status))")
}

signal(SIGTERM) { _ in exit(0) }
signal(SIGINT) { _ in exit(0) }

// Run until the parent app closes our stdin (or kills us).
_ = FileHandle.standardInput.readDataToEndOfFile()
exit(0)
