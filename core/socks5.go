package core

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
)

// HandleSocks5Conn memproses handshake SOCKS5 dan mengembalikan alamat target
func HandleSocks5Conn(c net.Conn) (string, uint16, error) {
	// 1. Handshake
	var header [2]byte
	if _, err := io.ReadFull(c, header[:]); err != nil {
		return "", 0, err
	}
	if header[0] != 0x05 {
		return "", 0, errors.New("versi socks tidak didukung")
	}
	numMethods := int(header[1])
	methods := make([]byte, numMethods)
	if _, err := io.ReadFull(c, methods); err != nil {
		return "", 0, err
	}
	// Balas metode 0x00 (tanpa autentikasi)
	if _, err := c.Write([]byte{0x05, 0x00}); err != nil {
		return "", 0, err
	}

	// 2. Request CONNECT
	var reqHeader [4]byte
	if _, err := io.ReadFull(c, reqHeader[:]); err != nil {
		return "", 0, err
	}
	if reqHeader[0] != 0x05 || reqHeader[1] != 0x01 {
		return "", 0, errors.New("hanya SOCKS5 CONNECT yang didukung")
	}

	var targetHost string
	switch reqHeader[3] { // ATYP
	case 0x01: // IPv4
		var ip [4]byte
		if _, err := io.ReadFull(c, ip[:]); err != nil {
			return "", 0, err
		}
		targetHost = net.IP(ip[:]).String()
	case 0x03: // Domain
		var lenBuf [1]byte
		if _, err := io.ReadFull(c, lenBuf[:]); err != nil {
			return "", 0, err
		}
		domainBytes := make([]byte, lenBuf[0])
		if _, err := io.ReadFull(c, domainBytes); err != nil {
			return "", 0, err
		}
		targetHost = string(domainBytes)
	case 0x04: // IPv6
		var ip [16]byte
		if _, err := io.ReadFull(c, ip[:]); err != nil {
			return "", 0, err
		}
		targetHost = net.IP(ip[:]).String()
	default:
		return "", 0, fmt.Errorf("atyp tidak didukung: %d", reqHeader[3])
	}

	var portBuf [2]byte
	if _, err := io.ReadFull(c, portBuf[:]); err != nil {
		return "", 0, err
	}
	targetPort := binary.BigEndian.Uint16(portBuf[:])

	// Balas sukses ke klien SOCKS (0x05, 0x00, 0x00, 0x01, BND.ADDR 0, BND.PORT 0)
	if _, err := c.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return "", 0, err
	}

	return targetHost, targetPort, nil
}
