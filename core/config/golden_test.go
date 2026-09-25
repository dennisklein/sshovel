// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package config

import (
	"bytes"
	"encoding/json"
	"os"
	"testing"
)

// TestKotlinGolden checks the profile JSON contract with the app: Kotlin's
// ProfileTest encodes exactly this file, and Go must accept it unchanged.
func TestKotlinGolden(t *testing.T) {
	raw, err := os.ReadFile("../../app/src/test/resources/profile_golden.json")
	if err != nil {
		t.Fatal(err)
	}
	raw = bytes.TrimSpace(raw)
	p, err := Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	if issues := p.Validate(); issues != nil {
		t.Fatalf("golden profile has issues: %+v", issues)
	}
	again, err := json.Marshal(p)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(again, raw) {
		t.Errorf("Go re-encodes the golden profile differently:\n got %s\nwant %s", again, raw)
	}
}
