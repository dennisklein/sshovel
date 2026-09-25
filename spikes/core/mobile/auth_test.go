// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package mobile

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

// jvmSigner drives testdata/JvmSigner.java over stdio.
type jvmSigner struct {
	mu    sync.Mutex
	in    io.Writer
	out   *bufio.Reader
	calls int
}

func (j *jvmSigner) SignDigest(_ string, digest []byte) ([]byte, error) {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.calls++
	if _, err := fmt.Fprintln(j.in, hex.EncodeToString(digest)); err != nil {
		return nil, err
	}
	line, err := j.out.ReadString('\n')
	if err != nil {
		return nil, err
	}
	return hex.DecodeString(strings.TrimSpace(line))
}

func startJvmSigner(t *testing.T) (*jvmSigner, []byte) {
	t.Helper()
	if _, err := exec.LookPath("java"); err != nil {
		t.Skip("java not installed")
	}
	cmd := exec.Command("java", "testdata/JvmSigner.java")
	stdin, _ := cmd.StdinPipe()
	stdout, _ := cmd.StdoutPipe()
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { stdin.Close(); cmd.Wait() })
	r := bufio.NewReader(stdout)
	line, err := r.ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	pkix, err := base64.StdEncoding.DecodeString(strings.TrimSpace(line))
	if err != nil {
		t.Fatal(err)
	}
	return &jvmSigner{in: stdin, out: r}, pkix
}

// startSshd runs the distro's stock OpenSSH sshd on a free loopback port with
// key-only auth, mirroring test-env/jumphost.
func startSshd(t *testing.T, authorizedKeys string) (port int, username string) {
	t.Helper()
	sshd := "/usr/sbin/sshd"
	if _, err := os.Stat(sshd); err != nil {
		t.Skip("sshd not installed")
	}
	u, err := user.Current()
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	hk := filepath.Join(dir, "hostkey")
	if out, err := exec.Command("ssh-keygen", "-q", "-t", "ecdsa", "-N", "", "-f", hk).CombinedOutput(); err != nil {
		t.Fatalf("ssh-keygen: %v\n%s", err, out)
	}
	ak := filepath.Join(dir, "authorized_keys")
	if err := os.WriteFile(ak, []byte(authorizedKeys+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port = l.Addr().(*net.TCPAddr).Port
	l.Close()
	cfg := fmt.Sprintf(`Port %d
ListenAddress 127.0.0.1
HostKey %s
AuthorizedKeysFile %s
PidFile %s
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
PermitRootLogin prohibit-password
AllowTcpForwarding yes
StrictModes no
UsePAM no
`, port, hk, ak, filepath.Join(dir, "pid"))
	cf := filepath.Join(dir, "sshd_config")
	if err := os.WriteFile(cf, []byte(cfg), 0o600); err != nil {
		t.Fatal(err)
	}
	os.MkdirAll("/run/sshd", 0o755)
	cmd := exec.Command(sshd, "-D", "-e", "-f", cf)
	var logs strings.Builder
	cmd.Stderr = &logs
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cmd.Process.Kill()
		cmd.Wait()
		if t.Failed() {
			t.Logf("sshd log:\n%s", logs.String())
		}
	})
	for i := 0; i < 50; i++ {
		if c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port)); err == nil {
			c.Close()
			return port, u.Username
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("sshd did not start:\n%s", logs.String())
	return 0, ""
}

func echoServer(t *testing.T) string {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	return l.Addr().String()
}

func authorizedLine(t *testing.T, opts string, pkix []byte) string {
	line, err := AuthorizedKeyLine(pkix, "sshovel@spike")
	if err != nil {
		t.Fatal(err)
	}
	return strings.Replace(line, "restrict,port-forwarding", opts, 1)
}

func TestSpike4_PlatformSignerAgainstOpenSSH(t *testing.T) {
	js, pkix := startJvmSigner(t)
	target := echoServer(t)

	t.Run("recommended line authenticates and forwards", func(t *testing.T) {
		port, u := startSshd(t, authorizedLine(t, "restrict,port-forwarding", pkix))
		msg, err := TestAuth("127.0.0.1", port, u, pkix, "alias", js, target)
		if err != nil {
			t.Fatal(err)
		}
		if js.calls == 0 {
			t.Fatal("platform signer was never called")
		}
		t.Log(msg)
	})

	t.Run("restrict alone denies forwarding as Prohibited", func(t *testing.T) {
		port, u := startSshd(t, authorizedLine(t, "restrict", pkix))
		_, err := TestAuth("127.0.0.1", port, u, pkix, "alias", js, target)
		var oce *ssh.OpenChannelError
		if err == nil || !strings.Contains(err.Error(), "reason=administratively prohibited") {
			t.Fatalf("want Prohibited, got %v (%v)", err, errors.As(err, &oce))
		}
		t.Log(err)
	})

	t.Run("key not in authorized_keys fails auth", func(t *testing.T) {
		port, u := startSshd(t, "")
		_, err := TestAuth("127.0.0.1", port, u, pkix, "alias", js, "")
		if err == nil || !strings.Contains(err.Error(), "unable to authenticate") {
			t.Fatalf("want auth failure, got %v", err)
		}
		t.Log(err)
	})

	t.Run("restrict,port-forwarding still allows exec for route discovery", func(t *testing.T) {
		port, u := startSshd(t, authorizedLine(t, "restrict,port-forwarding", pkix))
		signer, err := newPlatformSSHSigner(pkix, "alias", js)
		if err != nil {
			t.Fatal(err)
		}
		c, err := ssh.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port), &ssh.ClientConfig{
			User: u, Auth: []ssh.AuthMethod{ssh.PublicKeys(signer)},
			HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		})
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		s, err := c.NewSession()
		if err != nil {
			t.Fatal(err)
		}
		defer s.Close()
		out, err := s.CombinedOutput("ip -4 route show || netstat -rn")
		if err != nil {
			t.Fatalf("exec: %v\n%s", err, out)
		}
		t.Logf("route discovery output:\n%s", out)
	})
}
