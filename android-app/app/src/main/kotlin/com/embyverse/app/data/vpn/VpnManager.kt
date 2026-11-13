package com.embyverse.app.data.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.embyverse.app.data.secure.CredentialStore
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * VPN integrada (WireGuard). El cliente pega o elige el archivo de configuración «.conf» que le da su
 * proveedor de VPN (Proton VPN, Mullvad, Surfshark, Windscribe, su propio servidor…) y la conecta desde la
 * app. Por defecto la VPN se aplica SOLO a esta app (el resto del TV o del celular sigue igual).
 * La configuración (incluye la clave privada) se guarda cifrada con la llave del equipo (CredentialStore).
 */
class VpnManager(private val context: Context, private val secure: CredentialStore) {
    enum class State { OFF, CONNECTING, ON, ERROR }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state
    private val _message = MutableStateFlow<String?>(null)
    /** Detalle del último error (en palabras simples). */
    val message: StateFlow<String?> = _message

    private val backend by lazy { GoBackend(context.applicationContext) }
    private val lock = Mutex()
    private val tunnel = object : Tunnel {
        override fun getName() = "app"
        override fun onStateChange(newState: Tunnel.State) {
            _state.value = if (newState == Tunnel.State.UP) State.ON else State.OFF
        }
    }

    private companion object { const val KEY = "vpn.wireguard"; const val TAG = "EmbyVerseVpn" }

    fun hasConfig() = !secure.get(KEY).isNullOrBlank()

    /** Nombre del servidor de la configuración guardada (para mostrarlo), sin claves. */
    fun summary(): String? = runCatching { parse(secure.get(KEY) ?: return null).peers.firstOrNull()?.endpoint?.orElse(null)?.host }.getOrNull()

    private fun parse(text: String): Config = text.byteInputStream().use { Config.parse(it) }

    /** Revisa y guarda la configuración. Devuelve el error en palabras simples, o null si quedó bien. */
    fun save(text: String): String? {
        val clean = text.trim()
        return try {
            val cfg = parse(clean)
            if (cfg.peers.isEmpty()) return "La configuración no tiene servidor ([Peer])."
            secure.put(KEY, clean); null
        } catch (e: Exception) {
            "No es una configuración WireGuard válida. Debe empezar con [Interface] y tener PrivateKey, Address y un [Peer]."
        }
    }

    fun forget() { secure.put(KEY, "") }

    /** Intento de Android que pide permiso de VPN la primera vez (null = ya está concedido). */
    fun permissionIntent(): Intent? = VpnService.prepare(context)

    /**
     * Conecta. [appOnly] = solo el tráfico de esta app pasa por la VPN.
     * Debe llamarse con el permiso de VPN ya concedido (ver [permissionIntent]).
     */
    suspend fun connect(appOnly: Boolean = true): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val text = secure.get(KEY)
            if (text.isNullOrBlank()) { _message.value = "Primero agrega la configuración de tu VPN."; _state.value = State.ERROR; return@withContext false }
            if (VpnService.prepare(context) != null) { _message.value = "Falta dar permiso de VPN a la app."; _state.value = State.ERROR; return@withContext false }
            _state.value = State.CONNECTING; _message.value = null
            try {
                var cfg = parse(text)
                if (appOnly) {
                    // Interface nueva con la lista de apps incluidas = solo esta app.
                    val i = cfg.`interface`
                    val b = Interface.Builder().setKeyPair(i.keyPair).addAddresses(i.addresses).addDnsServers(i.dnsServers)
                        .includeApplication(context.packageName)
                    i.listenPort.ifPresent { b.setListenPort(it) }
                    i.mtu.ifPresent { b.setMtu(it) }
                    cfg = Config.Builder().setInterface(b.build()).addPeers(cfg.peers).build()
                }
                backend.setState(tunnel, Tunnel.State.UP, cfg)
                _state.value = State.ON
                true
            } catch (e: Exception) {
                Log.w(TAG, "no conectó: ${e.javaClass.simpleName}: ${e.message}")
                _message.value = "No se pudo conectar la VPN. Revisa la configuración o tu internet."
                _state.value = State.ERROR
                false
            }
        }
    }

    suspend fun disconnect() = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            _state.value = State.OFF
        }
    }

    /** Datos recibidos por la VPN (bytes), para mostrar que sí está pasando tráfico. */
    fun receivedBytes(): Long = runCatching { backend.getStatistics(tunnel).totalRx() }.getOrDefault(0L)
}
