package dev.epro.sock5tunnel

import com.daisy.mashiro.logger.AppLogger

object Socks5Tunnel {
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.log("[Warning] Gagal memuat libhev-socks5-tunnel.so: ${e.message}")
            isLoaded = false
        }
    }

    fun isAvailable(): Boolean = isLoaded

    @JvmStatic
    external fun tProxyStartService(configYaml: String, tunFd: Int)

    @JvmStatic
    external fun tProxyStopService()

    @JvmStatic
    external fun tProxyGetStats(): LongArray?

    fun generateConfig(socksHost: String = "127.0.0.1", socksPort: Int = 1080): String {
        return """
tunnel:
  name: tun0
  mtu: 1500
  ipv4: 10.0.0.2

socks5:
  port: $socksPort
  address: $socksHost
  udp: 'tcp'

misc:
  task-stack-size: 8192
  connect-timeout: 5000
  read-write-timeout: 60000
  log-level: warn
""".trimIndent()
    }
}
