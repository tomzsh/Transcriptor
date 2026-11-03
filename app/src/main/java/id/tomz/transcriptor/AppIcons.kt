package id.tomz.transcriptor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Lucide vectors, ISC/MIT; attribution is packaged in assets/licenses/lucide.txt. */
@Composable
internal fun AppIcon(@DrawableRes resource: Int, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(resource), contentDescription = description, modifier = modifier.size(22.dp))
}
