package app.shellscout.ui.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/** A single-line secret field with an eye button to show or hide what has been typed. Starts hidden. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    /** False hides the eye button and keeps the text masked, e.g. while the field holds a stand-in for a saved secret. */
    allowReveal: Boolean = true,
) {
    var reveal by rememberSaveable { mutableStateOf(false) }
    val visible = reveal && allowReveal
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        label = label, placeholder = placeholder, supportingText = supportingText,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        // Shown text still uses the password keyboard, so it stays out of suggestions and learned words.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = if (allowReveal) {
            {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (visible) "Hide password" else "Show password",
                    )
                }
            }
        } else null,
        modifier = modifier,
    )
}
