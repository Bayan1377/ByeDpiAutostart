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
    )

    fun installed(context: Context): List<App> {
        val result = mutableListOf<App>()
        addIfInstalled(context, result, PrizrakClient.PACKAGE, Kind.PRIZRAK)
        addIfInstalled(context, result, IncyClient.PACKAGE, Kind.INCY)
        HappClient.findPackage(context)?.let { pkg ->
            result += App(pkg, label(context, pkg), Kind.HAPP)
        }
        if (HappPlusClient.isInstalled(context)) {
            result += App(HappPlusClient.PACKAGE, label(context, HappPlusClient.PACKAGE), Kind.HAPP_PLUS)
        }
        return result.distinctBy { it.packageName }
    }

    fun find(context: Context, packageName: String?): App? {
        if (packageName.isNullOrBlank()) return null
        return installed(context).firstOrNull { it.packageName == packageName }
    }

    private fun addIfInstalled(context: Context, out: MutableList<App>, pkg: String, kind: Kind) {
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            out += App(pkg, label(context, pkg), kind)
        } catch (e: PackageManager.NameNotFoundException) {
            // не установлен
        }
    }

    private fun label(context: Context, pkg: String): String = try {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        pkg
    }
}
