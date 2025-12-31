package com.sidhu.androidautoglm.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sidhu.androidautoglm.data.TaskEndState
import com.sidhu.androidautoglm.ui.util.displayText
import com.sidhu.androidautoglm.ui.util.displayColor

/**
 * A reusable badge component for displaying task state.
 *
 * Shows a colored surface with white text indicating the current task state
 * (e.g., "已完成", "已暂停", "出错").
 *
 * @param state The task state to display
 * @param modifier Modifier for the component
 */
@Composable
fun TaskStateBadge(
    state: TaskEndState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val color = try {
        Color(android.graphics.Color.parseColor(state.displayColor()))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color,
        modifier = modifier
    ) {
        Text(
            state.displayText(context),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White
        )
    }
}
