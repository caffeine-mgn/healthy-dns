@file:OptIn(ExperimentalComposeWebApi::class)

package pw.binom.health.front

import androidx.compose.runtime.*
import org.jetbrains.compose.web.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

object Colors {
    val bg = Color("#121212")
    val surface = Color("#1E1E1E")
    val surfaceAlt = Color("#2A2A2A")
    val primary = Color("#9E9E9E")
    val text = Color("#E0E0E0")
    val textMuted = Color("#9E9E9E")
    val textDim = Color("#6B6B6B")
    val border = Color("#383838")
    val accentGreen = Color("#4ADE80")
    val accentRed = Color("#E04848")
}

@Composable
fun App() {
    var count by remember { mutableStateOf(0) }

    Div(
        attrs = {
            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                alignItems(AlignItems.Center)
                justifyContent(JustifyContent.Center)
                height(100.vh)
                width(100.vw)
                backgroundColor(Colors.bg)
                color(Colors.text)
                fontFamily("system-ui, sans-serif")
            }
        }
    ) {
        H1(
            attrs = {
                style {
                    fontSize(32.px)
                    fontWeight("600")
                    marginBottom(8.px)
                    color(Colors.text)
                }
            }
        ) { Text("PowerDNS Health") }

        P(
            attrs = {
                style {
                    fontSize(14.px)
                    color(Colors.textMuted)
                    marginBottom(24.px)
                }
            }
        ) { Text("Мониторинг DNS-серверов") }

        Button(
            attrs = {
                onClick { count++ }
                style {
                    padding(12.px, 24.px)
                    fontSize(16.px)
                    fontWeight("600")
                    backgroundColor(Colors.primary)
                    color(Color("white"))
                    border(0.px, LineStyle.None, Color.transparent)
                    borderRadius(10.px)
                    cursor("pointer")
                    property("transition", "all 0.15s")
                    marginBottom(12.px)
                }
            }
        ) {
            Text("Нажми меня")
        }

        P(
            attrs = {
                style {
                    fontSize(14.px)
                    color(Colors.textDim)
                }
            }
        ) {
            Text("Нажато раз: $count")
        }
    }
}

fun main() {
    renderComposable(rootElementId = "root") {
        App()
    }
}
