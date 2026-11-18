package com.bloqueador.android.dominio

object Paquetes {
    /** Nunca se pueden agregar a la lista: partes del sistema, el teléfono y emergencias. */
    val PROTEGIDOS: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        // En Xiaomi, Huawei, Oppo y otros, el marcador y la pantalla de llamada viven aquí.
        "com.android.contacts",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.cellbroadcastreceiver",
        "com.google.android.cellbroadcastreceiver",
    )

    /**
     * Con el modo estricto, durante un bloqueo también se bloquean estas apps para que no
     * puedas desactivar el Bloqueador, desconectar su VPN ni desinstalarlo hasta que termine.
     */
    val AJUSTES: Set<String> = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.vpndialogs",
        // Ajustes propios de los fabricantes, desde donde también se puede forzar la detención,
        // quitar permisos o desinstalar. ServicioAccesibilidad suma además los paquetes que
        // abren las pantallas de Ajustes en cada teléfono.
        "com.miui.securitycenter",
        "com.miui.packageinstaller",
        "com.samsung.accessibility",
        "com.samsung.android.lool",
        "com.huawei.systemmanager",
        "com.hihonor.systemmanager",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
    )

    /**
     * Con el modo estricto, durante un bloqueo también se bloquean las tiendas, para que no
     * puedas instalar otra app (una versión Lite, un navegador con VPN) que se salte el bloqueo.
     * No son protegidas: también se pueden bloquear a mano.
     */
    val TIENDAS: Set<String> = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.xiaomi.market",
        "com.xiaomi.mipicks",
        "com.heytap.market",
        "com.oppo.market",
        "com.huawei.appmarket",
        "com.bbk.appstore",
        "com.amazon.venezia",
        "com.aurora.store",
        "org.fdroid.fdroid",
    )

    /**
     * Pantallas de los paquetes de [AJUSTES] que se dejan abrir incluso con modo estricto,
     * porque hacen falta y no sirven para desactivar el bloqueo: dar un permiso (Android 8-9),
     * vincular un Bluetooth, escribir la contraseña de una Wi-Fi, los paneles rápidos y la
     * contraseña del «Bloqueo de apps» de Xiaomi (vive en com.miui.securitycenter).
     */
    private val ACTIVIDADES_PERMITIDAS: Set<String> = setOf(
        "com.android.settings.bluetooth.BluetoothPairingDialog",
        "com.android.settings.wifi.WifiDialogActivity",
        "com.android.settings.panel.SettingsPanelActivity",
        "com.miui.applicationlock.ConfirmAccessControl",
    )

    /** True si la pantalla (nombre de clase de la actividad) se deja abrir con modo estricto. */
    fun actividadPermitida(clase: String?): Boolean =
        clase != null && (clase in ACTIVIDADES_PERMITIDAS || clase.endsWith(".permission.ui.GrantPermissionsActivity"))

    /** Diálogo del sistema para dar permiso de VPN (se permite un momento cuando la app lo pide). */
    const val DIALOGO_VPN = "com.android.vpndialogs"

    /** True si [paquete] no se puede bloquear a mano (el propio Bloqueador, el sistema, los launchers...). */
    fun esProtegido(paquete: String, propio: String, lanzadores: Set<String>): Boolean =
        paquete == propio || paquete in PROTEGIDOS || paquete in AJUSTES || paquete in lanzadores
}
