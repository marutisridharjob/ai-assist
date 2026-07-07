// ai-assist native system-audio tap for Windows (Vista and later, incl. 11).
// Captures everything the PC is playing (the meeting audio) via WASAPI
// loopback on the default output device - no Stereo Mix, no virtual cable,
// works with any headphones - and streams it to stdout as 16-bit
// little-endian mono PCM, preceded by one header line:
// "AI_ASSIST_TAP <sampleRate>".
// Compiled on first use by the ai-assist app with the C# compiler that
// ships inside the .NET Framework on every Windows installation.

using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
class MMDeviceEnumeratorComObject
{
}

[ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator
{
    int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
}

[ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice
{
    int Activate(ref Guid iid, int clsCtx, IntPtr activationParams,
                 [MarshalAs(UnmanagedType.IUnknown)] out object activated);
}

[ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioClient
{
    int Initialize(int shareMode, int streamFlags, long bufferDuration,
                   long periodicity, IntPtr format, IntPtr audioSessionGuid);
    int GetBufferSize(out uint bufferFrameCount);
    int GetStreamLatency(out long latency);
    int GetCurrentPadding(out uint padding);
    int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closestMatch);
    int GetMixFormat(out IntPtr format);
    int GetDevicePeriod(out long defaultPeriod, out long minPeriod);
    int Start();
    int Stop();
    int Reset();
    int SetEventHandle(IntPtr handle);
    int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
}

[ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioCaptureClient
{
    int GetBuffer(out IntPtr data, out uint frames, out uint flags,
                  out long devicePosition, out long qpcPosition);
    int ReleaseBuffer(uint frames);
    int GetNextPacketSize(out uint frames);
}

[StructLayout(LayoutKind.Sequential, Pack = 1)]
struct WaveFormatEx
{
    public ushort wFormatTag;
    public ushort nChannels;
    public uint nSamplesPerSec;
    public uint nAvgBytesPerSec;
    public ushort nBlockAlign;
    public ushort wBitsPerSample;
    public ushort cbSize;
}

static class Program
{
    const int eRender = 0;
    const int eConsole = 0;
    const int CLSCTX_ALL = 23;
    const int AUDCLNT_SHAREMODE_SHARED = 0;
    const int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    const uint AUDCLNT_BUFFERFLAGS_SILENT = 2;

    static void Fail(int code, string message)
    {
        Console.Error.WriteLine(message);
        Environment.Exit(code);
    }

    static void Check(int hr, int code, string what)
    {
        if (hr != 0)
        {
            Fail(code, what + " failed (HRESULT 0x" + hr.ToString("X8") + ")");
        }
    }

    static void Main()
    {
        // Exit when the parent app closes our stdin.
        var watchdog = new Thread(delegate()
        {
            Console.OpenStandardInput().Read(new byte[1], 0, 1);
            Environment.Exit(0);
        });
        watchdog.IsBackground = true;
        watchdog.Start();

        var enumerator = (IMMDeviceEnumerator)(object)new MMDeviceEnumeratorComObject();
        IMMDevice device;
        Check(enumerator.GetDefaultAudioEndpoint(eRender, eConsole, out device), 3,
              "finding the default output device");

        Guid iidAudioClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
        object clientObject;
        Check(device.Activate(ref iidAudioClient, CLSCTX_ALL, IntPtr.Zero, out clientObject), 4,
              "activating the audio client");
        var client = (IAudioClient)clientObject;

        IntPtr mixFormatPtr;
        Check(client.GetMixFormat(out mixFormatPtr), 5, "reading the output mix format");
        var format = (WaveFormatEx)Marshal.PtrToStructure(mixFormatPtr, typeof(WaveFormatEx));

        Check(client.Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK,
                                10000000, 0, mixFormatPtr, IntPtr.Zero), 6,
              "initializing loopback capture");

        Guid iidCaptureClient = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
        object captureObject;
        Check(client.GetService(ref iidCaptureClient, out captureObject), 7,
              "obtaining the capture client");
        var capture = (IAudioCaptureClient)captureObject;

        Check(client.Start(), 8, "starting capture");

        int channels = format.nChannels;
        // Shared-mode mix format is 32-bit float on modern Windows; fall back
        // to 16-bit integer handling if that's what the driver reports.
        bool samplesAreFloat = format.wBitsPerSample == 32;

        Stream stdout = Console.OpenStandardOutput();
        byte[] header = System.Text.Encoding.ASCII.GetBytes(
            "AI_ASSIST_TAP " + format.nSamplesPerSec + "\n");
        stdout.Write(header, 0, header.Length);

        float[] floatSamples = new float[0];
        short[] shortSamples = new short[0];
        byte[] outputBytes = new byte[0];

        while (true)
        {
            uint packetFrames;
            Check(capture.GetNextPacketSize(out packetFrames), 9, "querying capture packets");
            if (packetFrames == 0)
            {
                Thread.Sleep(10);
                continue;
            }
            IntPtr data;
            uint frames, flags;
            long devicePosition, qpcPosition;
            Check(capture.GetBuffer(out data, out frames, out flags,
                                    out devicePosition, out qpcPosition), 10, "reading captured audio");

            int frameCount = (int)frames;
            int sampleCount = frameCount * channels;
            if (outputBytes.Length < frameCount * 2)
            {
                outputBytes = new byte[frameCount * 2];
            }

            if ((flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0)
            {
                Array.Clear(outputBytes, 0, frameCount * 2);
            }
            else if (samplesAreFloat)
            {
                if (floatSamples.Length < sampleCount)
                {
                    floatSamples = new float[sampleCount];
                }
                Marshal.Copy(data, floatSamples, 0, sampleCount);
                for (int frame = 0; frame < frameCount; frame++)
                {
                    float sum = 0;
                    for (int channel = 0; channel < channels; channel++)
                    {
                        sum += floatSamples[frame * channels + channel];
                    }
                    float value = sum / channels;
                    if (value > 1f) value = 1f;
                    if (value < -1f) value = -1f;
                    short pcm = (short)(value * 32767f);
                    outputBytes[frame * 2] = (byte)(pcm & 0xFF);
                    outputBytes[frame * 2 + 1] = (byte)((pcm >> 8) & 0xFF);
                }
            }
            else
            {
                if (shortSamples.Length < sampleCount)
                {
                    shortSamples = new short[sampleCount];
                }
                Marshal.Copy(data, shortSamples, 0, sampleCount);
                for (int frame = 0; frame < frameCount; frame++)
                {
                    int sum = 0;
                    for (int channel = 0; channel < channels; channel++)
                    {
                        sum += shortSamples[frame * channels + channel];
                    }
                    short pcm = (short)(sum / channels);
                    outputBytes[frame * 2] = (byte)(pcm & 0xFF);
                    outputBytes[frame * 2 + 1] = (byte)((pcm >> 8) & 0xFF);
                }
            }

            Check(capture.ReleaseBuffer(frames), 11, "releasing the capture buffer");
            stdout.Write(outputBytes, 0, frameCount * 2);
        }
    }
}
