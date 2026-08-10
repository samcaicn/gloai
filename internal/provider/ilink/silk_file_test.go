package ilink

import (
	"bytes"
	"encoding/binary"
	"math"
	"os"
	"testing"

	"github.com/youthlin/silk"
)

// TestSilkRoundTripFiles generates actual files you can play to verify each step.
// Files are saved to /tmp/silk_test_*
// 1. /tmp/silk_test_1_original.pcm      — raw PCM (24kHz 16bit mono)
// 2. /tmp/silk_test_2_original.wav       — playable WAV from step 1
// 3. /tmp/silk_test_3_encoded.silk       — SILK encoded from step 1 (with STX)
// 4. /tmp/silk_test_4_decoded.pcm        — PCM decoded from step 3
// 5. /tmp/silk_test_5_decoded.wav        — playable WAV from step 4
// 6. /tmp/silk_test_6_reencoded.silk     — SILK re-encoded from step 5 (simulates sendVoice)
// 7. /tmp/silk_test_7_final_decoded.wav  — WAV decoded from step 6
//
// Play step 2 and 5 and 7 with any audio player to verify.
func TestSilkRoundTripFiles(t *testing.T) {
	const sampleRate = 24000
	const durationMs = 3000 // 3 seconds

	// Step 1: Generate a 440Hz sine wave PCM (audible tone)
	pcm := generateSineWave(440, sampleRate, durationMs)
	os.WriteFile("/tmp/silk_test_1_original.pcm", pcm, 0644)
	t.Logf("Step 1: PCM %d bytes (24kHz 16bit mono, 3s)", len(pcm))

	// Step 2: Wrap as WAV
	wav := buildWAV(pcm, sampleRate, 1, 16)
	os.WriteFile("/tmp/silk_test_2_original.wav", wav, 0644)
	t.Logf("Step 2: WAV %d bytes → /tmp/silk_test_2_original.wav (should play 440Hz tone)", len(wav))

	// Step 3: Encode PCM → SILK with STX (simulates what WeChat would have)
	silkData, err := silk.Encode(bytes.NewReader(pcm), silk.SampleRate(sampleRate), silk.Stx(true))
	if err != nil {
		t.Fatalf("Step 3: encode failed: %v", err)
	}
	os.WriteFile("/tmp/silk_test_3_encoded.silk", silkData, 0644)
	t.Logf("Step 3: SILK %d bytes, header: %02x %02x %02x", len(silkData), silkData[0], silkData[1], silkData[2])

	// Step 4: Decode SILK → PCM (simulates DownloadVoice)
	decoded, err := silk.Decode(bytes.NewReader(silkData), silk.WithSampleRate(sampleRate))
	if err != nil {
		t.Fatalf("Step 4: decode failed: %v", err)
	}
	os.WriteFile("/tmp/silk_test_4_decoded.pcm", decoded, 0644)
	t.Logf("Step 4: decoded PCM %d bytes", len(decoded))

	// Step 5: Wrap decoded PCM as WAV (this is what user downloads)
	decodedWav := buildWAV(decoded, sampleRate, 1, 16)
	os.WriteFile("/tmp/silk_test_5_decoded.wav", decodedWav, 0644)
	t.Logf("Step 5: decoded WAV %d bytes → /tmp/silk_test_5_decoded.wav (should play same tone)", len(decodedWav))

	// Step 6: Re-encode (simulates sendVoice: parse WAV → extract PCM → encode SILK)
	info, err := parseWAV(decodedWav)
	if err != nil {
		t.Fatalf("Step 6a: parseWAV failed: %v", err)
	}
	t.Logf("Step 6a: parsed WAV: rate=%d ch=%d bits=%d pcm=%d",
		info.SampleRate, info.Channels, info.BitsPerSample, len(info.PCMData))

	rePCM := info.PCMData
	if info.Channels == 2 && info.BitsPerSample == 16 {
		rePCM = stereoToMono(rePCM)
		t.Logf("Step 6b: stereo→mono: %d bytes", len(rePCM))
	}

	reSilk, err := silk.Encode(bytes.NewReader(rePCM), silk.SampleRate(info.SampleRate), silk.Stx(true))
	if err != nil {
		t.Fatalf("Step 6c: re-encode failed: %v", err)
	}
	os.WriteFile("/tmp/silk_test_6_reencoded.silk", reSilk, 0644)
	t.Logf("Step 6c: re-encoded SILK %d bytes, header: %02x %02x %02x", len(reSilk), reSilk[0], reSilk[1], reSilk[2])

	// Step 7: Decode re-encoded SILK to verify it's valid
	finalPCM, err := silk.Decode(bytes.NewReader(reSilk), silk.WithSampleRate(sampleRate))
	if err != nil {
		t.Fatalf("Step 7: decode re-encoded failed: %v", err)
	}
	finalWav := buildWAV(finalPCM, sampleRate, 1, 16)
	os.WriteFile("/tmp/silk_test_7_final_decoded.wav", finalWav, 0644)
	t.Logf("Step 7: final WAV %d bytes → /tmp/silk_test_7_final_decoded.wav (should play same tone)", len(finalWav))

	// Summary
	t.Log("")
	t.Log("=== Verify these files ===")
	t.Log("Play /tmp/silk_test_2_original.wav    — original tone")
	t.Log("Play /tmp/silk_test_5_decoded.wav     — after SILK encode→decode")
	t.Log("Play /tmp/silk_test_7_final_decoded.wav — after full roundtrip")
	t.Log("")
	t.Logf("SILK sizes: original=%d, re-encoded=%d", len(silkData), len(reSilk))
	t.Logf("Byte-equal: %v", bytes.Equal(silkData, reSilk))

	// Compare SILK headers
	t.Logf("Original SILK first 20 bytes:     %x", silkData[:min(20, len(silkData))])
	t.Logf("Re-encoded SILK first 20 bytes:   %x", reSilk[:min(20, len(reSilk))])
}

func generateSineWave(freqHz, sampleRate, durationMs int) []byte {
	numSamples := sampleRate * durationMs / 1000
	pcm := make([]byte, numSamples*2)
	for i := 0; i < numSamples; i++ {
		t := float64(i) / float64(sampleRate)
		val := int16(16000 * math.Sin(2*math.Pi*float64(freqHz)*t))
		binary.LittleEndian.PutUint16(pcm[i*2:], uint16(val))
	}
	return pcm
}
