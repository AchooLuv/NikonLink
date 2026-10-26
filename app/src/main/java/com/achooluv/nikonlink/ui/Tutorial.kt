package com.achooluv.nikonlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.achooluv.nikonlink.data.ConnectMode

data class TutorialStep(
    val index: Int,
    val title: String,
    val body: String,
    val code: String? = null,
)

data class TutorialSection(
    val title: String,
    val steps: List<TutorialStep>,
)

/** Camera-side instructions for Wi-Fi access point mode (camera hosts the network). */
val tutorialSectionsAp = listOf(
    TutorialSection(
        title = "第一次连接",
        steps = listOf(
            TutorialStep(
                1,
                "在相机上打开 Wi-Fi",
                "Z50II：按 MENU → 网络菜单 → 连接至智能设备 → Wi-Fi 连接 → 建立连接。\n" +
                    "其它 Z 机型：设定菜单 → 连接至智能设备 → Wi-Fi 连接。\n" +
                    "D 系列：设定菜单 → Wi-Fi → 网络连接 → 启用。",
            ),
            TutorialStep(
                2,
                "记下屏幕上的 SSID 与密码",
                "相机屏幕会显示网络名和 8 位密码，网络名一般以 NIKON_ 开头，例如：",
                code = "NIKON_Z50II_12345678 / 12345678",
            ),
            TutorialStep(
                3,
                "让手机加入这个 Wi-Fi",
                "方式一：打开系统 Wi-Fi 列表，选择 NIKON_ 开头的网络并输入密码。\n" +
                    "方式二：回到本 App 点「连接相机」，输入同一个 SSID 与密码，App 会自己完成加入。\n" +
                    "系统提示「此 WLAN 无法访问互联网」时，一定要选「保持连接」。",
            ),
            TutorialStep(
                4,
                "在 App 里点连接",
                "App 会自动探测相机的 PTP/IP 服务（默认 192.168.1.1:15740），握手成功后即可看到相机型号、电量与存储卡。",
            ),
        ),
    ),
    TutorialSection(
        title = "保持稳定",
        steps = listOf(
            TutorialStep(
                1,
                "一次只连一个客户端",
                "SnapBridge、尼康无线移动适配器等 App 同时连接会让相机拒绝新会话，请先完全退出它们。",
            ),
            TutorialStep(
                2,
                "关掉手机的「WLAN+ / 智能网络切换」",
                "荣耀 / 华为的 WLAN+ 会因为相机网络没有互联网而自动切回移动数据，导致传输中断。设置 → WLAN → 更多设置 → 关闭 WLAN+。",
            ),
            TutorialStep(
                3,
                "让相机别睡着",
                "设定菜单 → 自动关机时间 → 选择 10 分钟或更长；拍摄间隔较长时可在相机上按一下快门唤醒。",
            ),
            TutorialStep(
                4,
                "距离与遮挡",
                "手机与相机保持 1–3 米、尽量避免金属和人体遮挡；相机天线一般在机身左侧（镜头朝向自己时的右侧）。",
            ),
        ),
    ),
    TutorialSection(
        title = "连不上时",
        steps = listOf(
            TutorialStep(1, "相机端重启 Wi-Fi", "在相机菜单里断开再重新「建立连接」，SSID 与密码可能随之变化。"),
            TutorialStep(2, "忘记网络后重连", "手机 Wi-Fi 设置里「忘记」该 NIKON 网络，再重新输入密码。"),
            TutorialStep(3, "确认没有开启飞行模式", "飞行模式下仍可开 Wi-Fi，但部分机型会限制局域网访问。"),
            TutorialStep(4, "看 App 的报错文案", "「InitFail」表示相机正在被其它设备占用；「连接超时」多为 IP 不在 192.168.1.x 网段。"),
        ),
    ),
)

/** Instructions for Wi-Fi station mode: camera and phone share a router. */
val tutorialSectionsSta = listOf(
    TutorialSection(
        title = "第一次连接",
        steps = listOf(
            TutorialStep(
                1,
                "让相机加入路由器",
                "Z50II / Z8 / Z9：MENU → 网络菜单 → 连接至智能设备 → Wi-Fi 连接（STA 模式）→ 建立配置。\n" +
                    "给配置起个名字 → 搜索 Wi-Fi 网络 → 选家里的路由器 SSID → 输入 Wi-Fi 密码。\n" +
                    "IP 地址选「自动获取」即可（路由器没开 DHCP 时才需要手动填）。",
            ),
            TutorialStep(
                2,
                "手机连同一个路由器",
                "手机连的是同一个路由器 Wi-Fi，不需要连相机的 NIKON_ 热点；此时手机仍可正常上网。\n" +
                    "用手机热点也行：让相机加入手机热点，手机自己保持在这个热点上。",
            ),
            TutorialStep(
                3,
                "在 App 里切到「同一 Wi-Fi」",
                "连接页顶部把模式切到「同一 Wi-Fi」，点「扫描并连接」。App 会扫描当前网段（如 192.168.1.x）里的 15740 端口。",
            ),
            TutorialStep(
                4,
                "找不到就手动填 IP",
                "相机屏幕的 STA 连接信息里能看到它拿到的 IP，形如 192.168.1.50。\n" +
                    "回到 App 点「手动 IP」填进去即可。",
            ),
        ),
    ),
    TutorialSection(
        title = "保持稳定",
        steps = listOf(
            TutorialStep(
                1,
                "关掉路由器的「AP 隔离」",
                "很多路由器默认开启「AP 隔离 / 客户端隔离 / 访客网络」，会让同一 Wi-Fi 下的设备互相不可见——这是 STA 模式找不到相机最常见的原因。",
            ),
            TutorialStep(
                2,
                "确认在同一个网段",
                "2.4G 与 5G 如果用了不同 SSID，相机和手机要连同一个；路由器的访客网络通常有独立网段，也不要用。",
            ),
            TutorialStep(
                3,
                "别让 VPN / 代理拦住局域网",
                "部分 VPN 会接管局域网流量，连接和传输期间先关掉 VPN、代理或「私人 DNS」。",
            ),
            TutorialStep(
                4,
                "相机省电与休眠",
                "设定菜单 → 自动关机时间 → 10 分钟以上；STA 模式下相机屏幕仍会显示连接状态。",
            ),
        ),
    ),
    TutorialSection(
        title = "连不上时",
        steps = listOf(
            TutorialStep(1, "先确认相机已连上路由器", "相机 STA 设置里应显示「连接完成」；没连上路由器时手机自然找不到它。"),
            TutorialStep(2, "手动填一次 IP", "「手动 IP」比扫描更可靠，尤其是路由器开了隔离或网段不在 192.168.1.x 时。"),
            TutorialStep(3, "看诊断日志", "设置 → 诊断 → 复制诊断信息，里面会记录「局域网扫描结果」，能看出扫到了哪些地址。"),
            TutorialStep(4, "换回 AP 模式验证", "如果 STA 一直不行，先用「相机热点」模式确认相机与 PTP/IP 本身正常。"),
        ),
    ),
)

@Composable
fun TutorialScreen(onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(ConnectMode.AP) }
    val sections = if (mode == ConnectMode.AP) tutorialSectionsAp else tutorialSectionsSta

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Fixed header: never scrolls away, never lets content slide under the status bar.
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("连接教程", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (mode == ConnectMode.AP) {
                            "相机热点：手机直接连相机"
                        } else {
                            "同一 Wi-Fi：相机与手机都连路由器"
                        },
                        color = NikonLinkTheme.colors.dim,
                        fontSize = 12.sp,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = PaperDim)
                }
            }

            Spacer(Modifier.height(10.dp))
            ModeSelectorRow(
                mode = mode,
                onChange = { mode = it },
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 32.dp),
            ) {
                item {
                    Text(
                        if (mode == ConnectMode.AP) {
                            "照着做一遍，之后每次只需在 App 里点一次「连接相机」。"
                        } else {
                            "相机加入家里的路由器后，手机在同一 Wi-Fi 下就能连上它，而且还能正常上网。"
                        },
                        color = NikonLinkTheme.colors.dim,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                }

                sections.forEach { section ->
                    item { SectionTitle(section.title) }
                    item { Spacer(Modifier.height(10.dp)) }
                    items(section.steps) { step -> TutorialRow(step) }
                    item { Spacer(Modifier.height(18.dp)) }
                }

                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, NikonYellowDim, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                    ) {
                        Column {
                            Text("小提示", color = NikonYellow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "JPEG 用来预览，RAW（NEF）会原样下载、不在 App 内解码；" +
                                    "视频和 RAW 都支持断点续传，中断后点同一个文件会接着传。",
                                color = PaperDim,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialRow(step: TutorialStep) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Box(
            Modifier
                .height(22.dp)
                .background(NikonYellow, RoundedCornerShape(11.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                step.index.toString(),
                color = androidx.compose.ui.graphics.Color(0xFF161200),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(step.title, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(step.body, color = PaperDim, fontSize = 12.sp, lineHeight = 18.sp)
            if (step.code != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Ink, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Mono(step.code, color = Mint, size = 12)
                }
            }
        }
    }
}

/** Kept for callers that still reference the old bottom-sheet name. */
@Composable
fun TutorialSheet(onDismiss: () -> Unit) = TutorialScreen(onDismiss)
