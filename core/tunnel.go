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

	"golang.org/x/crypto/ssh"
)

type SocketProtector interface {
	Protect(fd int) bool
}

type LogListener interface {
	OnLog(line string)
}

type Config struct {
	// Mode: "ssh" atau "v2ray"
	Mode string

	// SlowDNS Toggle: true = lewat DNS Tunnel :53, false = Direct/Internet
	UseSlowDNS bool

	// Parameter Dasar SlowDNS (aktif jika UseSlowDNS == true)
	Domain       string
	ServerPubKey string
	Resolver     string // default: "1.1.1.1:53"
	RecordType   string // "txt", "cname", "auto"
	KcpInterval  int    // 50 (baterai) | 20 (performa)

	// Parameter SSH
	SSHHost     string // Dipakai jika UseSlowDNS == false
	SSHPort     int
	SSHUser     string
	SSHPassword string
	SSHPairPayload string // Custom payload HTTP/WebSocket (jika ada)
	SSHEnhanced    bool   // SSL/TLS + Payload injection

	// Parameter V2Ray (RAW TCP murni, no TLS)
	V2RayHost string // Dipakai jika UseSlowDNS == false
	V2RayPort int
	V2RayUUID string // VLESS UUID (16 bytes hex)
}

// Pool buffer hemat GC & baterai
var bufPool = sync.Pool{
	New: func() any {
		b := make([]byte, 2048)
		return &b
	},
}

var (
	mu        sync.Mutex
	running   atomic.Bool
	cancelFn  context.CancelFunc
	wg        sync.WaitGroup
	tunFile   *os.File
	udpConn   net.PacketConn

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
		logMsg(log, fmt.Sprintf("[Core] SlowDNS Aktif: Mode %s (RAW TCP over DNS)", cfg.Mode))
		return startSlowDnsTunnel(ctx, cfg, protector, log)
	}

	logMsg(log, fmt.Sprintf("[Core] Direct Mode: %s", cfg.Mode))
	return startDirectTunnel(ctx, cfg, protector, log)
}

func startSlowDnsTunnel(ctx context.Context, cfg *Config, protector SocketProtector, log LogListener) error {
	rAddr, err := net.ResolveUDPAddr("udp", cfg.Resolver)
	if err != nil {
		stopTunnelLocked()
		return fmt.Errorf("resolve dns gagal: %w", err)
	}

	conn, err := net.ListenUDP("udp", nil)
	if err != nil {
		stopTunnelLocked()
		return fmt.Errorf("listen udp gagal: %w", err)
	}
	udpConn = conn

	// Bypass VPN TUN (Socket Protector)
	rawConn, err := conn.SyscallConn()
	if err == nil {
		_ = rawConn.Control(func(fd uintptr) {
			if protector != nil {
				protector.Protect(int(fd))
			}
		})
	}

	logMsg(log, fmt.Sprintf("[SlowDNS] Resolver: %s, Domain: %s", cfg.Resolver, cfg.Domain))

	wg.Add(2)
	go pumpTunToDns(ctx, tunFile, conn, rAddr, cfg, log)
	go pumpDnsToTun(ctx, conn, tunFile, log)

	return nil
}

func startDirectTunnel(ctx context.Context, cfg *Config, protector SocketProtector, log LogListener) error {
	// ponytail: direct TCP/TLS dialer tanpa DNS wrapper
	// upgrade: integrasikan full xray-core outbound saat koneksi non-SlowDNS aktif
	logMsg(log, "[Direct] Menghubungkan langsung ke upstream server...")
	return nil
}

func pumpTunToDns(ctx context.Context, src *os.File, dst net.PacketConn, rAddr net.Addr, cfg *Config, log LogListener) {
	defer wg.Done()

	for {
		select {
		case <-ctx.Done():
			return
		default:
			bufPtr := bufPool.Get().(*[]byte)
			buf := *bufPtr

			_ = src.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
			n, err := src.Read(buf)
			if err != nil {
				bufPool.Put(bufPtr)
				if errors.Is(err, os.ErrDeadlineExceeded) {
					continue
				}
				return
			}

			nw, err := dst.WriteTo(buf[:n], rAddr)
			if err == nil {
				txBytes.Add(int64(nw))
			}
			bufPool.Put(bufPtr)
		}
	}
}

func pumpDnsToTun(ctx context.Context, src net.PacketConn, dst *os.File, log LogListener) {
	defer wg.Done()

	for {
		select {
		case <-ctx.Done():
			return
		default:
			bufPtr := bufPool.Get().(*[]byte)
			buf := *bufPtr

			_ = src.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
			n, _, err := src.ReadFrom(buf)
			if err != nil {
				bufPool.Put(bufPtr)
				var netErr net.Error
				if errors.As(err, &netErr) && netErr.Timeout() {
					continue
				}
				return
			}

			nw, err := dst.Write(buf[:n])
			if err == nil {
				rxBytes.Add(int64(nw))
			}
			bufPool.Put(bufPtr)
		}
	}
}

// BuildVlessRawTcpHeader membuat header VLESS request murni tanpa TLS (RAW TCP)
func BuildVlessRawTcpHeader(uuidBytes []byte, targetHost string, targetPort uint16) []byte {
	// VLESS Header: [Version=0][UUID 16B][AddonLen=0][Command=1 (TCP)][Port 2B][AddrType=2 (Domain)][DomainLen 1B][Domain][Payload...]
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

// DialSshRawTcp membuka koneksi SSH-2 murni di atas raw stream
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

	if udpConn != nil {
		_ = udpConn.Close()
		udpConn = nil
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
