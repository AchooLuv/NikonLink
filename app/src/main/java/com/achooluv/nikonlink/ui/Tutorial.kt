@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.achooluv.nikonlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** Camera-side instructions for Nikon Z bodies (Z50II wording, generic fallbacks). */
val tutorialSections = listOf(
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

@Composable
fun TutorialSheet(onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = InkRaised,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .height(4.dp)
                    .fillMaxWidth(0.12f)
                    .background(InkLine, RoundedCornerShape(2.dp)),
            )
        },
    ) {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                bottom = 32.dp,
            ),
        ) {
            item {
                Text("连接教程", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "照着做一遍，之后每次只需在 App 里点一次「连接相机」。",
                    color = NikonLinkTheme.colors.dim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(20.dp))
            }

            tutorialSections.forEach { section ->
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

@Composable
private fun TutorialRow(step: TutorialStep) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Box(
            Modifier
                .height(22.dp)
                .background(NikonYellow, RoundedCornerShape(11.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(
                step.index.toString(),
                color = androidx.compose.ui.graphics.Color(0xFF161200),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(0.dp))
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

@Composable
fun TutorialButton(onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, NikonLinkTheme.colors.line),
    ) {
        Text("连接教程", color = Paper, fontSize = 14.sp)
    }
}

@Composable
fun TutorialLinkRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            "查看连接教程 →",
            color = NikonYellow,
            fontSize = 13.sp,
            modifier = Modifier.padding(4.dp).clickableNoIndication(onClick),
        )
    }
}
