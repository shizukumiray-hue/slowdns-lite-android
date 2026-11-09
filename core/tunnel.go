package core

import (
	"context"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/daisy/mashiro/core/dnstt"
	"github.com/daisy/mashiro/core/dnstt/dns"
	"github.com/daisy/mashiro/core/dnstt/noise"
	"github.com/daisy/mashiro/core/dnstt/turbotunnel"
	"github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"
	"golang.org/x/crypto/ssh"
)

type SocketProtector interface {
	Protect(fd int) bool
}

type LogListener interface {
	OnLog(line string)
}

type StatusListener interface {
	OnStateChange(state int, message string)
}

const (
	StateDisconnected = 0
	StateConnecting   = 1
	StateConnected    = 2
)

type Config struct {
	Mode         string // "ssh" atau "v2ray"
	UseSlowDNS   bool   // true = DNS Tunnel :53, false = Direct
	Domain       string
	ServerPubKey string
	Resolver     string // default: "1.1.1.1:53"
	RecordType   string // "txt", "cname", "auto"
	KcpInterval  int    // 50 (baterai) | 20 (performa)

	// Parameter SSH
	SSHHost        string
	SSHPort        int
	SSHUser        string
	SSHPassword    string
	SSHPairPayload string
	SSHEnhanced    bool

	// Parameter V2Ray (RAW TCP)
	V2RayHost string
	V2RayPort int
	V2RayUUID string
}

var (
	mu            sync.Mutex
	running       atomic.Bool
	cancelFn      context.CancelFunc
	wg            sync.WaitGroup
	tunFile       *os.File
	rawUdpConn    net.PacketConn
	smuxSession   *smux.Session
	socksListener net.Listener
	sshClient     *ssh.Client

	txBytes atomic.Int64
	rxBytes atomic.Int64
)

func StartTunnel(tunFd int, cfg *Config, protector SocketProtector, log LogListener, status StatusListener) error {
	mu.Lock()
	defer mu.Unlock()

	if running.Load() {
		return errors.New("tunnel sudah aktif")
	}
	if cfg == nil {
		return errors.New("parameter konfigurasi kosong")
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancelFn = cancel
	running.Store(true)

	txBytes.Store(0)
	rxBytes.Store(0)

	if tunFd > 0 {
		tunFile = os.NewFile(uintptr(tunFd), "/dev/tun")
	}

	updateState(status, StateConnecting, "Menghubungkan...")

	if cfg.UseSlowDNS {
		logMsg(log, fmt.Sprintf("[Core] Memulai SlowDNS: Mode %s over UDP 53", cfg.Mode))
		return startSlowDnsTunnel(ctx, cfg, protector, log, status)
	}

	logMsg(log, fmt.Sprintf("[Core] Direct Mode: %s", cfg.Mode))
	return startDirectTunnel(ctx, cfg, protector, log, status)
}

func startSlowDnsTunnel(ctx context.Context, cfg *Config, protector SocketProtector, log LogListener, status StatusListener) error {
	// 1. Validasi Domain & Public Key
	parsedDomain, err := dns.ParseName(cfg.Domain)
	if err != nil {
		stopTunnelLocked()
		updateState(status, StateDisconnected, "Domain tidak valid")
		return fmt.Errorf("domain tidak valid: %w", err)
	}

	pubkeyBytes, err := noise.DecodeKey(cfg.ServerPubKey)
	if err != nil {
		stopTunnelLocked()
		updateState(status, StateDisconnected, "Public key server tidak valid")
		return fmt.Errorf("public key server tidak valid: %w", err)
	}

	// 2. Resolve DNS Resolver
	rAddr, err := net.ResolveUDPAddr("udp", cfg.Resolver)
	if err != nil {
		stopTunnelLocked()
		updateState(status, StateDisconnected, "Resolver DNS gagal ditemukan")
		return fmt.Errorf("resolve resolver dns gagal: %w", err)
	}

	// 3. Listen UDP Socket Fisik
	conn, err := net.ListenUDP("udp", nil)
	if err != nil {
		stopTunnelLocked()
		updateState(status, StateDisconnected, "Gagal membuat socket UDP")
		return fmt.Errorf("listen udp gagal: %w", err)
	}
	rawUdpConn = conn

	// 4. Socket Protector
	rawConn, err := conn.SyscallConn()
	if err == nil {
		_ = rawConn.Control(func(fd uintptr) {
			if protector != nil {
				protector.Protect(int(fd))
			}
		})
	}

	// 5. Wrap DNSPacketConn
	pconn := dnstt.NewDNSPacketConn(conn, rAddr, parsedDomain)

	mtu := (253 - len(cfg.Domain) - 2) * 5 / 8 - 8 - 1 - 3 - 1
	if mtu < 80 {
		mtu = 80
	}

	// 6. Inisialisasi KCP
	kcpConn, err := kcp.NewConn2(rAddr, nil, 0, 0, pconn)
	if err != nil {
		stopTunnelLocked()
		updateState(status, StateDisconnected, "Gagal inisialisasi KCP")
		return fmt.Errorf("koneksi kcp gagal: %w", err)
	}
	kcpConn.SetStreamMode(true)
	kcpInterval := cfg.KcpInterval
	if kcpInterval <= 0 {
		kcpInterval = 50
	}
	kcpConn.SetNoDelay(1, kcpInterval, 2, 1)
	kcpConn.SetWindowSize(turbotunnel.QueueSize/2, turbotunnel.QueueSize/2)
	_ = kcpConn.SetMtu(mtu)

	logMsg(log, fmt.Sprintf("[SlowDNS] Melakukan handshake Noise_NK ke %s...", cfg.Domain))

	// 7. Enkripsi E2EE Noise Protocol
	noiseClient, err := noise.NewClient(kcpConn, pubkeyBytes)
	if err != nil {
		stopTunnelLocked()
		updateState(status, StateDisconnected, "Handshake Noise gagal")
		return fmt.Errorf("handshake noise gagal: %w", err)
	}

	// 8. Multiplexer Smux
	smuxConfig := smux.DefaultConfig()
	smuxConfig.Version = 2
	smuxConfig.KeepAliveTimeout = 30 * time.Second
	smuxConfig.MaxStreamBuffer = 1 * 1024 * 1024

	sess, err := smux.Client(noiseClient, smuxConfig)
	if err != nil {
		stopTunnelLocked()
		updateState(status, StateDisconnected, "Gagal membuat sesi smux")
		return fmt.Errorf("inisialisasi smux gagal: %w", err)
	}
	smuxSession = sess

	logMsg(log, "[SlowDNS] Terowongan Noise + KCP + smux terhubung")

	// 9. Jika mode SSH: Inisialisasi koneksi SSH di atas stream SlowDNS
	if cfg.Mode == "ssh" {
		logMsg(log, "[SSH] Memulai autentikasi Dropbear SSH...")
		stream, err := sess.OpenStream()
		if err != nil {
			stopTunnelLocked()
			updateState(status, StateDisconnected, "Gagal membuka stream SSH")
			return fmt.Errorf("open stream ssh gagal: %w", err)
		}

		client, err := DialSshRawTcp(stream, cfg.SSHHost, cfg.SSHPort, cfg.SSHUser, cfg.SSHPassword)
		if err != nil {
			stopTunnelLocked()
			updateState(status, StateDisconnected, fmt.Sprintf("Autentikasi SSH gagal: %v", err))
			return fmt.Errorf("autentikasi ssh gagal: %w", err)
		}
		sshClient = client
		logMsg(log, fmt.Sprintf("[SSH] Berhasil login sebagai user: %s", cfg.SSHUser))
	}

	// 10. Start Local SOCKS5 Server di 127.0.0.1:1080 untuk HEV Tunnel
	ln, err := net.Listen("tcp", "127.0.0.1:1080")
	if err != nil {
		stopTunnelLocked()
		updateState(status, StateDisconnected, "Gagal membuka port SOCKS lokal 1080")
		return fmt.Errorf("listen socks5 gagal: %w", err)
	}
	socksListener = ln

	logMsg(log, "[SOCKS5] Server lokal aktif di 127.0.0.1:1080")
	updateState(status, StateConnected, "Terhubung")

	wg.Add(1)
	go acceptSocksClients(ctx, ln, sess, cfg, log)

	return nil
}

func startDirectTunnel(ctx context.Context, cfg *Config, protector SocketProtector, log LogListener, status StatusListener) error {
	ln, err := net.Listen("tcp", "127.0.0.1:1080")
	if err != nil {
		stopTunnelLocked()
		updateState(status, StateDisconnected, "Gagal membuka port SOCKS lokal 1080")
		return fmt.Errorf("listen socks5 direct gagal: %w", err)
	}
	socksListener = ln

	logMsg(log, "[Direct] Server lokal aktif di 127.0.0.1:1080")
	updateState(status, StateConnected, "Terhubung (Direct)")

	wg.Add(1)
	go acceptSocksClients(ctx, ln, nil, cfg, log)

	return nil
}

func acceptSocksClients(ctx context.Context, ln net.Listener, sess *smux.Session, cfg *Config, log LogListener) {
	defer wg.Done()

	for {
		clientConn, err := ln.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				return
			default:
				continue
			}
		}

		go handleClient(ctx, clientConn, sess, cfg, log)
	}
}

func handleClient(ctx context.Context, local net.Conn, sess *smux.Session, cfg *Config, log LogListener) {
	defer local.Close()

	destHost, destPort, err := HandleSocks5Conn(local)
	if err != nil {
		return
	}

	var remote io.ReadWriteCloser

	if cfg.UseSlowDNS {
		if cfg.Mode == "ssh" && sshClient != nil {
			// Forward melalui channel direct-tcpip SSH
			targetAddr := fmt.Sprintf("%s:%d", destHost, destPort)
			ch, err := sshClient.Dial("tcp", targetAddr)
			if err != nil {
				return
			}
			remote = ch
		} else {
			// Forward melalui stream VLESS RAW TCP
			stream, err := sess.OpenStream()
			if err != nil {
				return
			}

			uuidClean := strings.ReplaceAll(cfg.V2RayUUID, "-", "")
			uuidBytes, _ := hex.DecodeString(uuidClean)
			if len(uuidBytes) != 16 {
				uuidBytes = make([]byte, 16)
			}

			vlessHeader := BuildVlessRawTcpHeader(uuidBytes, destHost, destPort)
			if _, err := stream.Write(vlessHeader); err != nil {
				stream.Close()
				return
			}
			remote = stream
		}
	} else {
		// Direct dial ke target
		targetAddr := fmt.Sprintf("%s:%d", destHost, destPort)
		ch, err := net.DialTimeout("tcp", targetAddr, 10*time.Second)
		if err != nil {
			return
		}
		remote = ch
	}
	defer remote.Close()

	pipeTraffic(local, remote)
}

func pipeTraffic(a io.ReadWriter, b io.ReadWriter) {
	var pipeWg sync.WaitGroup
	pipeWg.Add(2)

	// a -> b (Upload / Tx)
	go func() {
		defer pipeWg.Done()
		buf := make([]byte, 2048)
		for {
			n, err := a.Read(buf)
			if n > 0 {
				nw, _ := b.Write(buf[:n])
				txBytes.Add(int64(nw))
			}
			if err != nil {
				break
			}
		}
	}()

	// b -> a (Download / Rx)
	go func() {
		defer pipeWg.Done()
		buf := make([]byte, 2048)
		for {
			n, err := b.Read(buf)
			if n > 0 {
				nw, _ := a.Write(buf[:n])
				rxBytes.Add(int64(nw))
			}
			if err != nil {
				break
			}
		}
	}()

	pipeWg.Wait()
}

func BuildVlessRawTcpHeader(uuidBytes []byte, targetHost string, targetPort uint16) []byte {
	buf := make([]byte, 0, 64)
	buf = append(buf, 0) // Version 0
	buf = append(buf, uuidBytes...)
	buf = append(buf, 0) // Addons length 0
	buf = append(buf, 1) // Command: TCP stream

	portBuf := make([]byte, 2)
	binary.BigEndian.PutUint16(portBuf, targetPort)
	buf = append(buf, portBuf...)

	buf = append(buf, 2) // Address type: Domain
	buf = append(buf, byte(len(targetHost)))
	buf = append(buf, []byte(targetHost)...)
	return buf
}

func DialSshRawTcp(conn net.Conn, host string, port int, user, pass string) (*ssh.Client, error) {
	if host == "" {
		host = "127.0.0.1"
	}
	if port <= 0 {
		port = 22
	}
	targetAddr := fmt.Sprintf("%s:%d", host, port)

	sshConfig := &ssh.ClientConfig{
		User: user,
		Auth: []ssh.AuthMethod{
			ssh.Password(pass),
		},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         15 * time.Second,
	}

	c, chans, reqs, err := ssh.NewClientConn(conn, targetAddr, sshConfig)
	if err != nil {
		return nil, err
	}
	return ssh.NewClient(c, chans, reqs), nil
}

func StopTunnel() {
	mu.Lock()
	defer mu.Unlock()
	stopTunnelLocked()
}

func stopTunnelLocked() {
	if !running.Load() {
		return
	}

	if cancelFn != nil {
		cancelFn()
	}

	if socksListener != nil {
		_ = socksListener.Close()
		socksListener = nil
	}
	if sshClient != nil {
		_ = sshClient.Close()
		sshClient = nil
	}
	if smuxSession != nil {
		_ = smuxSession.Close()
		smuxSession = nil
	}
	if rawUdpConn != nil {
		_ = rawUdpConn.Close()
		rawUdpConn = nil
	}
	if tunFile != nil {
		_ = tunFile.Close()
		tunFile = nil
	}

	wg.Wait()
	running.Store(false)
}

func IsRunning() bool {
	return running.Load()
}

func GetTxBytes() int64 {
	return txBytes.Load()
}

func GetRxBytes() int64 {
	return rxBytes.Load()
}

func logMsg(l LogListener, msg string) {
	if l != nil {
		l.OnLog(msg)
	}
}

func updateState(s StatusListener, state int, msg string) {
	if s != nil {
		s.OnStateChange(state, msg)
	}
}
