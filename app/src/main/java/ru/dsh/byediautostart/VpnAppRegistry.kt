package ru.dsh.byediautostart

import android.content.Context
import android.content.pm.PackageManager

/** VPN-приложения, которые умеют управляться без открытия интерфейса. */
object VpnAppRegistry {

    enum class Kind { PRIZRAK, INCY, HAPP, HAPP_PLUS }

    data class App(
        val packageName: String,
        val label: String,
        val kind: Kind,
        val installed: Boolean = true,
        val downloadUrl: String = "",
    )

    private const val PRIZRAK_URL = "https://github.com/legiz-ru/Prizrak-Box/releases/latest"
    private const val INCY_URL = "https://github.com/INCY-DEV/incy-platforms/releases/latest"
    private const val HAPP_URL = "https://github.com/Happ-proxy/happ-android/releases/latest"
    private const val HAPP_PLUS_URL = "https://happ.living/plus/download"

    /** Все поддерживаемые VPN, включая неустановленные — их можно скачать. */
    fun supported(context: Context): List<App> {
        val result = mutableListOf<App>()
        addApp(context, result, PrizrakClient.PACKAGE, "Призрак Box", Kind.PRIZRAK, PRIZRAK_URL)
        addApp(context, result, IncyClient.PACKAGE, "INCY", Kind.INCY, INCY_URL)
        HappClient.findPackage(context)?.let { pkg ->
            result += App(pkg, label(context, pkg), Kind.HAPP, downloadUrl = HAPP_URL)
        } ?: run {
            result += App("su.happ.proxyutility", "Happ", Kind.HAPP, installed = false, downloadUrl = HAPP_URL)
        }
        if (HappPlusClient.isInstalled(context)) {
            result += App(
                HappPlusClient.PACKAGE,
                label(context, HappPlusClient.PACKAGE),
                Kind.HAPP_PLUS,
                downloadUrl = HAPP_PLUS_URL,
            )
        } else {
            result += App(
                HappPlusClient.PACKAGE,
                "Happ Plus",
                Kind.HAPP_PLUS,
                installed = false,
                downloadUrl = HAPP_PLUS_URL,
            )
        }
        return result.distinctBy { it.packageName }
    }

    fun installed(context: Context): List<App> = supported(context).filter { it.installed }

    fun find(context: Context, packageName: String?): App? {
        if (packageName.isNullOrBlank()) return null
        return installed(context).firstOrNull { it.packageName == packageName }
    }

    private fun addApp(
        context: Context,
        out: MutableList<App>,
        pkg: String,
        fallbackLabel: String,
        kind: Kind,
        downloadUrl: String,
    ) {
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            out += App(pkg, label(context, pkg), kind, downloadUrl = downloadUrl)
        } catch (e: PackageManager.NameNotFoundException) {
            out += App(pkg, fallbackLabel, kind, installed = false, downloadUrl = downloadUrl)
        }
    }

    private fun label(context: Context, pkg: String): String = try {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        pkg
    }
}
