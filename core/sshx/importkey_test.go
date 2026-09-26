// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package sshx_test

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"strings"
	"testing"

	"golang.org/x/crypto/ssh"

	"github.com/dennisklein/sshovel/core/errcode"
	"github.com/dennisklein/sshovel/core/internal/testutil"
	"github.com/dennisklein/sshovel/core/sshx"
)

func openSSH(t *testing.T, key any, passphrase string) []byte {
	t.Helper()
	var block *pem.Block
	var err error
	if passphrase == "" {
		block, err = ssh.MarshalPrivateKey(key, "me@laptop")
	} else {
		block, err = ssh.MarshalPrivateKeyWithPassphrase(key, "me@laptop", []byte(passphrase))
	}
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(block)
}

func zeroed(b []byte) bool {
	for _, c := range b {
		if c != 0 {
			return false
		}
	}
	return true
}

// An imported passphrase-protected key is stored without the passphrase and
// then authenticates as the original key (IMPLEMENTATION_PLAN M3).
func TestImportKeyEncryptedEd25519(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	sshPub, _ := ssh.NewPublicKey(pub)
	in := openSSH(t, priv, "correct horse")
	pass := []byte("correct horse")

	k, err := sshx.ImportKey(in, pass, "phone")
	if err != nil {
		t.Fatal(err)
	}
	if !zeroed(in) || !zeroed(pass) {
		t.Error("input key or passphrase not zeroed")
	}
	if k.Type != ssh.KeyAlgoED25519 || k.Fingerprint != ssh.FingerprintSHA256(sshPub) {
		t.Errorf("got %s %s", k.Type, k.Fingerprint)
	}
	want := "restrict,port-forwarding " + strings.TrimSpace(string(ssh.MarshalAuthorizedKey(sshPub))) + " phone"
	if k.AuthorizedLine != want {
		t.Errorf("line %q", k.AuthorizedLine)
	}
	// The normalized key needs no passphrase and authenticates.
	stored := append([]byte(nil), k.Key...)
	signer, err := sshx.ParseImportedKey(k.Key)
	if err != nil {
		t.Fatalf("stored key: %v", err)
	}
	if strings.Contains(string(stored), "me@laptop") {
		t.Error("stored key keeps the original comment")
	}
	srv := testutil.NewSSHServer(t, sshPub)
	c, err := sshx.Dial(t.Context(), opts(srv, signer, pinOf(srv.HostSigner.PublicKey())))
	if err != nil {
		t.Fatalf("dial with imported key: %v", err)
	}
	c.Close()
}

func TestImportKeyFormats(t *testing.T) {
	ec := testutil.NewECDSAKey(t)
	rsa2048, _ := rsa.GenerateKey(rand.Reader, 2048)
	rsa1024, _ := rsa.GenerateKey(rand.Reader, 1024)
	_, ed, _ := ed25519.GenerateKey(rand.Reader)
	pkcs8, _ := x509.MarshalPKCS8PrivateKey(ec)
	ecPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: pkcs8})
	rsaPEM := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(rsa2048)})

	cases := []struct {
		name       string
		key        []byte
		passphrase string
		code       errcode.Code // "" = success
		typ        string
	}{
		{"ecdsa openssh", openSSH(t, ec, ""), "", "", ssh.KeyAlgoECDSA256},
		{"ecdsa pkcs8 pem", ecPEM, "", "", ssh.KeyAlgoECDSA256},
		{"rsa 2048 pkcs1 pem", rsaPEM, "", "", ssh.KeyAlgoRSA},
		{"unencrypted key, passphrase given anyway", openSSH(t, ed, ""), "unused", "", ssh.KeyAlgoED25519},
		{"rsa 1024", openSSH(t, rsa1024, ""), "", errcode.KeyUnsupported, ""},
		{"encrypted, no passphrase", openSSH(t, ed, "secret"), "", errcode.KeyPassphrase, ""},
		{"encrypted, wrong passphrase", openSSH(t, ed, "secret"), "wrong", errcode.KeyPassphrase, ""},
		{"putty", []byte("PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: none\n"), "", errcode.KeyPutty, ""},
		{"public key", ssh.MarshalAuthorizedKey(mustPub(t, ed.Public())), "", errcode.KeyUnsupported, ""},
		{"garbage", []byte("hello"), "", errcode.KeyUnsupported, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			in := append([]byte(nil), tc.key...)
			k, err := sshx.ImportKey(in, []byte(tc.passphrase), "")
			if !zeroed(in) {
				t.Error("key not zeroed")
			}
			if tc.code != "" {
				if errcode.Of(err) != tc.code {
					t.Fatalf("err %v, want %s", err, tc.code)
				}
				if strings.Contains(err.Error(), "PRIVATE KEY") {
					t.Error("error mentions key material")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if k.Type != tc.typ {
				t.Errorf("type %s, want %s", k.Type, tc.typ)
			}
			if _, err := sshx.ParseImportedKey(k.Key); err != nil {
				t.Errorf("stored key doesn't parse: %v", err)
			}
		})
	}
}

func mustPub(t *testing.T, k any) ssh.PublicKey {
	p, err := ssh.NewPublicKey(k)
	if err != nil {
		t.Fatal(err)
	}
	return p
}
