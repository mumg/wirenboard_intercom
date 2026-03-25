package net.muratov.intercom.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.ui.screens.FullscreenStreamScreen
import net.muratov.intercom.ui.screens.HomeScreen

object Routes {
    const val Home = "home"
    const val Fullscreen = "fullscreen/{streamId}"

    fun fullscreen(streamId: String): String = "fullscreen/$streamId"
}

@Composable
fun IntercomNavGraph(
    navController: NavHostController,
    webViewUrl: String,
    streams: List<RtspStream>,
    browserVisible: Boolean,
    onStreamSelected: (RtspStream) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.Home,
        modifier = modifier,
    ) {
        composable(Routes.Home) {
            HomeScreen(
                webViewUrl = webViewUrl,
                streams = streams,
                browserVisible = browserVisible,
                onStreamSelected = onStreamSelected,
            )
        }
        composable(
            route = Routes.Fullscreen,
            arguments = listOf(navArgument("streamId") { defaultValue = "" }),
        ) { backStackEntry ->
            val streamId = backStackEntry.arguments?.getString("streamId").orEmpty()
            val stream = streams.firstOrNull { it.id == streamId }
            if (stream != null) {
                FullscreenStreamScreen(stream = stream)
            }
        }
    }
}
