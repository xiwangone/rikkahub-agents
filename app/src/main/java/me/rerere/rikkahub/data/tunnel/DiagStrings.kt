package me.rerere.rikkahub.data.tunnel

import android.content.Context
import android.content.res.Resources
import me.rerere.rikkahub.R

/**
 * DiagStringKey 到字符串资源的映射。
 * 与 TunnelDiagnostics.kt 的纯函数分离：该文件只做 key 到资源的映射，不含判定逻辑。
 * 用表查不用 when：61 分支的 when 会让圈复杂度（62）超过 detekt 阈值（20），表查复杂度为 1。
 */
private val RES_IDS: Map<DiagStringKey, Int> = mapOf(
    DiagStringKey.MODULE_NOT_STARTED to R.string.tunnel_diag_detail_module_not_started,
    DiagStringKey.MODULE_RUNNING to R.string.tunnel_diag_detail_module_running,
    DiagStringKey.MODULE_BAD_STATE to R.string.tunnel_diag_detail_module_bad_state,
    DiagStringKey.DEVICE_CONNECTED to R.string.tunnel_diag_detail_device_connected,
    DiagStringKey.DEVICE_OFFLINE to R.string.tunnel_diag_detail_device_offline,
    DiagStringKey.DNS_OK to R.string.tunnel_diag_detail_dns_ok,
    DiagStringKey.DNS_FAIL to R.string.tunnel_diag_detail_dns_fail,
    DiagStringKey.DIRECT_OK to R.string.tunnel_diag_detail_direct_ok,
    DiagStringKey.DIRECT_FAIL_HTTP to R.string.tunnel_diag_detail_direct_fail_http,
    DiagStringKey.DIRECT_FAIL_UNREACHABLE to R.string.tunnel_diag_detail_direct_fail_unreachable,
    DiagStringKey.CONTROL_OK to R.string.tunnel_diag_detail_control_ok,
    DiagStringKey.CONTROL_FAIL to R.string.tunnel_diag_detail_control_fail,
    DiagStringKey.NO_PEER to R.string.tunnel_diag_detail_no_peer,
    DiagStringKey.MAGIC_MISSING to R.string.tunnel_diag_detail_magic_missing,
    DiagStringKey.MAGIC_FOUND to R.string.tunnel_diag_detail_magic_found,
    DiagStringKey.RESOLVE_TIMEOUT to R.string.tunnel_diag_detail_resolve_timeout,
    DiagStringKey.RESOLVE_NO_RESPONSE to R.string.tunnel_diag_detail_resolve_no_response,
    DiagStringKey.RESOLVE_NXDOMAIN to R.string.tunnel_diag_detail_resolve_nxdomain,
    DiagStringKey.RESOLVE_RCODE to R.string.tunnel_diag_detail_resolve_rcode,
    DiagStringKey.RESOLVE_NO_A to R.string.tunnel_diag_detail_resolve_no_a,
    DiagStringKey.RESOLVE_OUTSIDE to R.string.tunnel_diag_detail_resolve_outside,
    DiagStringKey.RESOLVE_MATCH to R.string.tunnel_diag_detail_resolve_match,
    DiagStringKey.RESOLVE_MISMATCH to R.string.tunnel_diag_detail_resolve_mismatch,
    DiagStringKey.PEERS_NONE to R.string.tunnel_diag_detail_peers_none,
    DiagStringKey.PEERS_ONLINE to R.string.tunnel_diag_detail_peers_online,
    DiagStringKey.PEERS_ALL_OFFLINE to R.string.tunnel_diag_detail_peers_all_offline,
    DiagStringKey.ROUTES_EXIT_UNSET to R.string.tunnel_diag_detail_routes_exit_unset,
    DiagStringKey.ROUTES_EXIT to R.string.tunnel_diag_detail_routes_exit,
    DiagStringKey.ROUTES_ACCEPT_ON to R.string.tunnel_diag_detail_routes_accept_on,
    DiagStringKey.ROUTES_ACCEPT_OFF to R.string.tunnel_diag_detail_routes_accept_off,
    DiagStringKey.ROUTES to R.string.tunnel_diag_detail_routes,
    DiagStringKey.TCP_BANNER to R.string.tunnel_diag_detail_tcp_banner,
    DiagStringKey.TCP_NO_BANNER to R.string.tunnel_diag_detail_tcp_no_banner,
    DiagStringKey.TCP_FAIL to R.string.tunnel_diag_detail_tcp_fail,
    DiagStringKey.PING_OK to R.string.tunnel_diag_detail_ping_ok,
    DiagStringKey.PING_FAIL to R.string.tunnel_diag_detail_ping_fail,
    DiagStringKey.ERROR_SUFFIX to R.string.tunnel_diag_detail_error_suffix,
    DiagStringKey.PAREN_SUFFIX to R.string.tunnel_diag_detail_paren_suffix,
    DiagStringKey.COPY_HEADER to R.string.tunnel_diag_detail_copy_header,
    DiagStringKey.COPY_ITEM to R.string.tunnel_diag_detail_copy_item,
    DiagStringKey.SNAPSHOT_FAIL to R.string.tunnel_diag_detail_snapshot_fail,
    DiagStringKey.SNAPSHOT_UNKNOWN to R.string.tunnel_diag_detail_snapshot_unknown,
    DiagStringKey.SNAPSHOT_NOT_RUNNING to R.string.tunnel_diag_detail_snapshot_not_running,
    DiagStringKey.MAGIC_MISSING_RESOLVE to R.string.tunnel_diag_detail_magic_missing_resolve,
    DiagStringKey.MAGIC_DNS_CUSTOM_SKIP to R.string.tunnel_diag_detail_magic_dns_custom_skip,
    DiagStringKey.PEER_NO_IP to R.string.tunnel_diag_detail_peer_no_ip,
    DiagStringKey.CHECK_ERROR to R.string.tunnel_diag_detail_check_error,
    DiagStringKey.UNKNOWN_ERROR to R.string.tunnel_diag_detail_unknown_error,
    DiagStringKey.CHECK_TIMEOUT to R.string.tunnel_diag_detail_check_timeout,
    DiagStringKey.NETWORK_UNREADABLE to R.string.tunnel_diag_detail_network_unreadable,
    DiagStringKey.TRANSPORT_CELLULAR to R.string.tunnel_diag_detail_transport_cellular,
    DiagStringKey.TRANSPORT_ETHERNET to R.string.tunnel_diag_detail_transport_ethernet,
    DiagStringKey.TRANSPORT_OTHER to R.string.tunnel_diag_detail_transport_other,
    DiagStringKey.CONTROL_INVALID to R.string.tunnel_diag_detail_control_invalid,
    DiagStringKey.DNS_PROXY_FAIL to R.string.tunnel_diag_detail_dns_proxy_fail,
    DiagStringKey.PROXY_FAIL to R.string.tunnel_diag_detail_proxy_fail,
    DiagStringKey.STATUS_PASS to R.string.tunnel_diag_status_pass,
    DiagStringKey.STATUS_FAIL to R.string.tunnel_diag_status_fail,
    DiagStringKey.STATUS_SKIP to R.string.tunnel_diag_status_skip,
    DiagStringKey.STATUS_RUNNING to R.string.tunnel_diag_status_running,
    DiagStringKey.STATUS_PENDING to R.string.tunnel_diag_status_pending,
)

fun DiagStringKey.toResId(): Int = RES_IDS.getValue(this)

/**
 * 以 Resources 解析诊断明细文案。
 * 解析器在 ViewModelScope 与 onClick 里调用，stringResource 只能在组合期间调用，
 * 故此处用 Resources 实现，组合层经 LocalContext 传入。
 */
class AndroidDiagStrings(private val resources: Resources) : DiagStrings {
    override fun get(key: DiagStringKey, vararg args: Any): String =
        resources.getString(key.toResId(), *args)
}

/** 用 Context 的资源实现 DiagStrings。 */
fun Context.diagStrings(): DiagStrings = AndroidDiagStrings(resources)
