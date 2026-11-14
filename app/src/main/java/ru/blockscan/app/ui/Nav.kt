package ru.blockscan.app.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.blockscan.app.domain.HistoryKind

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val back = remember { { nav.popBackStack(); Unit } }
    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 } },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 10 } },
    ) {
        composable("home") {
            HomeScreen(
                onCheck = { nav.navigate("check/${Uri.encode(it)}") },
                onScan = { nav.navigate("scan?input=${Uri.encode(it)}") },
                onHistory = { nav.navigate("history") },
                onSettings = { nav.navigate("settings") },
                onOpenEntry = { e ->
                    if (e.kind == HistoryKind.SCAN && e.jobId != null) nav.navigate("job/${e.jobId}") else nav.navigate("history/${e.id}")
                },
            )
        }
        composable("check/{target}", arguments = listOf(navArgument("target") { type = NavType.StringType })) { entry ->
            CheckScreen(entry.arguments?.getString("target").orEmpty(), onBack = back)
        }
        composable(
            "scan?input={input}",
            arguments = listOf(navArgument("input") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            ScanScreen(
                initialInput = entry.arguments?.getString("input").orEmpty(),
                onBack = back,
                onOpenItem = { job, t -> nav.navigate("item/$job/${Uri.encode(t)}") },
            )
        }
        composable("job/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            JobScreen(
                jobId = entry.arguments?.getLong("id") ?: 0L,
                onBack = back,
                onOpenItem = { job, t -> nav.navigate("item/$job/${Uri.encode(t)}") },
            )
        }
        composable(
            "item/{job}/{target}",
            arguments = listOf(navArgument("job") { type = NavType.LongType }, navArgument("target") { type = NavType.StringType }),
        ) { entry ->
            val job = entry.arguments?.getLong("job") ?: 0L
            val t = entry.arguments?.getString("target").orEmpty()
            StoredRecordScreen(RecordSource.Item(job, t), key = "item:$job:$t", onBack = back)
        }
        composable("history") {
            HistoryScreen(
                onBack = back,
                onOpen = { e ->
                    if (e.kind == HistoryKind.SCAN && e.jobId != null) nav.navigate("job/${e.jobId}") else nav.navigate("history/${e.id}")
                },
            )
        }
        composable("history/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            StoredRecordScreen(RecordSource.History(id), key = "hist:$id", onBack = back)
        }
        composable("settings") { SettingsScreen(onBack = back) }
    }
}
