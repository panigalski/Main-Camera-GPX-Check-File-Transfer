package com.labpano.gpxextractor.api

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Best-effort camera-side Bluetooth/location/GNSS diagnostics for the companion client.
 *
 * Important boundaries:
 * - We never start Bluetooth discovery or open a second Bluetooth connection just to obtain RSSI.
 *   Doing either can disturb a production GPS receiver connection. RSSI is therefore reported only
 *   when Android passively exposes a recent discovery measurement for the already-connected device.
 * - Location source classification comes from Android Location metadata. Mocked locations are
 *   detectable; the identity of the app injecting them is not exposed by the public Location API.
 * - GnssStatus describes the Android system GNSS receiver. When the current fix is injected/mock,
 *   satellite C/N0 data may not belong to the external receiver that produced that fix.
 */
object DeviceDiagnosticsRegistry {
    private const val LOCATION_FRESH_MS = 15_000L
    private const val RSSI_FRESH_MS = 120_000L
    private const val GNSS_FRESH_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val observedRssi = ConcurrentHashMap<String, RssiObservation>()
    private val observedAclDevices = ConcurrentHashMap<String, BluetoothDevice>()

    @Volatile private var bluetoothReceiverRegistered = false
    @Volatile private var locationStarted = false
    @Volatile private var latestLocation: LocationSnapshot? = null
    @Volatile private var gnssSnapshot = GnssSnapshot()

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
            when (action) {
                BluetoothDevice.ACTION_FOUND -> {
                    device ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (rssi != Short.MIN_VALUE.toInt()) {
                        observedRssi[device.address.orEmpty()] = RssiObservation(rssi, System.currentTimeMillis())
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    device ?: return
                    observedAclDevices[device.address.orEmpty()] = device
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    device ?: return
                    observedAclDevices.remove(device.address.orEmpty())
                }
            }
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestLocation = location.toSnapshot()
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onStarted() {
            gnssSnapshot = gnssSnapshot.copy(
                running = true,
                firstFixMs = null,
                updatedAt = System.currentTimeMillis()
            )
        }

        override fun onStopped() {
            gnssSnapshot = gnssSnapshot.copy(
                running = false,
                updatedAt = System.currentTimeMillis()
            )
        }

        override fun onFirstFix(ttffMillis: Int) {
            gnssSnapshot = gnssSnapshot.copy(
                firstFixMs = ttffMillis.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis()
            )
        }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var cn0Sum = 0.0
            var cn0Count = 0
            var maxCn0: Double? = null
            val constellationCounts = linkedMapOf<String, Int>()
            val usedConstellationCounts = linkedMapOf<String, Int>()

            for (index in 0 until status.satelliteCount) {
                val constellation = constellationName(status.getConstellationType(index))
                constellationCounts[constellation] = (constellationCounts[constellation] ?: 0) + 1
                val inFix = status.usedInFix(index)
                if (inFix) {
                    used++
                    usedConstellationCounts[constellation] = (usedConstellationCounts[constellation] ?: 0) + 1
                }
                val cn0 = status.getCn0DbHz(index).toDouble()
                if (cn0.isFinite() && cn0 >= 0.0) {
                    cn0Sum += cn0
                    cn0Count++
                    maxCn0 = maxOf(maxCn0 ?: cn0, cn0)
                }
            }

            gnssSnapshot = GnssSnapshot(
                running = true,
                satellitesVisible = status.satelliteCount,
                satellitesUsedInFix = used,
                averageCn0DbHz = if (cn0Count > 0) cn0Sum / cn0Count else null,
                maxCn0DbHz = maxCn0,
                firstFixMs = gnssSnapshot.firstFixMs,
                constellations = constellationCounts,
                usedConstellations = usedConstellationCounts,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun ensureStarted(context: Context) {
        val appContext = context.applicationContext
        ensureBluetoothReceiver(appContext)
        if (locationStarted || !hasFineLocationPermission(appContext)) return
        synchronized(this) {
            if (locationStarted || !hasFineLocationPermission(appContext)) return
            locationStarted = true
            mainHandler.post {
                val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    ?: return@post
                runCatching {
                    // Passive updates observe the provider already being used by the camera/OS without
                    // turning on the internal GNSS receiver merely for diagnostics.
                    manager.requestLocationUpdates(
                        LocationManager.PASSIVE_PROVIDER,
                        0L,
                        0f,
                        locationListener,
                        Looper.getMainLooper()
                    )
                }
                initializeLastKnownLocation(manager)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    runCatching { manager.registerGnssStatusCallback(gnssCallback, mainHandler) }
                }
            }
        }
    }

    fun toJson(context: Context): JSONObject {
        ensureStarted(context)
        val now = System.currentTimeMillis()
        val bluetooth = bluetoothSnapshot(context, now)
        val location = freshestLocationSnapshot(context)
        val locationJson = locationJson(location, bluetooth.devices, now)
        val gnss = gnssSnapshot

        val permissionGranted = hasFineLocationPermission(context)
        val signalMatchesActiveSource = location != null &&
            !location.mocked && location.provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true)
        return JSONObject().apply {
            put("bluetooth", bluetooth.toJson(now))
            put("location", locationJson)
            put("gnss", gnss.toJson(now, permissionGranted, location?.mocked == true, signalMatchesActiveSource))
        }
    }

    private fun ensureBluetoothReceiver(context: Context) {
        if (bluetoothReceiverRegistered) return
        synchronized(this) {
            if (bluetoothReceiverRegistered) return
            runCatching {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                }
                @Suppress("DEPRECATION")
                context.registerReceiver(bluetoothReceiver, filter)
                bluetoothReceiverRegistered = true
            }
        }
    }

    private fun initializeLastKnownLocation(manager: LocationManager) {
        val candidates = listOf(
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        candidates.maxByOrNull { it.time }?.let { candidate ->
            val current = latestLocation
            if (current == null || candidate.time > current.time) latestLocation = candidate.toSnapshot()
        }
    }

    private fun freshestLocationSnapshot(context: Context): LocationSnapshot? {
        if (!hasFineLocationPermission(context)) return null
        val current = latestLocation
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return current
        val lastKnown = listOf(
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toSnapshot()
        return when {
            current == null -> lastKnown
            lastKnown == null -> current
            lastKnown.time > current.time -> lastKnown
            else -> current
        }
    }

    private fun bluetoothSnapshot(context: Context, now: Long): BluetoothSnapshot {
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return BluetoothSnapshot(false, false, emptyList(), "Bluetooth adapter unavailable")
        if (!adapter.isEnabled) return BluetoothSnapshot(true, false, emptyList(), "Bluetooth is off")

        val devicesByAddress = linkedMapOf<String, MutableBluetoothDevice>()
        val errors = mutableListOf<String>()

        val bonded = runCatching { adapter.bondedDevices.orEmpty() }.getOrElse {
            errors += "Bonded-device query failed"
            emptySet()
        }
        bonded.forEach { device ->
            if (isConnectedByReflection(device)) {
                devicesByAddress.getOrPut(device.address.orEmpty()) { MutableBluetoothDevice(device) }
                    .transports += "Classic/system"
            }
        }

        observedAclDevices.values.forEach { device ->
            devicesByAddress.getOrPut(device.address.orEmpty()) { MutableBluetoothDevice(device) }
                .transports += "ACL/system"
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val gattDevices = runCatching {
            manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
        }.getOrElse {
            errors += "GATT query failed"
            emptyList()
        }
        gattDevices.forEach { device ->
            devicesByAddress.getOrPut(device.address.orEmpty()) { MutableBluetoothDevice(device) }
                .transports += "BLE GATT"
        }

        val devices = devicesByAddress.values.map { mutable ->
            val name = runCatching { mutable.device.name }.getOrNull().orEmpty()
            val address = mutable.device.address.orEmpty()
            val rssiObservation = observedRssi[address]?.takeIf { now - it.observedAt <= RSSI_FRESH_MS }
            ConnectedBluetoothDevice(
                name = name.ifBlank { "Unnamed Bluetooth device" },
                address = address,
                transports = mutable.transports.toList(),
                likelyGps = isLikelyGpsDevice(name),
                rssiDbm = rssiObservation?.rssiDbm,
                rssiObservedAt = rssiObservation?.observedAt ?: 0L
            )
        }.sortedWith(compareByDescending<ConnectedBluetoothDevice> { it.likelyGps }.thenBy { it.name.lowercase(Locale.US) })

        return BluetoothSnapshot(
            available = true,
            enabled = true,
            devices = devices,
            error = errors.joinToString("; ")
        )
    }

    private fun isConnectedByReflection(device: BluetoothDevice): Boolean {
        return runCatching {
            val method = device.javaClass.getMethod("isConnected")
            method.isAccessible = true
            method.invoke(device) as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun isLikelyGpsDevice(name: String): Boolean {
        val normalized = name.lowercase(Locale.US)
        return GPS_NAME_HINTS.any { normalized.contains(it) }
    }

    private fun locationJson(
        location: LocationSnapshot?,
        connectedBluetoothDevices: List<ConnectedBluetoothDevice>,
        now: Long
    ): JSONObject {
        val permissionGranted = locationStarted
        if (location == null) {
            return JSONObject().apply {
                put("available", false)
                put("permissionGranted", permissionGranted)
                put("fresh", false)
                put("sourceType", "UNKNOWN")
                put("sourceLabel", if (permissionGranted) "No location fix observed" else "Location permission required")
                put("provider", "")
                put("mocked", false)
                put("lastFixAt", 0L)
                put("accuracyMeters", JSONObject.NULL)
                put("latitude", JSONObject.NULL)
                put("longitude", JSONObject.NULL)
                put("altitudeMeters", JSONObject.NULL)
                put("speedMps", JSONObject.NULL)
                put("bearingDegrees", JSONObject.NULL)
            }
        }

        val likelyGpsBluetooth = connectedBluetoothDevices.firstOrNull { it.likelyGps }
        val classification = classifyLocation(location, likelyGpsBluetooth)
        return JSONObject().apply {
            put("available", true)
            put("permissionGranted", permissionGranted)
            put("fresh", now - location.time <= LOCATION_FRESH_MS)
            put("sourceType", classification.first)
            put("sourceLabel", classification.second)
            put("provider", location.provider)
            put("mocked", location.mocked)
            put("lastFixAt", location.time)
            put("accuracyMeters", location.accuracyMeters ?: JSONObject.NULL)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("altitudeMeters", location.altitudeMeters ?: JSONObject.NULL)
            put("speedMps", location.speedMps ?: JSONObject.NULL)
            put("bearingDegrees", location.bearingDegrees ?: JSONObject.NULL)
            put("inferredExternalBluetoothDevice", likelyGpsBluetooth?.name ?: "")
        }
    }

    private fun classifyLocation(
        location: LocationSnapshot,
        likelyGpsBluetooth: ConnectedBluetoothDevice?
    ): Pair<String, String> {
        if (location.mocked) {
            return if (likelyGpsBluetooth != null) {
                "EXTERNAL_BLUETOOTH_MOCK" to "External Bluetooth GPS via mocked location (inferred: ${likelyGpsBluetooth.name})"
            } else {
                "MOCKED" to "Mocked by another app"
            }
        }
        return when (location.provider.lowercase(Locale.US)) {
            LocationManager.GPS_PROVIDER -> "INTERNAL_GNSS" to "Internal/system GNSS"
            "fused" -> "SYSTEM_FUSED" to "Android fused/system location"
            LocationManager.NETWORK_PROVIDER -> "NETWORK" to "Android network location"
            else -> "SYSTEM_OTHER" to "Android system provider: ${location.provider.ifBlank { "unknown" }}"
        }
    }

    private fun hasFineLocationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun Location.toSnapshot(): LocationSnapshot = LocationSnapshot(
        provider = provider.orEmpty(),
        time = time,
        mocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) isFromMockProvider else false,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = if (hasAltitude()) altitude else null,
        speedMps = if (hasSpeed()) speed.toDouble() else null,
        bearingDegrees = if (hasBearing()) bearing.toDouble() else null
    )

    private fun constellationName(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        else -> "Unknown"
    }

    private data class RssiObservation(val rssiDbm: Int, val observedAt: Long)
    private data class MutableBluetoothDevice(
        val device: BluetoothDevice,
        val transports: LinkedHashSet<String> = linkedSetOf()
    )
    private data class ConnectedBluetoothDevice(
        val name: String,
        val address: String,
        val transports: List<String>,
        val likelyGps: Boolean,
        val rssiDbm: Int?,
        val rssiObservedAt: Long
    )
    private data class BluetoothSnapshot(
        val available: Boolean,
        val enabled: Boolean,
        val devices: List<ConnectedBluetoothDevice>,
        val error: String
    ) {
        fun toJson(now: Long): JSONObject = JSONObject().apply {
            put("available", available)
            put("enabled", enabled)
            put("connectedDeviceCount", devices.size)
            put("error", error)
            put("devices", JSONArray().apply {
                devices.forEach { device ->
                    put(JSONObject().apply {
                        put("name", device.name)
                        put("address", device.address)
                        put("transport", device.transports.joinToString(" + "))
                        put("likelyGps", device.likelyGps)
                        put("rssiAvailable", device.rssiDbm != null)
                        put("rssiDbm", device.rssiDbm ?: JSONObject.NULL)
                        put("rssiObservedAt", device.rssiObservedAt)
                        put("rssiAgeMs", if (device.rssiObservedAt > 0L) (now - device.rssiObservedAt).coerceAtLeast(0L) else JSONObject.NULL)
                        put(
                            "rssiNote",
                            if (device.rssiDbm != null) "Passively observed Bluetooth RSSI" else
                                "RSSI not exposed for this connection without opening/scanning Bluetooth"
                        )
                    })
                }
            })
        }
    }

    private data class LocationSnapshot(
        val provider: String,
        val time: Long,
        val mocked: Boolean,
        val accuracyMeters: Double?,
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double?,
        val speedMps: Double?,
        val bearingDegrees: Double?
    )

    private data class GnssSnapshot(
        val running: Boolean = false,
        val satellitesVisible: Int = 0,
        val satellitesUsedInFix: Int = 0,
        val averageCn0DbHz: Double? = null,
        val maxCn0DbHz: Double? = null,
        val firstFixMs: Int? = null,
        val constellations: Map<String, Int> = emptyMap(),
        val usedConstellations: Map<String, Int> = emptyMap(),
        val updatedAt: Long = 0L
    ) {
        fun toJson(
            now: Long,
            permissionGranted: Boolean,
            activeLocationMocked: Boolean,
            signalMatchesActiveLocationSource: Boolean
        ): JSONObject = JSONObject().apply {
            put("supported", true)
            put("permissionGranted", permissionGranted)
            put("running", running)
            put("fresh", updatedAt > 0L && now - updatedAt <= GNSS_FRESH_MS)
            put("satellitesVisible", satellitesVisible)
            put("satellitesUsedInFix", satellitesUsedInFix)
            put("averageCn0DbHz", averageCn0DbHz ?: JSONObject.NULL)
            put("maxCn0DbHz", maxCn0DbHz ?: JSONObject.NULL)
            put("firstFixMs", firstFixMs ?: JSONObject.NULL)
            put("updatedAt", updatedAt)
            put("activeLocationMocked", activeLocationMocked)
            put("signalMatchesActiveLocationSource", signalMatchesActiveLocationSource)
            put("constellations", JSONObject().apply {
                constellations.forEach { (name, count) -> put(name, count) }
            })
            put("usedConstellations", JSONObject().apply {
                usedConstellations.forEach { (name, count) -> put(name, count) }
            })
        }
    }

    private val GPS_NAME_HINTS = listOf(
        "gps", "gnss", "garmin", "qstarz", "holux", "ublox", "u-blox", "skytraq", "navilock", "globalsat", "bad elf"
    )
}
