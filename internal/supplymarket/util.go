package supplymarket

import (
	"crypto/rand"
	"encoding/hex"
	"math"
	"strconv"
	"strings"
)

// newID returns a 16-hex-char identifier (8 random bytes).
func newID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// round2 rounds to two decimal places.
func round2(v float64) float64 {
	return math.Round(v*100) / 100
}

// parseFloatSafe parses a float, tolerating empty/whitespace.
func parseFloatSafe(s string) (float64, error) {
	return strconv.ParseFloat(strings.TrimSpace(s), 64)
}

// trimFloat renders a float without trailing zeros.
func trimFloat(v float64) string {
	return strconv.FormatFloat(v, 'f', -1, 64)
}
