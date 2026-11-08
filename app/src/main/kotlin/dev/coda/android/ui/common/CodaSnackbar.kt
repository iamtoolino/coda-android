package dev.coda.android.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Night snackbar": a dark notice that fits Coda's night look instead of Material's inverse
 * (near white) bar. Every in-app snackbar goes through [CodaSnackbarHost] and [showNotice].
 */
/** The action a notice offers. [long]: it leads somewhere worth reading first, so the notice stays Long (10 s). */
enum class NoticeAction(val label: String, val long: Boolean = false) {
    Undo("Undo"), Open("Open"), Retry("Retry"), Details("Details", long = true),
}

object NoticeDuration {
    /** Up to this many characters a notice is Short (4 s); longer text needs time to read (Long, 10 s). */
    const val SHORT_MAX_CHARS = 50

    /**
     * Short for a short line; Long for longer text or an action that leads somewhere ([NoticeAction.long], Details).
     * Keyed on the action kind, not its label text. Material's [SnackbarHost] then applies Android's accessibility timeout.
     */
    fun of(message: String, action: NoticeAction? = null): SnackbarDuration =
        if (message.length > SHORT_MAX_CHARS || action?.long == true) SnackbarDuration.Long else SnackbarDuration.Short
}

/** Shows [message] with the duration rule; [indefinite] keeps it until acted on (a Retry for a failed save). */
suspend fun SnackbarHostState.showNotice(message: String, action: NoticeAction? = null, indefinite: Boolean = false): SnackbarResult =
    showSnackbar(message, action?.label, duration = if (indefinite) SnackbarDuration.Indefinite else NoticeDuration.of(message, action))

private val NightContainer = Color(0xFF22252A)
private val NightText = Color(0xFFF1F1EE)
private val NightBorder = Color.White.copy(alpha = .08f)
private val NightShape = RoundedCornerShape(12.dp)

/** The one snackbar host of the app: same placement and motion as Material's, with the Night snackbar inside. */
@Composable
fun CodaSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val actionColor = MaterialTheme.colorScheme.primary
    SnackbarHost(state, modifier) { data ->
        val label = data.visuals.actionLabel
        Snackbar(
            // Material's own data snackbar pads by 12 dp; kept so the placement stays as it was.
            modifier = Modifier.padding(12.dp).border(1.dp, NightBorder, NightShape),
            action = label?.let { { TextButton(onClick = { data.performAction() }, colors = ButtonDefaults.textButtonColors(contentColor = actionColor)) {
                Text(label, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold))
            } } },
            shape = NightShape,
            containerColor = NightContainer,
            contentColor = NightText,
            actionContentColor = actionColor,
        ) {
            Text(data.visuals.message, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = NightText,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
