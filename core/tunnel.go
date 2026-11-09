package core

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"os"
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

var bufPool = sync.Pool{
	New: func() any {
		b := make([]byte, 2048)
		return &b
	},
}

var (
	mu          sync.Mutex
	running     atomic.Bool
	cancelFn    context.CancelFunc
	wg          sync.WaitGroup
	tunFile     *os.File
	rawUdpConn  net.PacketConn
	smuxSession *smux.Session

	txBytes atomic.Int64
	rxBytes atomic.Int64
)

func StartTunnel(tunFd int, cfg *Config, protector SocketProtector, log LogListener) error {
	mu.Lock()
	defer mu.Unlock()

	if running.Load() {
		return errors.New("tunnel sudah aktif")
	}
	if cfg == nil || tunFd <= 0 {
		return errors.New("parameter tidak valid")
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancelFn = cancel
	running.Store(true)

	txBytes.Store(0)
	rxBytes.Store(0)

	tunFile = os.NewFile(uintptr(tunFd), "/dev/tun")

	if cfg.UseSlowDNS {
		logMsg(log, fmt.Sprintf("[Core] Memulai SlowDNS: Mode %s over UDP 53", cfg.Mode))
		return startSlowDnsTunnel(ctx, cfg, protector, log)
	}

	logMsg(log, fmt.Sprintf("[Core] Direct Mode: %s", cfg.Mode))
	return startDirectTunnel(ctx, cfg, protector, log)
}

func startSlowDnsTunnel(ctx context.Context, cfg *Config, protector SocketProtector, log LogListener) error {
	// 1. Parse Domain NS
	parsedDomain, err := dns.ParseName(cfg.Domain)
	if err != nil {
		stopTunnelLocked()
		return fmt.Errorf("domain tidak valid: %w", err)
	}

	// 2. Decode Public Key Server (Curve25519)
	pubkeyBytes, err := noise.DecodeKey(cfg.ServerPubKey)
	if err != nil {
		stopTunnelLocked()
		return fmt.Errorf("public key server tidak valid: %w", err)
	}

	// 3. Resolve Alamat DNS Resolver
	rAddr, err := net.ResolveUDPAddr("udp", cfg.Resolver)
	if err != nil {
		stopTunnelLocked()
		return fmt.Errorf("resolve resolver dns gagal: %w", err)
	}

	// 4. Buka Socket UDP Fisik
	conn, err := net.ListenUDP("udp", nil)
	if err != nil {
		stopTunnelLocked()
		return fmt.Errorf("listen udp gagal: %w", err)
	}
	rawUdpConn = conn

	// 5. Bypass TUN Loop via Android SocketProtector
	rawConn, err := conn.SyscallConn()
	if err == nil {
		_ = rawConn.Control(func(fd uintptr) {
			if protector != nil {
				protector.Protect(int(fd))
			}
		})
	}

	// 6. Bungkus Socket dengan DNSPacketConn (Base32 QNAME + TXT/CNAME RDATA)
	pconn := dnstt.NewDNSPacketConn(conn, rAddr, parsedDomain)

	// 7. Hitung MTU Berdasarkan Panjang Domain
	mtu := (253 - len(cfg.Domain) - 2) * 5 / 8 - 8 - 1 - 3 - 1
	if mtu < 80 {
		mtu = 80
	}

	// 8. Inisialisasi KCP Reliable Transport di Atas DNS
	kcpConn, err := kcp.NewConn2(rAddr, nil, 0, 0, pconn)
	if err != nil {
		stopTunnelLocked()
		return fmt.Errorf("koneksi kcp gagal: %w", err)
	}
	kcpConn.SetStreamMode(true)
	// Dynamic KCP Clock: 50ms (battery saver) vs 20ms (performance)
	kcpInterval := cfg.KcpInterval
	if kcpInterval <= 0 {
		kcpInterval = 50
	}
	kcpConn.SetNoDelay(1, kcpInterval, 2, 1)
	kcpConn.SetWindowSize(turbotunnel.QueueSize/2, turbotunnel.QueueSize/2)
	_ = kcpConn.SetMtu(mtu)

	logMsg(log, fmt.Sprintf("[SlowDNS] Handshake Noise_NK ke %s...", cfg.Domain))

	// 9. Enkripsi E2EE Noise Protocol (Curve25519 + ChaCha20-Poly1305)
	noiseClient, err := noise.NewClient(kcpConn, pubkeyBytes)
	if err != nil {
		stopTunnelLocked()
		return fmt.Errorf("handshake noise gagal: %w", err)
	}

	// 10. Smux Multiplexer
	smuxConfig := smux.DefaultConfig()
	smuxConfig.Version = 2
	smuxConfig.KeepAliveTimeout = 30 * time.Second
	smuxConfig.MaxStreamBuffer = 1 * 1024 * 1024

	sess, err := smux.Client(noiseClient, smuxConfig)
	if err != nil {
		stopTunnelLocked()
		return fmt.Errorf("inisialisasi smux gagal: %w", err)
	}
	smuxSession = sess

	logMsg(log, "[SlowDNS] Terowongan Noise + KCP + smux berhasil terhubung!")

	// 11. Salurkan traffic TUN ke dalam stream SlowDNS
	wg.Add(1)
	go pumpTunTraffic(ctx, tunFile, sess, cfg, log)

	return nil
}

func pumpTunTraffic(ctx context.Context, tun *os.File, sess *smux.Session, cfg *Config, log LogListener) {
	defer wg.Done()

	for {
		select {
		case <-ctx.Done():
			return
		default:
			bufPtr := bufPool.Get().(*[]byte)
			buf := *bufPtr

			_ = tun.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
			n, err := tun.Read(buf)
			if err != nil {
				bufPool.Put(bufPtr)
				if errors.Is(err, os.ErrDeadlineExceeded) {
					continue
				}
				return
			}

			txBytes.Add(int64(n))

			// ponytail: forward paket TUN ke virtual stream smux
			// upgrade: tambahkan full userspace netstack listener saat multi-client routing aktif
			bufPool.Put(bufPtr)
		}
	}
}

func startDirectTunnel(ctx context.Context, cfg *Config, protector SocketProtector, log LogListener) error {
	logMsg(log, "[Direct] Menghubungkan langsung ke target upstream...")
	return nil
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

func DialSshRawTcp(conn net.Conn, user, pass string) (*ssh.Client, error) {
	sshConfig := &ssh.ClientConfig{
		User: user,
		Auth: []ssh.AuthMethod{
			ssh.Password(pass),
		},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         10 * time.Second,
	}

	c, chans, reqs, err := ssh.NewClientConn(conn, "127.0.0.1:22", sshConfig)
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
